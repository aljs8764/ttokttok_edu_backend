package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ActivePlatformNoticeQuery
import com.ttokttok.application.port.`in`.InstitutionSupportUseCase
import com.ttokttok.application.port.`in`.MetricsMonth
import com.ttokttok.application.port.`in`.PlatformAccessQuery
import com.ttokttok.application.port.`in`.PlatformBillingUseCase
import com.ttokttok.application.port.`in`.PlatformInstitutionDetail
import com.ttokttok.application.port.`in`.PlatformInstitutionRow
import com.ttokttok.application.port.`in`.PlatformInstitutionUseCase
import com.ttokttok.application.port.`in`.PlatformMetrics
import com.ttokttok.application.port.`in`.PlatformMetricsUseCase
import com.ttokttok.application.port.`in`.PlatformNoticeKind
import com.ttokttok.application.port.`in`.PlatformNoticeUseCase
import com.ttokttok.application.port.`in`.PlatformNoticeView
import com.ttokttok.application.port.`in`.PlatformPaymentView
import com.ttokttok.application.port.`in`.PlatformSupportUseCase
import com.ttokttok.application.port.`in`.ServiceStatus
import com.ttokttok.application.port.`in`.SubscriptionStatus
import com.ttokttok.application.port.`in`.TicketCategory
import com.ttokttok.application.port.`in`.TicketStatus
import com.ttokttok.application.port.`in`.TicketView
import com.ttokttok.application.port.`in`.VoucherSettlementView
import com.ttokttok.application.port.`in`.VoucherUseCase
import com.ttokttok.application.port.`in`.VoucherView
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.PlatformAdminPort
import com.ttokttok.application.port.out.PlatformInstitutionPort
import com.ttokttok.application.port.out.PlatformMetricsPort
import com.ttokttok.application.port.out.PlatformNoticePort
import com.ttokttok.application.port.out.PlatformPaymentPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.SubscriptionPort
import com.ttokttok.application.port.out.SupportTicketPort
import com.ttokttok.application.port.out.VoucherPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.TeacherPushRequested
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** 운영사(platform_admin) 확인. 운영사 API 는 모두 여기를 먼저 거친다 */
@Component
class PlatformGuard(private val admins: PlatformAdminPort) {
    fun require(actor: UserId) {
        if (!admins.isAdmin(actor)) throw ForbiddenException("운영사 권한이 필요합니다")
    }
}

@Service
class PlatformAccessService(private val admins: PlatformAdminPort) : PlatformAccessQuery {
    override fun isPlatformAdmin(userId: UserId) = admins.isAdmin(userId)
}

// ───────── SAAS-001 ─────────
@Service
class PlatformInstitutionService(
    private val guard: PlatformGuard,
    private val institutions: PlatformInstitutionPort,
    private val subscriptions: SubscriptionPort,
    private val payments: PlatformPaymentPort,
    private val clock: ClockPort,
) : PlatformInstitutionUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, status: ServiceStatus?, keyword: String?): List<PlatformInstitutionRow> {
        guard.require(actor)
        return institutions.list(status, keyword?.trim()?.takeIf { it.isNotEmpty() })
    }

    @Transactional
    override fun detail(actor: UserId, institutionId: UUID): PlatformInstitutionDetail {
        guard.require(actor)
        return buildDetail(institutionId)
    }

    @Transactional
    override fun approve(actor: UserId, institutionId: UUID): PlatformInstitutionRow {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        institutions.setStatus(institutionId, ServiceStatus.ACTIVE, null, clock.now())
        return institutions.find(institutionId) ?: throw NotFoundException("기관")
    }

    @Transactional
    override fun suspend(actor: UserId, institutionId: UUID, reason: String): PlatformInstitutionRow {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        val r = reason.trim()
        if (r.isEmpty() || r.length > 200) throw InvalidInputException("INVALID_REASON", "정지 사유는 1~200자입니다")
        institutions.setStatus(institutionId, ServiceStatus.SUSPENDED, r, clock.now())
        return institutions.find(institutionId) ?: throw NotFoundException("기관")
    }

    @Transactional
    override fun overrideSubscription(actor: UserId, institutionId: UUID, planCode: String, status: SubscriptionStatus, nextBillingDate: LocalDate?): PlatformInstitutionDetail {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        subscriptions.plan(planCode) ?: throw NotFoundException("요금제")
        val now = clock.now()
        subscriptions.ensure(InstitutionId(institutionId), clock.today().plusDays(14), now)
        subscriptions.changePlan(InstitutionId(institutionId), planCode, status, nextBillingDate, now)
        return buildDetail(institutionId)
    }

    private fun buildDetail(institutionId: UUID): PlatformInstitutionDetail {
        val row = institutions.find(institutionId) ?: throw NotFoundException("기관")
        val sub = subscriptions.ensure(InstitutionId(institutionId), clock.today().plusDays(14), clock.now())
        return PlatformInstitutionDetail(
            row, sub.nextBillingDate, sub.trialEndsOn, sub.cancelAtPeriodEnd, sub.cardBrand, sub.cardLast4,
            institutions.staffCount(institutionId), payments.recent(institutionId, 10),
        )
    }
}

