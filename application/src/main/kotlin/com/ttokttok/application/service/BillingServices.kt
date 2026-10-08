package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.BillingWidget
import com.ttokttok.application.port.`in`.BillingWidgetQuery
import com.ttokttok.application.port.`in`.InvoiceFilter
import com.ttokttok.application.port.`in`.InvoiceReminderUseCase
import com.ttokttok.application.port.`in`.InvoiceStatus
import com.ttokttok.application.port.`in`.InvoiceSummary
import com.ttokttok.application.port.`in`.InvoiceUseCase
import com.ttokttok.application.port.`in`.InvoiceView
import com.ttokttok.application.port.`in`.ParentInvoiceUseCase
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.PlanView
import com.ttokttok.application.port.`in`.SubscriptionPaymentView
import com.ttokttok.application.port.`in`.SubscriptionStatus
import com.ttokttok.application.port.`in`.SubscriptionUseCase
import com.ttokttok.application.port.`in`.SubscriptionView
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.InvoicePort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.SubscriptionPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

private const val MAX_AMOUNT = 10_000_000
private const val MAX_ITEMS = 500
private const val TRIAL_DAYS = 14L

private fun won(n: Int): String = String.format(Locale.KOREA, "%,d원", n)
private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"

// ───────── PAY-001·002 청구·수납 ─────────
@Service
class InvoiceService(
    private val invoices: InvoicePort,
    private val guard: AccessGuard,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : InvoiceUseCase {

    @Transactional
    override fun issue(actor: UserId, institutionId: InstitutionId, command: InvoiceUseCase.Issue): List<InvoiceView> {
        guard.requireManager(actor, institutionId)
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "청구 제목은 1~100자입니다")
        val memo = command.memo?.trim()?.takeIf { it.isNotEmpty() }
        if (memo != null && memo.length > 300) throw InvalidInputException("MEMO_TOO_LONG", "메모는 300자까지입니다")
        val today = clock.today()
        if (command.dueDate.isBefore(today.minusDays(31))) throw InvalidInputException("PAST_DUE_DATE", "납부기한이 너무 과거입니다")
        if (command.dueDate.isAfter(today.plusYears(1))) throw InvalidInputException("DUE_DATE_TOO_FAR", "납부기한은 1년 이내여야 합니다")
        if (command.items.isEmpty() || command.items.size > MAX_ITEMS) throw InvalidInputException("INVALID_ITEMS", "청구 대상은 1~${MAX_ITEMS}명입니다")
        if (command.items.map { it.studentId }.toSet().size != command.items.size) throw InvalidInputException("DUPLICATE_STUDENT", "같은 원생이 두 번 들어 있습니다")
        if (command.items.any { it.amount !in 0..MAX_AMOUNT }) throw InvalidInputException("INVALID_AMOUNT", "금액은 0~${won(MAX_AMOUNT)}입니다")
        val valid = invoices.activeStudentIds(institutionId, command.items.map { it.studentId })
        val unknown = command.items.firstOrNull { it.studentId !in valid }
        if (unknown != null) throw InvalidInputException("UNKNOWN_STUDENT", "이 기관의 재원 원생이 아닌 대상이 있습니다")

        val now = clock.now()
        val ids = command.items.map {
            invoices.insert(institutionId, it.studentId, title, it.amount, command.dueDate, memo, actor, if (command.notify) now else null, now)
        }
        val views = invoices.findMany(ids)
        if (command.notify) views.forEach { notifyIssued(it, now) }
        realtime.institutionEvent(institutionId, mapOf("type" to "invoice.issued", "count" to views.size.toString()))
        return views
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, month: YearMonth, filter: InvoiceFilter, classroomId: UUID?, studentId: UUID?): List<InvoiceView> {
        guard.requireManager(actor, institutionId)
        return invoices.list(institutionId, month.atDay(1), month.atEndOfMonth(), filter, clock.today(), classroomId, studentId)
    }

    @Transactional(readOnly = true)
    override fun summary(actor: UserId, institutionId: InstitutionId, month: YearMonth): InvoiceSummary {
        guard.requireManager(actor, institutionId)
        return invoices.summary(institutionId, month, clock.today())
    }

    @Transactional
    override fun markPaid(actor: UserId, institutionId: InstitutionId, id: UUID, method: PayMethod, paidOn: LocalDate?, note: String?): InvoiceView {
        guard.requireManager(actor, institutionId)
        val v = owned(id, institutionId)
        if (v.status != InvoiceStatus.UNPAID) throw ConflictException("NOT_UNPAID", "미납 상태의 청구서만 수납 처리할 수 있습니다")
        val today = clock.today()
        val on = paidOn ?: today
        if (on.isAfter(today)) throw InvalidInputException("FUTURE_PAID_ON", "납부일이 미래일 수 없습니다")
        val n = note?.trim()?.takeIf { it.isNotEmpty() }
        if (n != null && n.length > 200) throw InvalidInputException("NOTE_TOO_LONG", "메모는 200자까지입니다")
        invoices.markPaid(id, method, on, n, actor, clock.now())
        return owned(id, institutionId)
    }

    @Transactional
    override fun markUnpaid(actor: UserId, institutionId: InstitutionId, id: UUID): InvoiceView {
        guard.requireManager(actor, institutionId)
        val v = owned(id, institutionId)
        if (v.status != InvoiceStatus.PAID) throw ConflictException("NOT_PAID", "수납 완료 상태의 청구서만 되돌릴 수 있습니다")
        invoices.markUnpaid(id, actor, clock.now())
        return owned(id, institutionId)
    }

    @Transactional
    override fun cancel(actor: UserId, institutionId: InstitutionId, id: UUID): InvoiceView {
        guard.requireManager(actor, institutionId)
        val v = owned(id, institutionId)
        if (v.status != InvoiceStatus.UNPAID) throw ConflictException("NOT_UNPAID", "미납 상태의 청구서만 취소할 수 있습니다")
        invoices.cancel(id, actor, clock.now())
        return owned(id, institutionId)
    }

    @Transactional
    override fun remind(actor: UserId, institutionId: InstitutionId, id: UUID) {
        guard.requireManager(actor, institutionId)
        val v = owned(id, institutionId)
        if (v.status != InvoiceStatus.UNPAID) throw ConflictException("NOT_UNPAID", "미납 상태의 청구서만 알릴 수 있습니다")
        val now = clock.now()
        notifyIssued(v, now)
        invoices.touchSent(id, now)
    }

    private fun owned(id: UUID, institutionId: InstitutionId): InvoiceView =
        invoices.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("청구서")

    private fun notifyIssued(v: InvoiceView, now: Instant) {
        val users = invoices.guardianUsers(v.studentId)
        if (users.isEmpty()) return
        outbox.publish(
            ParentPushRequested(
                InstitutionId(v.institutionId), "PAYMENT_INVOICE", "[${v.institutionName}] ${v.title} 납부 안내",
                "${v.studentName} · ${won(v.amount)} · 납부기한 ${md(v.dueDate)}",
                mapOf("type" to "invoice", "invoiceId" to v.id.toString(), "studentId" to v.studentId.toString()), users, now,
            ),
        )
        realtime.userEvents(users, mapOf("type" to "invoice.new", "invoiceId" to v.id.toString()))
    }
}

