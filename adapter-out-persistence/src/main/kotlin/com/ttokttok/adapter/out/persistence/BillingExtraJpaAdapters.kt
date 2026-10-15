package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.DiscountRuleEntity
import com.ttokttok.adapter.out.persistence.entity.InvoiceEntity
import com.ttokttok.adapter.out.persistence.entity.RefundRecordEntity
import com.ttokttok.adapter.out.persistence.repository.DiscountRuleJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InvoiceJpaRepository
import com.ttokttok.adapter.out.persistence.repository.RefundRecordJpaRepository
import com.ttokttok.application.port.`in`.DiscountKind
import com.ttokttok.application.port.`in`.DiscountRuleView
import com.ttokttok.application.port.`in`.DiscountUseCase
import com.ttokttok.application.port.`in`.DiscountValueType
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.RefundMethod
import com.ttokttok.application.port.`in`.RefundView
import com.ttokttok.application.port.out.DataExportPort
import com.ttokttok.application.port.out.DiscountFacts
import com.ttokttok.application.port.out.DiscountPort
import com.ttokttok.application.port.out.ExportSheet
import com.ttokttok.application.port.out.RefundInvoice
import com.ttokttok.application.port.out.RefundPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ───────── PAY-006 ─────────
@Component
class DiscountJpaAdapter(
    private val rules: DiscountRuleJpaRepository,
    private val invoices: InvoiceJpaRepository,
    private val q: JpaQueries,
) : DiscountPort {

    private fun DiscountRuleEntity.toView() = DiscountRuleView(
        id, name, DiscountKind.valueOf(kind), threshold, DiscountValueType.valueOf(valueType), value, active,
    )

    override fun rules(institutionId: InstitutionId, activeOnly: Boolean): List<DiscountRuleView> =
        (if (activeOnly) rules.findByInstitutionIdAndActiveOrderByKindAscThresholdAscCreatedAtAsc(institutionId.value, true)
        else rules.findByInstitutionIdOrderByKindAscThresholdAscCreatedAtAsc(institutionId.value)).map { it.toView() }

    override fun findRule(id: UUID): Pair<UUID, DiscountRuleView>? = rules.findByIdOrNull(id)?.let { it.institutionId to it.toView() }

    override fun insertRule(institutionId: InstitutionId, command: DiscountUseCase.SaveRule, now: Instant): UUID {
        val id = Uuid7.next()
        rules.save(
            DiscountRuleEntity(
                id, institutionId.value, command.name, command.kind.name, command.threshold, command.valueType.name, command.value,
                command.active, now, now,
            ),
        )
        return id
    }

    override fun updateRule(id: UUID, command: DiscountUseCase.SaveRule, now: Instant) {
        val e = rules.findByIdOrNull(id) ?: return
        e.name = command.name
        e.kind = command.kind.name
        e.threshold = command.threshold
        e.valueType = command.valueType.name
        e.value = command.value
        e.active = command.active
        e.updatedAt = now
        rules.save(e)
    }

    override fun deleteRule(id: UUID) {
        if (rules.existsById(id)) rules.deleteById(id)
    }

    /**
     * 형제: 같은 기관에서 보호자 전화번호(해시)를 하나라도 같이 쓰는 재원 원생. 먼저 등록한(첫 반 배정일 → 등록일) 순으로 몇째인지 센다.
     * 장기: 첫 반 배정일부터 기준일까지 개월 수. 다과목: 지금 다니는 반 수.
     * (with 절·행 값 비교·age() 를 쓰는 집계라 네이티브 쿼리)
     */
    override fun facts(institutionId: InstitutionId, studentIds: Collection<UUID>, asOf: LocalDate): List<DiscountFacts> {
        if (studentIds.isEmpty()) return emptyList()
        return q.rows(
            """with active as (
                   select s.id, s.name, s.created_at,
                          coalesce((select min(e.from_date) from enrollment e where e.student_id = s.id), s.created_at::date) as since
                     from student s
                    where s.institution_id = :i and s.status <> 'WITHDRAWN'
               )
               select a.id, a.name,
                      1 + (select count(distinct b.id)
                             from guardian g1
                             join guardian g2 on g2.phone_hash = g1.phone_hash and g2.student_id <> g1.student_id
                             join active b on b.id = g2.student_id
                            where g1.student_id = a.id
                              and (b.since, b.created_at, b.id) < (a.since, a.created_at, a.id)) as sibling_rank,
                      greatest(0, (extract(year from age(cast(:d as date), a.since)) * 12 + extract(month from age(cast(:d as date), a.since)))::int) as months,
                      (select count(*) from enrollment e join classroom c on c.id = e.classroom_id and c.deleted_at is null
                        where e.student_id = a.id and e.to_date is null) as classes
                 from active a
                where a.id in (:ids)""",
            params("i" to institutionId.value, "ids" to studentIds, "d" to asOf),
        ).map { DiscountFacts(it.uuid("id"), it.str("name"), it.int("sibling_rank"), it.int("months"), it.int("classes")) }
    }

    override fun saveInvoiceExtras(invoiceId: UUID, baseAmount: Int, discountAmount: Int, discountNote: String?, periodStart: LocalDate?, periodEnd: LocalDate?) {
        val e = invoices.findByIdOrNull(invoiceId) ?: return
        e.baseAmount = baseAmount
        e.discountAmount = discountAmount
        e.discountNote = discountNote
        e.periodStart = periodStart
        e.periodEnd = periodEnd
        e.updatedAt = Instant.now()
        invoices.save(e)
    }
}

