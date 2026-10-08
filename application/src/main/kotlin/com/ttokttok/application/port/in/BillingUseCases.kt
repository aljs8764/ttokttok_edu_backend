package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Phase 2 ④ 결제·구독 — PAY-001~003, SET-003, DASH-007

enum class InvoiceStatus { UNPAID, PAID, CANCELED }
enum class PayMethod(val label: String) { CASH("현금"), TRANSFER("계좌이체"), CARD("카드"), OTHER("기타") }

/** 목록 필터. OVERDUE 는 미납 중 기한이 지난 건 (DB 상태가 아니라 계산값) */
enum class InvoiceFilter { ALL, UNPAID, OVERDUE, PAID, CANCELED }

data class InvoiceView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val classroomName: String?,
    val title: String,
    val amount: Int,
    val dueDate: LocalDate,
    val memo: String?,
    val status: InvoiceStatus,
    val paidOn: LocalDate?,
    val payMethod: PayMethod?,
    val paidNote: String?,
    val sentAt: Instant?,
    val createdAt: Instant,
) {
    /** 미납이고 기한이 지났으면 지난 일수, 아니면 0 */
    fun overdueDays(today: LocalDate): Int =
        if (status == InvoiceStatus.UNPAID && dueDate.isBefore(today)) java.time.temporal.ChronoUnit.DAYS.between(dueDate, today).toInt() else 0
}

data class InvoiceSummary(
    val month: YearMonth,
    val count: Int,
    val totalAmount: Long,
    val paidAmount: Long,
    val unpaidCount: Int,
    val overdueCount: Int,
    val overdueAmount: Long,
) {
    /** 취소 건 제외 청구액 대비 수납액 (%), 청구가 없으면 null */
    val collectionRate: Double? get() = if (totalAmount == 0L) null else paidAmount * 100.0 / totalAmount
}

/** 관리자(원장·실장) 전용 */
interface InvoiceUseCase {
    data class Item(val studentId: UUID, val amount: Int)
    data class Issue(val title: String, val dueDate: LocalDate, val memo: String?, val items: List<Item>, val notify: Boolean)

    fun issue(actor: UserId, institutionId: InstitutionId, command: Issue): List<InvoiceView>
    fun list(actor: UserId, institutionId: InstitutionId, month: YearMonth, filter: InvoiceFilter, classroomId: UUID?, studentId: UUID?): List<InvoiceView>
    fun summary(actor: UserId, institutionId: InstitutionId, month: YearMonth): InvoiceSummary
    fun markPaid(actor: UserId, institutionId: InstitutionId, id: UUID, method: PayMethod, paidOn: LocalDate?, note: String?): InvoiceView
    fun markUnpaid(actor: UserId, institutionId: InstitutionId, id: UUID): InvoiceView
    fun cancel(actor: UserId, institutionId: InstitutionId, id: UUID): InvoiceView
    /** 원장이 수동으로 다시 알림 */
    fun remind(actor: UserId, institutionId: InstitutionId, id: UUID)
}

/** 학부모 앱 (PAR-007 의 API) — 본인 자녀 청구서만 */
interface ParentInvoiceUseCase {
    fun mine(parent: UserId, studentIds: Set<UUID>?, limit: Int): List<InvoiceView>
}

/** PAY-003 스케줄러가 부른다 */
interface InvoiceReminderUseCase {
    /** 이번 단계 알림이 아직 안 나간 미납 청구서를 최대 limit 건 알린다. 알린 건수 반환 */
    fun remindDue(limit: Int): Int
}

// ───────── SET-003 구독 ─────────
data class PlanView(val code: String, val name: String, val monthlyPrice: Int?, val studentLimit: Int?, val description: String?)

enum class SubscriptionStatus { TRIAL, ACTIVE, PAST_DUE, CANCELED }

data class SubscriptionView(
    val plan: PlanView,
    val status: SubscriptionStatus,
    val trialEndsOn: LocalDate?,
    val nextBillingDate: LocalDate?,
    val cancelAtPeriodEnd: Boolean,
    val cardBrand: String?,
    val cardLast4: String?,
    val studentCount: Int,
) {
    val usagePercent: Double? get() = plan.studentLimit?.let { studentCount * 100.0 / it }
}

data class SubscriptionPaymentView(
    val id: UUID,
    val planCode: String,
    val amount: Int,
    val status: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val paidAt: Instant?,
    val failureReason: String?,
)

interface SubscriptionUseCase {
    fun get(actor: UserId, institutionId: InstitutionId): SubscriptionView
    fun plans(actor: UserId, institutionId: InstitutionId): List<PlanView>
    /** 원장만. 현재 원생 수가 새 플랜 한도를 넘으면 거절 */
    fun changePlan(actor: UserId, institutionId: InstitutionId, planCode: String): SubscriptionView
    fun setCard(actor: UserId, institutionId: InstitutionId, brand: String, last4: String): SubscriptionView
    fun setCancelAtPeriodEnd(actor: UserId, institutionId: InstitutionId, cancel: Boolean): SubscriptionView
    fun payments(actor: UserId, institutionId: InstitutionId): List<SubscriptionPaymentView>
}

// ───────── DASH-007 ─────────
data class BillingWidget(
    val planName: String,
    val status: SubscriptionStatus,
    val nextBillingDate: LocalDate?,
    val trialDaysLeft: Int?,
    val studentCount: Int,
    val studentLimit: Int?,
    val usagePercent: Double?,
    val invoiceSummary: InvoiceSummary,
)

interface BillingWidgetQuery {
    fun widget(actor: UserId, institutionId: InstitutionId): BillingWidget
}