@Service
class ParentInvoiceService(private val invoices: InvoicePort) : ParentInvoiceUseCase {
    @Transactional(readOnly = true)
    override fun mine(parent: UserId, studentIds: Set<UUID>?, limit: Int): List<InvoiceView> {
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        return invoices.listForParent(parent, studentIds, limit)
    }
}

// ───────── PAY-003 미납 자동 알림 ─────────
@Service
class InvoiceReminderService(
    private val invoices: InvoicePort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : InvoiceReminderUseCase {

    /** 반환값은 처리한 건수 — limit 과 같으면 스케줄러가 다음 배치를 이어서 돈다.
     *  단계: DUE_SOON(기한 1~3일 전) · DUE_DAY(당일) · OVERDUE_1(1~6일 경과) · OVERDUE_7(7일 이상, 30일까지). 각 단계 1회 */
    @Transactional
    override fun remindDue(limit: Int): Int {
        val now = clock.now()
        val today = clock.today()
        val batch = invoices.dueReminders(today, limit)
        for (r in batch) {
            if (!invoices.markReminded(r.invoiceId, r.stage, now)) continue
            val users = invoices.guardianUsers(r.studentId)
            if (users.isEmpty()) continue
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, r.dueDate).toInt()
            val headline = when (r.stage) {
                "DUE_SOON" -> "납부기한이 ${days}일 남았어요"
                "DUE_DAY" -> "오늘이 납부기한이에요"
                else -> "납부기한이 ${-days}일 지났어요"
            }
            outbox.publish(
                ParentPushRequested(
                    InstitutionId(r.institutionId), "PAYMENT_REMINDER", "[${r.institutionName}] $headline",
                    "${r.studentName} · ${r.title} ${won(r.amount)} (기한 ${md(r.dueDate)})",
                    mapOf("type" to "invoice", "invoiceId" to r.invoiceId.toString(), "studentId" to r.studentId.toString()), users, now,
                ),
            )
            realtime.userEvents(users, mapOf("type" to "invoice.reminder", "invoiceId" to r.invoiceId.toString()))
        }
        // 보호자 계정이 없어 못 보낸 건도 기록은 남기므로(다시 안 잡힘) 처리한 건수를 돌려준다
        return batch.size
    }
}