// ───────── SAAS-002 ─────────
@Service
class PlatformBillingService(
    private val guard: PlatformGuard,
    private val payments: PlatformPaymentPort,
    private val subscriptions: SubscriptionPort,
    private val institutions: PlatformInstitutionPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : PlatformBillingUseCase {

    @Transactional(readOnly = true)
    override fun payments(actor: UserId, status: String?, month: YearMonth?): List<PlatformPaymentView> {
        guard.require(actor)
        if (status != null && status !in setOf("PAID", "FAILED", "REFUNDED")) throw InvalidInputException("INVALID_STATUS", "status 는 PAID·FAILED·REFUNDED 입니다")
        return payments.list(status, month?.atDay(1), month?.atEndOfMonth())
    }

    @Transactional
    override fun record(actor: UserId, institutionId: UUID, command: PlatformBillingUseCase.Record): PlatformPaymentView {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        subscriptions.plan(command.planCode) ?: throw NotFoundException("요금제")
        if (command.amount < 0 || command.amount > 100_000_000) throw InvalidInputException("INVALID_AMOUNT", "금액이 올바르지 않습니다")
        if (command.periodEnd.isBefore(command.periodStart)) throw InvalidInputException("INVALID_PERIOD", "이용 기간이 올바르지 않습니다")
        val reason = command.failureReason?.trim()?.takeIf { it.isNotEmpty() }
        if (!command.paid && reason == null) throw InvalidInputException("REASON_REQUIRED", "실패 사유를 입력해 주세요")
        if (reason != null && reason.length > 200) throw InvalidInputException("REASON_TOO_LONG", "실패 사유는 200자까지입니다")
        val now = clock.now()
        val inst = InstitutionId(institutionId)
        subscriptions.ensure(inst, clock.today().plusDays(14), now)
        val id = payments.insert(institutionId, command.planCode, command.amount, if (command.paid) "PAID" else "FAILED", command.periodStart, command.periodEnd, if (command.paid) now else null, if (command.paid) null else reason, now)
        if (command.paid) {
            subscriptions.changePlan(inst, command.planCode, SubscriptionStatus.ACTIVE, command.periodEnd.plusDays(1), now)
        } else {
            subscriptions.setStatus(inst, SubscriptionStatus.PAST_DUE, null, now)
            notifyFailed(inst, reason ?: "", now)
        }
        return payments.find(id) ?: throw NotFoundException("결제")
    }

    @Transactional
    override fun retry(actor: UserId, paymentId: UUID): PlatformPaymentView {
        guard.require(actor)
        val p = payments.find(paymentId) ?: throw NotFoundException("결제")
        if (p.status != "FAILED") throw ConflictException("NOT_FAILED", "실패한 결제만 재시도할 수 있습니다")
        val now = clock.now()
        payments.update(paymentId, "PAID", now, null)
        subscriptions.setStatus(InstitutionId(p.institutionId), SubscriptionStatus.ACTIVE, p.periodEnd.plusDays(1), now)
        return payments.find(paymentId) ?: throw NotFoundException("결제")
    }

    @Transactional
    override fun refund(actor: UserId, paymentId: UUID): PlatformPaymentView {
        guard.require(actor)
        val p = payments.find(paymentId) ?: throw NotFoundException("결제")
        if (p.status != "PAID") throw ConflictException("NOT_PAID", "결제 완료 건만 환불할 수 있습니다")
        payments.update(paymentId, "REFUNDED", p.paidAt, null)
        return payments.find(paymentId) ?: throw NotFoundException("결제")
    }

    @Transactional
    override fun processCancellations(today: LocalDate): Int = subscriptions.cancelDue(today, clock.now())

    private fun notifyFailed(inst: InstitutionId, reason: String, now: Instant) {
        val managers = institutions.managers(inst.value)
        if (managers.isEmpty()) return
        outbox.publish(
            TeacherPushRequested(
                inst, "SUBSCRIPTION_PAYMENT_FAILED", "[똑똑] 구독 결제에 실패했어요", reason.take(80).ifEmpty { "카드 정보를 확인해 주세요" },
                mapOf("type" to "subscription"), managers, now,
            ),
        )
        realtime.userEvents(managers, mapOf("type" to "subscription.payment_failed"))
    }
}

// ───────── SAAS-003 ─────────
@Service
class VoucherService(
    private val guard: PlatformGuard,
    private val vouchers: VoucherPort,
    private val institutions: PlatformInstitutionPort,
    private val clock: ClockPort,
) : VoucherUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId): List<VoucherView> {
        guard.require(actor)
        return vouchers.list()
    }

    @Transactional
    override fun save(actor: UserId, institutionId: UUID, command: VoucherUseCase.Save): VoucherView {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        val code = command.voucherCode.trim()
        if (code.isEmpty() || code.length > 50) throw InvalidInputException("INVALID_CODE", "바우처 코드는 1~50자입니다")
        if (command.supportRatio !in 0..100) throw InvalidInputException("INVALID_RATIO", "지원 비율은 0~100% 입니다")
        if (command.status !in setOf("ACTIVE", "ENDED")) throw InvalidInputException("INVALID_STATUS", "상태는 ACTIVE·ENDED 입니다")
        if (command.startedOn != null && command.endedOn != null && command.endedOn.isBefore(command.startedOn)) throw InvalidInputException("INVALID_PERIOD", "종료일이 시작일보다 빠릅니다")
        val memo = command.memo?.trim()?.takeIf { it.isNotEmpty() }
        if (memo != null && memo.length > 300) throw InvalidInputException("MEMO_TOO_LONG", "메모는 300자까지입니다")
        vouchers.upsert(institutionId, command.copy(voucherCode = code, memo = memo), clock.now())
        return vouchers.find(institutionId) ?: throw NotFoundException("바우처")
    }

    @Transactional
    override fun remove(actor: UserId, institutionId: UUID) {
        guard.require(actor)
        vouchers.find(institutionId) ?: throw NotFoundException("바우처")
        vouchers.delete(institutionId)
    }

    @Transactional(readOnly = true)
    override fun settlements(actor: UserId, month: YearMonth): List<VoucherSettlementView> {
        guard.require(actor)
        return vouchers.settlements(month.toString())
    }

    @Transactional
    override fun generateSettlements(actor: UserId, month: YearMonth): Int {
        guard.require(actor)
        val now = clock.now()
        return vouchers.billing(month).count { b ->
            vouchers.insertSettlement(b.institutionId, month.toString(), b.billed, b.billed * b.supportRatio / 100, now)
        }
    }

    @Transactional
    override fun settle(actor: UserId, id: UUID, memo: String?): VoucherSettlementView {
        guard.require(actor)
        val s = vouchers.findSettlement(id) ?: throw NotFoundException("정산")
        if (s.status != "PENDING") throw ConflictException("ALREADY_SETTLED", "이미 정산 완료된 건입니다")
        val m = memo?.trim()?.takeIf { it.isNotEmpty() }
        if (m != null && m.length > 300) throw InvalidInputException("MEMO_TOO_LONG", "메모는 300자까지입니다")
        vouchers.settle(id, clock.today(), m)
        return vouchers.findSettlement(id) ?: throw NotFoundException("정산")
    }
}

