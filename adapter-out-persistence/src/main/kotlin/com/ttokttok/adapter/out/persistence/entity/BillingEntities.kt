package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** PAY-001 청구서 (V17, V23 할인·교습 기간) */
@Entity @Table(name = "invoice")
class InvoiceEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var title: String,
    var amount: Int,
    var dueDate: LocalDate,
    var memo: String?,
    var status: String = "UNPAID",
    var paidOn: LocalDate? = null,
    var payMethod: String? = null,
    var paidNote: String? = null,
    var markedBy: UUID? = null,
    var sentAt: Instant? = null,
    var createdBy: UUID,
    var createdAt: Instant,
    var updatedAt: Instant,
    var baseAmount: Int? = null,
    var discountAmount: Int = 0,
    var discountNote: String? = null,
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
)

/** PAY-003 자동 리마인드 중복 방지 (청구서 × 단계 1회) */
@Entity @Table(name = "invoice_reminder_log") @IdClass(InvoiceReminderLogKey::class)
class InvoiceReminderLogEntity(
    @Id var invoiceId: UUID,
    @Id var stage: String,
    var sentAt: Instant,
)

data class InvoiceReminderLogKey(var invoiceId: UUID? = null, var stage: String? = null) : Serializable

/** SET-003 요금제 */
@Entity @Table(name = "subscription_plan")
class SubscriptionPlanEntity(
    @Id var code: String,
    var name: String,
    var monthlyPrice: Int?,
    var studentLimit: Int?,
    var description: String?,
    var sortOrder: Int,
)

/** SET-003 기관 구독 (V17, V18 activated_at·canceled_at) */
@Entity @Table(name = "institution_subscription")
class InstitutionSubscriptionEntity(
    @Id var institutionId: UUID,
    var planCode: String,
    var status: String,
    var trialEndsOn: LocalDate?,
    var nextBillingDate: LocalDate?,
    var cancelAtPeriodEnd: Boolean = false,
    var cardBrand: String? = null,
    @JdbcTypeCode(SqlTypes.CHAR) var cardLast4: String? = null,
    var cardUpdatedAt: Instant? = null,
    var createdAt: Instant,
    var updatedAt: Instant,
    var activatedAt: Instant? = null,
    var canceledAt: Instant? = null,
)

/** SET-003 구독 결제 내역 (운영사/PG 가 기록) */
@Entity @Table(name = "subscription_payment")
class SubscriptionPaymentEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var planCode: String,
    var amount: Int,
    var status: String,
    var periodStart: LocalDate,
    var periodEnd: LocalDate,
    var paidAt: Instant?,
    var failureReason: String?,
    var pgTransactionId: String? = null,
    var createdAt: Instant,
)