// ───────── SET-003 구독 ─────────
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : SubscriptionUseCase {

    @Transactional
    override fun get(actor: UserId, institutionId: InstitutionId): SubscriptionView {
        guard.requireManager(actor, institutionId)
        return view(institutionId)
    }

    @Transactional(readOnly = true)
    override fun plans(actor: UserId, institutionId: InstitutionId): List<PlanView> {
        guard.requireManager(actor, institutionId)
        return subscriptions.plans()
    }

    @Transactional
    override fun changePlan(actor: UserId, institutionId: InstitutionId, planCode: String): SubscriptionView {
        guard.require(actor, institutionId, Role.OWNER)
        val plan = subscriptions.plan(planCode) ?: throw NotFoundException("요금제")
        if (plan.code == "TRIAL") throw InvalidInputException("TRIAL_NOT_SELECTABLE", "무료 체험은 선택할 수 없습니다")
        if (plan.monthlyPrice == null) throw InvalidInputException("CONTACT_REQUIRED", "${plan.name} 플랜은 별도 문의가 필요합니다")
        val count = subscriptions.studentCount(institutionId)
        if (plan.studentLimit != null && count > plan.studentLimit) {
            throw ConflictException("OVER_LIMIT", "현재 원생 ${count}명이 ${plan.name} 플랜 한도(${plan.studentLimit}명)를 넘습니다")
        }
        val now = clock.now()
        val current = subscriptions.ensure(institutionId, clock.today().plusDays(TRIAL_DAYS), now)
        val next = if (current.planCode == plan.code && current.nextBillingDate != null) current.nextBillingDate else clock.today().plusMonths(1)
        subscriptions.changePlan(institutionId, plan.code, SubscriptionStatus.ACTIVE, next, now)
        return view(institutionId)
    }

    @Transactional
    override fun setCard(actor: UserId, institutionId: InstitutionId, brand: String, last4: String): SubscriptionView {
        guard.require(actor, institutionId, Role.OWNER)
        val b = brand.trim()
        if (b.isEmpty() || b.length > 20) throw InvalidInputException("INVALID_BRAND", "카드사는 1~20자입니다")
        if (!Regex("^\\d{4}$").matches(last4)) throw InvalidInputException("INVALID_LAST4", "카드 끝 4자리 숫자만 입력해 주세요")
        subscriptions.ensure(institutionId, clock.today().plusDays(TRIAL_DAYS), clock.now())
        subscriptions.setCard(institutionId, b, last4, clock.now())
        return view(institutionId)
    }

    @Transactional
    override fun setCancelAtPeriodEnd(actor: UserId, institutionId: InstitutionId, cancel: Boolean): SubscriptionView {
        guard.require(actor, institutionId, Role.OWNER)
        subscriptions.ensure(institutionId, clock.today().plusDays(TRIAL_DAYS), clock.now())
        subscriptions.setCancelAtPeriodEnd(institutionId, cancel, clock.now())
        return view(institutionId)
    }

    @Transactional(readOnly = true)
    override fun payments(actor: UserId, institutionId: InstitutionId): List<SubscriptionPaymentView> {
        guard.requireManager(actor, institutionId)
        return subscriptions.payments(institutionId, 50)
    }

    private fun view(institutionId: InstitutionId): SubscriptionView {
        val row = subscriptions.ensure(institutionId, clock.today().plusDays(TRIAL_DAYS), clock.now())
        val plan = subscriptions.plan(row.planCode) ?: throw NotFoundException("요금제")
        return SubscriptionView(plan, row.status, row.trialEndsOn, row.nextBillingDate, row.cancelAtPeriodEnd, row.cardBrand, row.cardLast4, subscriptions.studentCount(institutionId))
    }
}

// ───────── DASH-007 ─────────
@Service
class BillingWidgetService(
    private val subscriptionService: SubscriptionService,
    private val invoices: InvoicePort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : BillingWidgetQuery {
    @Transactional
    override fun widget(actor: UserId, institutionId: InstitutionId): BillingWidget {
        guard.requireManager(actor, institutionId)
        val s = subscriptionService.get(actor, institutionId)
        val today = clock.today()
        val daysLeft = if (s.status == SubscriptionStatus.TRIAL) s.trialEndsOn?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0) } else null
        return BillingWidget(
            s.plan.name, s.status, s.nextBillingDate, daysLeft, s.studentCount, s.plan.studentLimit, s.usagePercent,
            invoices.summary(institutionId, YearMonth.from(today), today),
        )
    }
}