// ───────── SAAS-004 ─────────
private fun checkText(label: String, v: String, max: Int): String {
    val t = v.trim()
    if (t.isEmpty()) throw InvalidInputException("EMPTY_TEXT", "${label}을(를) 입력해 주세요")
    if (t.length > max) throw InvalidInputException("TEXT_TOO_LONG", "${label}은(는) ${max}자까지입니다")
    return t
}

@Service
class InstitutionSupportService(
    private val tickets: SupportTicketPort,
    private val accessGuard: AccessGuard,
    private val clock: ClockPort,
) : InstitutionSupportUseCase {

    @Transactional
    override fun open(actor: UserId, institutionId: InstitutionId, subject: String, category: TicketCategory, body: String): TicketView {
        accessGuard.requireMemberAnyStatus(actor, institutionId, Role.OWNER, Role.ADMIN)
        val s = checkText("제목", subject, 100)
        val b = checkText("내용", body, 4000)
        val now = clock.now()
        val id = tickets.insert(institutionId.value, actor, s, category, now)
        tickets.addMessage(id, actor, false, b, now)
        return full(id)
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<TicketView> {
        accessGuard.requireMemberAnyStatus(actor, institutionId, Role.OWNER, Role.ADMIN)
        return tickets.list(institutionId.value, null)
    }

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, id: UUID): TicketView {
        accessGuard.requireMemberAnyStatus(actor, institutionId, Role.OWNER, Role.ADMIN)
        return owned(id, institutionId).let { full(id) }
    }

    @Transactional
    override fun reply(actor: UserId, institutionId: InstitutionId, id: UUID, body: String): TicketView {
        accessGuard.requireMemberAnyStatus(actor, institutionId, Role.OWNER, Role.ADMIN)
        val t = owned(id, institutionId)
        if (t.status == TicketStatus.CLOSED) throw ConflictException("TICKET_CLOSED", "종료된 문의입니다. 새 문의를 등록해 주세요")
        val now = clock.now()
        tickets.addMessage(id, actor, false, checkText("내용", body, 4000), now)
        if (t.status == TicketStatus.RESOLVED) tickets.update(id, TicketStatus.OPEN, null, now)
        return full(id)
    }

    private fun owned(id: UUID, institutionId: InstitutionId): TicketView =
        tickets.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("문의")

    private fun full(id: UUID): TicketView {
        val t = tickets.find(id) ?: throw NotFoundException("문의")
        return t.copy(messages = tickets.messages(id))
    }
}

