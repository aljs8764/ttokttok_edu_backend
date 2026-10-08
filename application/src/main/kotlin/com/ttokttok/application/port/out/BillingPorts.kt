package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.InvoiceFilter
import com.ttokttok.application.port.`in`.InvoiceStatus
import com.ttokttok.application.port.`in`.InvoiceSummary
import com.ttokttok.application.port.`in`.InvoiceView
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.PlanView
import com.ttokttok.application.port.`in`.SubscriptionPaymentView
import com.ttokttok.application.port.`in`.SubscriptionStatus
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** 리마인드 대상 한 건 */
data class DueReminder(
    val invoiceId: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val title: String,
    val amount: Int,
    val dueDate: LocalDate,
    val stage: String,
)

interface InvoicePort {
    /** 이 기관의 재원(탈퇴 아님) 원생만 돌려준다 */
    fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID>
    fun insert(institutionId: InstitutionId, studentId: UUID, title: String, amount: Int, dueDate: LocalDate, memo: String?, creator: UserId, sentAt: Instant?, now: Instant): UUID
    fun find(id: UUID): InvoiceView?
    fun findMany(ids: Collection<UUID>): List<InvoiceView>
    fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate, filter: InvoiceFilter, today: LocalDate, classroomId: UUID?, studentId: UUID?): List<InvoiceView>
    fun summary(institutionId: InstitutionId, month: YearMonth, today: LocalDate): InvoiceSummary
    fun markPaid(id: UUID, method: PayMethod, paidOn: LocalDate, note: String?, by: UserId, now: Instant)
    fun markUnpaid(id: UUID, by: UserId, now: Instant)
    fun cancel(id: UUID, by: UserId, now: Instant)
    fun touchSent(id: UUID, now: Instant)
    /** 학부모 계정이 연결된 보호자 user id (원생 1명 기준) */
    fun guardianUsers(studentId: UUID): List<UserId>
    fun listForParent(parent: UserId, studentIds: Set<UUID>?, limit: Int): List<InvoiceView>
    /** 오늘 보낼 단계가 있고 아직 안 보낸 미납 건 */
    fun dueReminders(today: LocalDate, limit: Int): List<DueReminder>
    /** 단계 기록. 이미 있으면 false */
    fun markReminded(invoiceId: UUID, stage: String, now: Instant): Boolean
}

data class SubscriptionRow(
    val planCode: String,
    val status: SubscriptionStatus,
    val trialEndsOn: LocalDate?,
    val nextBillingDate: LocalDate?,
    val cancelAtPeriodEnd: Boolean,
    val cardBrand: String?,
    val cardLast4: String?,
)

interface SubscriptionPort {
    fun plans(): List<PlanView>
    fun plan(code: String): PlanView?
    fun find(institutionId: InstitutionId): SubscriptionRow?
    /** 없으면 만든다 (14일 체험) */
    fun ensure(institutionId: InstitutionId, trialEndsOn: LocalDate, now: Instant): SubscriptionRow
    fun changePlan(institutionId: InstitutionId, planCode: String, status: SubscriptionStatus, nextBillingDate: LocalDate?, now: Instant)
    fun setCard(institutionId: InstitutionId, brand: String, last4: String, now: Instant)
    fun setCancelAtPeriodEnd(institutionId: InstitutionId, cancel: Boolean, now: Instant)
    fun payments(institutionId: InstitutionId, limit: Int): List<SubscriptionPaymentView>
    /** 탈퇴가 아닌 원생 수 */
    fun studentCount(institutionId: InstitutionId): Int
}