// ───────── PAY-007 ─────────
@Component
class RefundJpaAdapter(private val refunds: RefundRecordJpaRepository) : RefundPort {

    private fun view(r: Array<Any?>): RefundView {
        val e = r[0] as RefundRecordEntity
        return RefundView(
            e.id, e.invoiceId, r[1] as String, e.studentId, r[2] as String,
            RefundMethod.valueOf(e.method), e.withdrawDate, e.periodStart, e.periodEnd,
            e.paidAmount, e.calculatedAmount, e.refundAmount, e.refundedOn,
            PayMethod.valueOf(e.payMethod), e.note, r[3] as String, e.createdAt,
        )
    }

    override fun invoice(id: UUID): RefundInvoice? =
        refunds.findInvoice(id).firstOrNull()?.let { r ->
            val i = r[0] as InvoiceEntity
            RefundInvoice(
                i.id, i.institutionId, i.studentId, r[1] as String, i.title,
                i.amount, i.status, i.dueDate, i.periodStart, i.periodEnd,
                (r[3] as Number).toLong() > 0, r[2] == "WITHDRAWN",
            )
        }

    override fun insert(
        institutionId: InstitutionId, invoice: RefundInvoice, method: RefundMethod, withdrawDate: LocalDate, periodStart: LocalDate, periodEnd: LocalDate,
        calculated: Int, refund: Int, refundedOn: LocalDate, payMethod: PayMethod, note: String?, by: UserId, now: Instant,
    ): UUID {
        val id = Uuid7.next()
        refunds.save(
            RefundRecordEntity(
                id, institutionId.value, invoice.invoiceId, invoice.studentId, method.name, withdrawDate, periodStart, periodEnd,
                invoice.amount, calculated, refund, refundedOn, payMethod.name, note, by.value, now,
            ),
        )
        return id
    }

    override fun find(id: UUID): RefundView? = refunds.findView(id).firstOrNull()?.let(::view)

    override fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<RefundView> =
        refunds.findViews(institutionId.value, from, to).map(::view)
}

// ───────── SET-006 데이터 내보내기 ─────────
@Component
class DataExportJpaAdapter(private val q: JpaQueries, private val crypto: FieldCrypto) : DataExportPort {
    private val zone = ZoneId.of("Asia/Seoul")
    private val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val days = listOf("월", "화", "수", "목", "금", "토", "일")