@Service
class PlatformSupportService(
    private val guard: PlatformGuard,
    private val tickets: SupportTicketPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : PlatformSupportUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, status: TicketStatus?, institutionId: UUID?): List<TicketView> {
        guard.require(actor)
        return tickets.list(institutionId, status)
    }

    @Transactional(readOnly = true)
    override fun get(actor: UserId, id: UUID): TicketView {
        guard.require(actor)
        return full(id)
    }

    @Transactional
    override fun reply(actor: UserId, id: UUID, body: String): TicketView {
        guard.require(actor)
        val t = tickets.find(id) ?: throw NotFoundException("문의")
        if (t.status == TicketStatus.CLOSED) throw ConflictException("TICKET_CLOSED", "종료된 문의입니다")
        val now = clock.now()
        tickets.addMessage(id, actor, true, checkText("내용", body, 4000), now)
        if (t.status == TicketStatus.OPEN) tickets.update(id, TicketStatus.IN_PROGRESS, actor, now)
        tickets.creator(id)?.let { creator ->
            val to = listOf(UserId(creator))
            outbox.publish(
                TeacherPushRequested(
                    InstitutionId(t.institutionId), "SUPPORT_REPLY", "[운영팀] 문의에 답변이 달렸어요", t.subject.take(80),
                    mapOf("type" to "support", "ticketId" to id.toString()), to, now,
                ),
            )
            realtime.userEvents(to, mapOf("type" to "support.reply", "ticketId" to id.toString()))
        }
        return full(id)
    }

    @Transactional
    override fun update(actor: UserId, id: UUID, status: TicketStatus?, assignToMe: Boolean?): TicketView {
        guard.require(actor)
        tickets.find(id) ?: throw NotFoundException("문의")
        tickets.update(id, status, if (assignToMe == true) actor else null, clock.now())
        return full(id)
    }

    private fun full(id: UUID): TicketView {
        val t = tickets.find(id) ?: throw NotFoundException("문의")
        return t.copy(messages = tickets.messages(id))
    }
}

