package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** PAY-006 원비 할인 규칙 (V23) */
@Entity @Table(name = "discount_rule")
class DiscountRuleEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var kind: String,
    var threshold: Int,
    var valueType: String,
    var value: Int,
    var active: Boolean = true,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** PAY-007 중도 퇴원 환불 기록 (V23) */
@Entity @Table(name = "refund_record")
class RefundRecordEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var invoiceId: UUID,
    var studentId: UUID,
    var method: String,
    var withdrawDate: LocalDate,
    var periodStart: LocalDate,
    var periodEnd: LocalDate,
    var paidAmount: Int,
    var calculatedAmount: Int,
    var refundAmount: Int,
    var refundedOn: LocalDate,
    var payMethod: String,
    var note: String?,
    var createdBy: UUID,
    var createdAt: Instant,
)