    private fun ts(rs: Row, col: String) = rs.instantOrNull(col)?.let { dt.format(it.atZone(zone)) }.orEmpty()
    private fun d(rs: Row, col: String) = rs.dateOrNull(col)?.toString().orEmpty()
    private fun s(rs: Row, col: String) = rs.strOrNull(col).orEmpty()
    private fun yn(rs: Row, col: String) = if (rs.bool(col)) "Y" else ""
    private fun dec(v: String?) = v?.let { runCatching { crypto.decrypt(it) }.getOrDefault("(복호화 실패)") }.orEmpty()

    /** 시트마다 PostgreSQL 집계(string_agg, to_char)가 들어가 네이티브 쿼리로 읽는다 */
    private fun sheet(name: String, headers: List<String>, sql: String, p: Map<String, Any?>, row: (Row) -> List<String>) =
        ExportSheet(name, headers, q.rows(sql, p).map(row))

    override fun institutionName(institutionId: InstitutionId): String =
        q.scalar("select name from institution where id = :i", params("i" to institutionId.value)) as String? ?: "기관"

    override fun sheets(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<ExportSheet> {
        val p = params("i" to institutionId.value, "f" to from, "t" to to)
        val pAt = params(
            "i" to institutionId.value,
            "fa" to from.atStartOfDay(zone).toInstant(), "ta" to to.plusDays(1).atStartOfDay(zone).toInstant(),
        )
        return listOf(
            sheet(
                "원생", listOf("원생ID", "이름", "생년월일", "학년", "상태", "현재 반", "메모", "등록일"),
                """select s.id, s.name, s.birth_enc, s.grade, s.status, s.memo, s.created_at,
                          (select string_agg(c.name, ', ' order by c.name) from enrollment e join classroom c on c.id = e.classroom_id
                            where e.student_id = s.id and e.to_date is null) as classes
                     from student s where s.institution_id = :i order by s.name""", p,
            ) { rs -> listOf(s(rs, "id"), s(rs, "name"), dec(rs.strOrNull("birth_enc")), s(rs, "grade"), s(rs, "status"), s(rs, "classes"), s(rs, "memo"), ts(rs, "created_at")) },
            sheet(
                "보호자", listOf("원생ID", "원생", "관계", "전화번호", "대표 보호자", "앱 연결"),
                """select g.student_id, s.name, g.relation, g.phone_enc, g.is_primary, g.link_status
                     from guardian g join student s on s.id = g.student_id where g.institution_id = :i order by s.name, g.is_primary desc""", p,
            ) { rs -> listOf(s(rs, "student_id"), s(rs, "name"), s(rs, "relation"), dec(rs.strOrNull("phone_enc")), yn(rs, "is_primary"), s(rs, "link_status")) },
            sheet(
                "반", listOf("반ID", "반", "요일", "시작", "종료", "정원", "담당 교사", "삭제일"),
                """select c.id, c.name, c.days_mask, to_char(c.start_time, 'HH24:MI') as st, to_char(c.end_time, 'HH24:MI') as et, c.capacity, c.deleted_at,
                          (select string_agg(u.name, ', ' order by u.name) from classroom_teacher ct join app_user u on u.id = ct.user_id where ct.classroom_id = c.id) as teachers
                     from classroom c where c.institution_id = :i order by c.deleted_at nulls first, c.name""", p,
            ) { rs ->
                val mask = rs.int("days_mask")
                listOf(s(rs, "id"), s(rs, "name"), days.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(""), s(rs, "st"), s(rs, "et"),
                    rs.int("capacity").toString(), s(rs, "teachers"), ts(rs, "deleted_at"))
            },
            sheet(
                "반 배정", listOf("원생ID", "원생", "반", "시작일", "종료일"),
                """select e.student_id, s.name, c.name as cname, e.from_date, e.to_date
                     from enrollment e join student s on s.id = e.student_id join classroom c on c.id = e.classroom_id
                    where s.institution_id = :i order by s.name, e.from_date""", p,
            ) { rs -> listOf(s(rs, "student_id"), s(rs, "name"), s(rs, "cname"), d(rs, "from_date"), d(rs, "to_date")) },
            sheet(
                "출결", listOf("날짜", "원생ID", "원생", "반", "상태", "지각", "조퇴", "등원", "하원"),
                """select ad.date, ad.student_id, s.name, c.name as cname, ad.status, ad.is_late, ad.is_early_leave, ad.check_in_at, ad.check_out_at
                     from attendance_day ad join student s on s.id = ad.student_id join classroom c on c.id = ad.classroom_id
                    where ad.institution_id = :i and ad.date between :f and :t order by ad.date, c.name, s.name""", p,
            ) { rs -> listOf(d(rs, "date"), s(rs, "student_id"), s(rs, "name"), s(rs, "cname"), s(rs, "status"), yn(rs, "is_late"), yn(rs, "is_early_leave"), ts(rs, "check_in_at"), ts(rs, "check_out_at")) },
            sheet(
                "알림장·공지", listOf("발송", "종류", "제목", "작성자", "대상(원생)", "열람(원생)", "본문"),
                """select n.sent_at, n.kind, n.title, u.name as author, n.body,
                          (select count(distinct r.student_id) from notice_recipient r where r.notice_id = n.id) as targets,
                          (select count(distinct r.student_id) from notice_recipient r where r.notice_id = n.id and r.read_at is not null) as reads
                     from notice n join app_user u on u.id = n.author_id
                    where n.institution_id = :i and n.status = 'SENT' and n.sent_at >= :fa and n.sent_at < :ta order by n.sent_at""", pAt,
            ) { rs -> listOf(ts(rs, "sent_at"), s(rs, "kind"), s(rs, "title"), s(rs, "author"), rs.int("targets").toString(), rs.int("reads").toString(), s(rs, "body").take(32_000)) },
            sheet(
                "청구·수납", listOf("청구ID", "원생", "제목", "할인 전", "할인", "할인 내역", "청구액", "납부기한", "교습 시작", "교습 종료", "상태", "납부일", "결제수단", "메모"),
                """select i.id, s.name, i.title, i.base_amount, i.discount_amount, i.discount_note, i.amount, i.due_date, i.period_start, i.period_end,
                          i.status, i.paid_on, i.pay_method, i.memo
                     from invoice i join student s on s.id = i.student_id where i.institution_id = :i order by i.due_date, s.name""", p,
            ) { rs ->
                listOf(s(rs, "id"), s(rs, "name"), s(rs, "title"), rs.intOrNull("base_amount")?.toString().orEmpty(), rs.int("discount_amount").toString(),
                    s(rs, "discount_note"), rs.int("amount").toString(), d(rs, "due_date"), d(rs, "period_start"), d(rs, "period_end"),
                    s(rs, "status"), d(rs, "paid_on"), s(rs, "pay_method"), s(rs, "memo"))
            },
            sheet(
                "환불", listOf("청구ID", "원생", "계산 방식", "퇴원일", "납부액", "계산액", "환불액", "환불일", "환불 수단", "메모"),
                """select r.invoice_id, s.name, r.method, r.withdraw_date, r.paid_amount, r.calculated_amount, r.refund_amount, r.refunded_on, r.pay_method, r.note
                     from refund_record r join student s on s.id = r.student_id where r.institution_id = :i order by r.refunded_on""", p,
            ) { rs ->
                listOf(s(rs, "invoice_id"), s(rs, "name"), s(rs, "method"), d(rs, "withdraw_date"), rs.int("paid_amount").toString(),
                    rs.int("calculated_amount").toString(), rs.int("refund_amount").toString(), d(rs, "refunded_on"), s(rs, "pay_method"), s(rs, "note"))
            },
            sheet(
                "상담", listOf("일시", "교사", "원생", "방식", "상태", "주제"),
                """select cs.starts_at, t.name as teacher, s.name as student, cs.mode, cs.status, cs.topic
                     from consult_slot cs join app_user t on t.id = cs.teacher_id left join student s on s.id = cs.student_id
                    where cs.institution_id = :i and cs.status in ('BOOKED', 'DONE') order by cs.starts_at""", p,
            ) { rs -> listOf(ts(rs, "starts_at"), s(rs, "teacher"), s(rs, "student"), s(rs, "mode"), s(rs, "status"), s(rs, "topic")) },
        )
    }
}
