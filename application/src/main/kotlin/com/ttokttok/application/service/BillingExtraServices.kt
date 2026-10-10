package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AppliedDiscount
import com.ttokttok.application.port.`in`.ChangeStudentStatusUseCase
import com.ttokttok.application.port.`in`.DataExportUseCase
import com.ttokttok.application.port.`in`.DiscountKind
import com.ttokttok.application.port.`in`.DiscountPreviewItem
import com.ttokttok.application.port.`in`.DiscountRuleView
import com.ttokttok.application.port.`in`.DiscountUseCase
import com.ttokttok.application.port.`in`.DiscountValueType
import com.ttokttok.application.port.`in`.ExportFile
import com.ttokttok.application.port.`in`.InvoiceUseCase
import com.ttokttok.application.port.`in`.InvoiceView
import com.ttokttok.application.port.`in`.RefundCalculation
import com.ttokttok.application.port.`in`.RefundMethod
import com.ttokttok.application.port.`in`.RefundUseCase
import com.ttokttok.application.port.`in`.RefundView
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DataExportPort
import com.ttokttok.application.port.out.DiscountFacts
import com.ttokttok.application.port.out.DiscountPort
import com.ttokttok.application.port.out.RefundInvoice
import com.ttokttok.application.port.out.RefundPort
import com.ttokttok.application.port.out.SpreadsheetPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun wonText(n: Int): String = String.format(Locale.KOREA, "%,d원", n)
/** 10원 단위 버림 */
private fun floor10(n: Long): Int = ((n / 10) * 10).toInt()

