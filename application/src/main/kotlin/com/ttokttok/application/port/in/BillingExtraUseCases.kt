package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.WithdrawalReason
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Phase 3 P3-② 수납 고도화·데이터 내보내기 — PAY-006, PAY-007, SET-006

// ───────── PAY-006 원비 할인 규칙 ─────────
enum class DiscountKind(val label: String) { SIBLING("형제"), LONG_TERM("장기 등록"), MULTI_CLASS("다과목") }
enum class DiscountValueType { PERCENT, AMOUNT }

data class DiscountRuleView(
    val id: UUID, val name: String, val kind: DiscountKind, val threshold: Int,
    val valueType: DiscountValueType, val value: Int, val active: Boolean,
)

data class AppliedDiscount(val ruleId: UUID, val name: String, val amount: Int)
data class DiscountPreviewItem(
    val studentId: UUID, val studentName: String, val baseAmount: Int, val discountAmount: Int, val finalAmount: Int,
    val applied: List<AppliedDiscount>,
    /** 판단 근거: 형제 중 몇째, 등록 개월 수, 현재 반 수 */
    val siblingRank: Int, val monthsEnrolled: Int, val classCount: Int,
)

interface DiscountUseCase {
    data class SaveRule(val name: String, val kind: DiscountKind, val threshold: Int, val valueType: DiscountValueType, val value: Int, val active: Boolean)

    /** 원장·실장 */
    fun rules(actor: UserId, institutionId: InstitutionId): List<DiscountRuleView>
    fun addRule(actor: UserId, institutionId: InstitutionId, command: SaveRule): DiscountRuleView
    fun updateRule(actor: UserId, institutionId: InstitutionId, ruleId: UUID, command: SaveRule): DiscountRuleView
    fun deleteRule(actor: UserId, institutionId: InstitutionId, ruleId: UUID)

    /** 청구 전 미리보기: 대상 원생별 할인 적용 결과 */
    fun preview(actor: UserId, institutionId: InstitutionId, items: List<InvoiceUseCase.Item>): List<DiscountPreviewItem>

    /** 할인을 반영해 청구서 발행 (PAY-001 발행을 그대로 쓰고 할인 내역·교습 기간을 덧붙인다) */
    fun issueWithDiscounts(
        actor: UserId, institutionId: InstitutionId, command: InvoiceUseCase.Issue,
        applyDiscounts: Boolean, periodStart: LocalDate?, periodEnd: LocalDate?,
    ): List<InvoiceView>
}

// ───────── PAY-007 중도 퇴원 일할 환불 ─────────
enum class RefundMethod(val label: String) {
    /** 학원법 시행령 별표 기준: 1/3 경과 전 2/3, 1/2 경과 전 1/2, 이후 0 (1개월 넘는 기간은 남은 달 전액) */
    LAW("학원법 기준"),
    /** 남은 일수 비율 */
    DAILY("일할 계산"),
}

data class RefundCalculation(
    val invoiceId: UUID,
    val studentName: String,
    val method: RefundMethod,
    val paidAmount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val withdrawDate: LocalDate,
    val totalDays: Int,
    val usedDays: Int,
    val refundAmount: Int,
    /** 계산 근거 문장들 (화면·영수증 메모용) */
    val lines: List<String>,
)

data class RefundView(
    val id: UUID, val invoiceId: UUID, val invoiceTitle: String, val studentId: UUID, val studentName: String,
    val method: RefundMethod, val withdrawDate: LocalDate, val periodStart: LocalDate, val periodEnd: LocalDate,
    val paidAmount: Int, val calculatedAmount: Int, val refundAmount: Int, val refundedOn: LocalDate,
    val payMethod: PayMethod, val note: String?, val createdByName: String, val createdAt: Instant,
)

interface RefundUseCase {
    data class Calc(val withdrawDate: LocalDate, val method: RefundMethod, val periodStart: LocalDate?, val periodEnd: LocalDate?)
    data class Record(val calc: Calc, val refundAmount: Int?, val refundedOn: LocalDate?, val payMethod: PayMethod, val note: String?, val withdrawReason: WithdrawalReason?)

    /** 원장·실장. 수납 완료 청구서만 */
    fun calculate(actor: UserId, institutionId: InstitutionId, invoiceId: UUID, calc: Calc): RefundCalculation
    /** 처리 기록 (금액을 바꿔 기록할 수 있다). withdrawReason 이 있으면 퇴원일로 원생 퇴원 처리 */
    fun record(actor: UserId, institutionId: InstitutionId, invoiceId: UUID, command: Record): RefundView
    fun list(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<RefundView>
}

// ───────── SET-006 데이터 내보내기 ─────────
data class ExportFile(val filename: String, val bytes: ByteArray)

interface DataExportUseCase {
    /** 원장만. 기관 데이터 전체를 시트별 엑셀로 묶은 ZIP. 출결·알림장은 기간(최대 1년) */
    fun export(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): ExportFile
}