// ───────── SAAS-005 ─────────
@Service
class PlatformNoticeService(
    private val guard: PlatformGuard,
    private val notices: PlatformNoticePort,
    private val clock: ClockPort,
) : PlatformNoticeUseCase, ActivePlatformNoticeQuery {

    @Transactional(readOnly = true)
    override fun list(actor: UserId): List<PlatformNoticeView> {
        guard.require(actor)
        return notices.list()
    }

    @Transactional
    override fun create(actor: UserId, kind: PlatformNoticeKind, title: String, body: String, startsAt: Instant?, endsAt: Instant?): PlatformNoticeView {
        guard.require(actor)
        val now = clock.now()
        val start = startsAt ?: now
        if (endsAt != null && !endsAt.isAfter(start)) throw InvalidInputException("INVALID_PERIOD", "종료 시각이 시작 시각보다 늦어야 합니다")
        val id = notices.insert(kind, checkText("제목", title, 100), checkText("내용", body, 2000), start, endsAt, actor, now)
        return notices.find(id) ?: throw NotFoundException("공지")
    }

    @Transactional
    override fun cancel(actor: UserId, id: UUID): PlatformNoticeView {
        guard.require(actor)
        notices.find(id) ?: throw NotFoundException("공지")
        notices.cancel(id)
        return notices.find(id) ?: throw NotFoundException("공지")
    }

    @Transactional(readOnly = true)
    override fun active(): List<PlatformNoticeView> = notices.active(clock.now())
}

// ───────── SAAS-007 ─────────
@Service
class PlatformMetricsService(
    private val guard: PlatformGuard,
    private val metrics: PlatformMetricsPort,
    private val clock: ClockPort,
) : PlatformMetricsUseCase {

    @Transactional(readOnly = true)
    override fun metrics(actor: UserId, month: YearMonth): PlatformMetrics {
        guard.require(actor)
        val zone = clock.zone()
        fun stats(m: YearMonth) = metrics.monthStats(m.atDay(1).atStartOfDay(zone).toInstant(), m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
        val cur = stats(month)
        val trend = (5 downTo 0).map { back ->
            val m = month.minusMonths(back.toLong())
            val s = if (back == 0) cur else stats(m)
            MetricsMonth(m.toString(), s.newPaid, s.churned, s.activeAtEnd, s.mrrAtEnd)
        }
        val (_, trial) = metrics.currentCounts()
        val cost = metrics.marketingCost(month.toString())
        return PlatformMetrics(
            month.toString(), cur.mrrAtEnd, cur.mrrAtEnd * 12, cur.activeAtEnd, trial, cur.newPaid, cur.churned,
            if (cur.activeAtStart > 0) cur.churned * 100.0 / cur.activeAtStart else null,
            cost, if (cost != null && cur.newPaid > 0) cost / cur.newPaid else null, trend,
        )
    }

    @Transactional
    override fun setMarketingCost(actor: UserId, month: YearMonth, amount: Long) {
        guard.require(actor)
        if (amount < 0) throw InvalidInputException("INVALID_AMOUNT", "비용은 0 이상이어야 합니다")
        metrics.setMarketingCost(month.toString(), amount, actor, clock.now())
    }
}