// ───────── PAY-006 원비 할인 규칙 ─────────
@Service
class DiscountService(
    private val guard: AccessGuard,
    private val discounts: DiscountPort,
    private val invoices: InvoiceUseCase,
    private val clock: ClockPort,
) : DiscountUseCase {

    private fun check(c: DiscountUseCase.SaveRule): DiscountUseCase.SaveRule {
        val name = c.name.trim()
        if (name.isEmpty() || name.length > 50) throw InvalidInputException("INVALID_NAME", "규칙 이름은 1~50자입니다")
        val maxThreshold = when (c.kind) { DiscountKind.SIBLING -> 10; DiscountKind.LONG_TERM -> 120; DiscountKind.MULTI_CLASS -> 10 }
        val minThreshold = if (c.kind == DiscountKind.SIBLING || c.kind == DiscountKind.MULTI_CLASS) 2 else 1
        if (c.threshold !in minThreshold..maxThreshold) throw InvalidInputException("INVALID_THRESHOLD", "조건 값은 $minThreshold~$maxThreshold 사이여야 합니다")
        when (c.valueType) {
            DiscountValueType.PERCENT -> if (c.value !in 1..100) throw InvalidInputException("INVALID_VALUE", "할인율은 1~100%입니다")
            DiscountValueType.AMOUNT -> if (c.value !in 10..1_000_000) throw InvalidInputException("INVALID_VALUE", "할인 금액은 10원~100만원입니다")
        }
        return c.copy(name = name)
    }

    @Transactional(readOnly = true)
    override fun rules(actor: UserId, institutionId: InstitutionId): List<DiscountRuleView> {
        guard.requireManager(actor, institutionId)
        return discounts.rules(institutionId, activeOnly = false)
    }

    @Transactional
    override fun addRule(actor: UserId, institutionId: InstitutionId, command: DiscountUseCase.SaveRule): DiscountRuleView {
        guard.requireManager(actor, institutionId)
        if (discounts.rules(institutionId, activeOnly = false).size >= 20) throw InvalidInputException("TOO_MANY_RULES", "할인 규칙은 20개까지 만들 수 있습니다")
        val id = discounts.insertRule(institutionId, check(command), clock.now())
        return discounts.findRule(id)!!.second
    }

    private fun owned(institutionId: InstitutionId, ruleId: UUID) =
        discounts.findRule(ruleId)?.takeIf { it.first == institutionId.value }?.second ?: throw NotFoundException("할인 규칙")

    @Transactional
    override fun updateRule(actor: UserId, institutionId: InstitutionId, ruleId: UUID, command: DiscountUseCase.SaveRule): DiscountRuleView {
        guard.requireManager(actor, institutionId)
        owned(institutionId, ruleId)
        discounts.updateRule(ruleId, check(command), clock.now())
        return discounts.findRule(ruleId)!!.second
    }

    @Transactional
    override fun deleteRule(actor: UserId, institutionId: InstitutionId, ruleId: UUID) {
        guard.requireManager(actor, institutionId)
        owned(institutionId, ruleId)
        discounts.deleteRule(ruleId)
    }

    private fun applies(rule: DiscountRuleView, f: DiscountFacts): Boolean = when (rule.kind) {
        DiscountKind.SIBLING -> f.siblingRank >= rule.threshold
        DiscountKind.LONG_TERM -> f.monthsEnrolled >= rule.threshold
        DiscountKind.MULTI_CLASS -> f.classCount >= rule.threshold
    }

    private fun compute(institutionId: InstitutionId, items: List<InvoiceUseCase.Item>): List<DiscountPreviewItem> {
        if (items.isEmpty() || items.size > 500) throw InvalidInputException("INVALID_ITEMS", "청구 대상은 1~500명입니다")
        val rules = discounts.rules(institutionId, activeOnly = true)
        val facts = discounts.facts(institutionId, items.map { it.studentId }, clock.today()).associateBy { it.studentId }
        return items.map { item ->
            val f = facts[item.studentId] ?: throw InvalidInputException("UNKNOWN_STUDENT", "이 기관의 재원 원생이 아닌 대상이 있습니다")
            var left = item.amount
            val applied = rules.filter { applies(it, f) }.mapNotNull { r ->
                val raw = when (r.valueType) {
                    DiscountValueType.PERCENT -> floor10(item.amount.toLong() * r.value / 100)
                    DiscountValueType.AMOUNT -> r.value
                }
                val amt = minOf(raw, left)
                if (amt <= 0) return@mapNotNull null
                left -= amt
                AppliedDiscount(r.id, r.name, amt)
            }
            val discount = applied.sumOf { it.amount }
            DiscountPreviewItem(f.studentId, f.studentName, item.amount, discount, item.amount - discount, applied, f.siblingRank, f.monthsEnrolled, f.classCount)
        }
    }

    @Transactional(readOnly = true)
    override fun preview(actor: UserId, institutionId: InstitutionId, items: List<InvoiceUseCase.Item>): List<DiscountPreviewItem> {
        guard.requireManager(actor, institutionId)
        return compute(institutionId, items)
    }

    @Transactional
    override fun issueWithDiscounts(
        actor: UserId, institutionId: InstitutionId, command: InvoiceUseCase.Issue,
        applyDiscounts: Boolean, periodStart: LocalDate?, periodEnd: LocalDate?,
    ): List<InvoiceView> {
        guard.requireManager(actor, institutionId)
        if ((periodStart == null) != (periodEnd == null)) throw InvalidInputException("INVALID_PERIOD", "교습 기간은 시작일과 종료일을 함께 입력하세요")
        if (periodStart != null && periodEnd != null) {
            if (periodEnd.isBefore(periodStart)) throw InvalidInputException("INVALID_PERIOD", "교습 기간 종료일이 시작일보다 빠릅니다")
            if (ChronoUnit.DAYS.between(periodStart, periodEnd) > 366) throw InvalidInputException("INVALID_PERIOD", "교습 기간은 1년 이내여야 합니다")
        }
        val preview = if (applyDiscounts) compute(institutionId, command.items).associateBy { it.studentId } else emptyMap()
        val items = command.items.map { i -> preview[i.studentId]?.let { InvoiceUseCase.Item(i.studentId, it.finalAmount) } ?: i }
        val views = invoices.issue(actor, institutionId, command.copy(items = items))
        val base = command.items.associate { it.studentId to it.amount }
        views.forEach { v ->
            val p = preview[v.studentId]
            val note = p?.applied?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.name} -${wonText(it.amount)}" }?.take(300)
            discounts.saveInvoiceExtras(v.id, base[v.studentId] ?: v.amount, p?.discountAmount ?: 0, note, periodStart, periodEnd)
        }
        return views
    }
}

// ───────── PAY-007 중도 퇴원 환불 ─────────
@Service
class RefundService(
    private val guard: AccessGuard,
    private val refunds: RefundPort,
    private val studentStatus: ChangeStudentStatusUseCase,
    private val clock: ClockPort,
) : RefundUseCase {
    private val md = DateTimeFormatter.ofPattern("M/d")

    private fun owned(institutionId: InstitutionId, invoiceId: UUID): RefundInvoice =
        refunds.invoice(invoiceId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("청구서")

    private fun days(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b).toInt()

    /** 금액 계산 (DB 를 안 건드리는 순수 계산) */
    internal fun compute(inv: RefundInvoice, calc: RefundUseCase.Calc): RefundCalculation {
        val start = calc.periodStart ?: inv.periodStart ?: YearMonth.from(inv.dueDate).atDay(1)
        val end = calc.periodEnd ?: inv.periodEnd ?: YearMonth.from(start).atEndOfMonth()
        if (end.isBefore(start)) throw InvalidInputException("INVALID_PERIOD", "교습 기간 종료일이 시작일보다 빠릅니다")
        val total = days(start, end) + 1
        val w = calc.withdrawDate
        val used = days(start, w).coerceIn(0, total)
        val paid = inv.amount
        val lines = mutableListOf("교습 기간 ${md.format(start)}~${md.format(end)} (${total}일), 퇴원일 ${md.format(w)} → 이용 ${used}일")
        val refund: Int = when {
            !w.isAfter(start) -> { lines += "교습 시작 전 퇴원 → 전액 환불"; paid }
            w.isAfter(end) -> { lines += "교습 기간이 끝난 뒤라 환불액 없음"; 0 }
            calc.method == RefundMethod.DAILY -> {
                val r = floor10(paid.toLong() * (total - used) / total)
                lines += "일할: ${wonText(paid)} × 남은 ${total - used}일 / ${total}일 = ${wonText(r)} (10원 미만 버림)"
                r
            }
            else -> lawRefund(paid, start, end, total, w, lines)
        }
        return RefundCalculation(inv.invoiceId, inv.studentName, calc.method, paid, start, end, w, total, used, refund, lines)
    }

    /** 학원법 시행령 별표(교습비 반환 기준). 1개월 단위로 나눠 퇴원한 달은 경과 비율로, 이후 달은 전액 */
    private fun lawRefund(paid: Int, start: LocalDate, end: LocalDate, total: Int, w: LocalDate, lines: MutableList<String>): Int {
        data class Seg(val s: LocalDate, val e: LocalDate, val amount: Int)
        val segs = mutableListOf<Seg>()
        var s = start
        var allocated = 0
        var i = 1L
        while (!s.isAfter(end)) {
            val next = start.plusMonths(i)
            val e = minOf(next.minusDays(1), end)
            val last = e == end
            val amt = if (last) paid - allocated else floor10(paid.toLong() * (days(s, e) + 1) / total)
            segs += Seg(s, e, amt)
            allocated += amt
            s = e.plusDays(1)
            i++
        }
        var refund = 0
        segs.forEach { seg ->
            val segDays = days(seg.s, seg.e) + 1
            val label = "${md.format(seg.s)}~${md.format(seg.e)} ${wonText(seg.amount)}"
            when {
                w.isAfter(seg.e) -> lines += "$label: 이미 지난 기간 → 0원"
                !w.isAfter(seg.s) -> { refund += seg.amount; lines += "$label: 시작 전 → 전액" }
                else -> {
                    val usedSeg = days(seg.s, w)
                    val (r, why) = when {
                        usedSeg * 3 < segDays -> floor10(seg.amount.toLong() * 2 / 3) to "1/3 경과 전 → 2/3 환불"
                        usedSeg * 2 < segDays -> floor10(seg.amount.toLong() / 2) to "1/2 경과 전 → 1/2 환불"
                        else -> 0 to "1/2 경과 후 → 환불 없음"
                    }
                    refund += r
                    lines += "$label: ${segDays}일 중 ${usedSeg}일 경과, $why (${wonText(r)})"
                }
            }
        }
        lines += "학원법 시행령 교습비 반환 기준을 달력 일수로 적용한 값입니다. 교습 시간 기준으로 다르게 정한 경우 금액을 고쳐 기록하세요."
        return refund
    }

    @Transactional(readOnly = true)
    override fun calculate(actor: UserId, institutionId: InstitutionId, invoiceId: UUID, calc: RefundUseCase.Calc): RefundCalculation {
        guard.requireManager(actor, institutionId)
        val inv = owned(institutionId, invoiceId)
        if (inv.status != "PAID") throw ConflictException("NOT_PAID", "수납 완료된 청구서만 환불을 계산할 수 있습니다")
        return compute(inv, calc)
    }

    @Transactional
    override fun record(actor: UserId, institutionId: InstitutionId, invoiceId: UUID, command: RefundUseCase.Record): RefundView {
        guard.requireManager(actor, institutionId)
        val inv = owned(institutionId, invoiceId)
        if (inv.status != "PAID") throw ConflictException("NOT_PAID", "수납 완료된 청구서만 환불 처리할 수 있습니다")
        if (inv.refunded) throw ConflictException("ALREADY_REFUNDED", "이미 환불 처리한 청구서입니다")
        val calc = compute(inv, command.calc)
        val amount = command.refundAmount ?: calc.refundAmount
        if (amount !in 0..inv.amount) throw InvalidInputException("INVALID_AMOUNT", "환불액은 0원~납부액 사이여야 합니다")
        val today = clock.today()
        val on = command.refundedOn ?: today
        if (on.isAfter(today)) throw InvalidInputException("FUTURE_DATE", "환불일이 미래일 수 없습니다")
        val note = command.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > 300) throw InvalidInputException("NOTE_TOO_LONG", "메모는 300자까지입니다")
        val now = clock.now()
        val id = refunds.insert(institutionId, inv, command.calc.method, calc.withdrawDate, calc.periodStart, calc.periodEnd, calc.refundAmount, amount, on, command.payMethod, note, actor, now)
        // 퇴원 처리는 원생 관리(STU)의 상태 변경을 그대로 쓴다 → 반 배정 마감·이력까지 같이 남는다
        if (command.withdrawReason != null && !inv.studentWithdrawn) {
            studentStatus.change(
                ChangeStudentStatusUseCase.Command(actor, institutionId, StudentId(inv.studentId), StudentStatus.WITHDRAWN, command.withdrawReason, calc.withdrawDate, "중도 퇴원 환불"),
            )
        }
        return refunds.find(id)!!
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<RefundView> {
        guard.requireManager(actor, institutionId)
        return refunds.list(institutionId, month.atDay(1), month.atEndOfMonth())
    }
}

// ───────── SET-006 데이터 내보내기 ─────────
@Service
class DataExportService(
    private val guard: AccessGuard,
    private val data: DataExportPort,
    private val spreadsheet: SpreadsheetPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : DataExportUseCase {

    @Transactional
    override fun export(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): ExportFile {
        guard.require(actor, institutionId, Role.OWNER)
        val t = to ?: clock.today()
        val f = from ?: t.minusDays(364)
        if (f.isAfter(t)) throw InvalidInputException("INVALID_RANGE", "시작일이 종료일보다 늦습니다")
        if (f.isBefore(t.minusDays(366))) throw InvalidInputException("INVALID_RANGE", "출결·알림장 기간은 최대 1년입니다")
        val sheets = data.sheets(institutionId, f, t)
        val name = data.institutionName(institutionId).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        val stamp = clock.today().toString()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            sheets.forEachIndexed { i, s ->
                zip.putNextEntry(ZipEntry("%02d_%s.xlsx".format(i + 1, s.name)))
                zip.write(spreadsheet.write(s.name, s.headers, s.rows))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                buildString {
                    appendLine("똑똑에듀 데이터 내보내기 — ${data.institutionName(institutionId)}")
                    appendLine("만든 날: $stamp, 출결·알림장 기간: $f ~ $t")
                    appendLine("원생 생년월일·보호자 연락처 등 개인정보가 들어 있습니다. 보관·전달에 주의하고 쓰임이 끝나면 지워 주세요.")
                    sheets.forEach { appendLine("- ${it.name}: ${it.rows.size}행") }
                }.toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        audit.record(
            AuditEntry(institutionId, actor, "DATA_EXPORT", "institution", institutionId.value.toString(),
                mapOf("from" to f.toString(), "to" to t.toString(), "sheets" to sheets.associate { it.name to it.rows.size }), clock.now()),
        )
        return ExportFile("똑똑에듀_${name}_$stamp.zip", out.toByteArray())
    }
}
