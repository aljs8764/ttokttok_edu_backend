package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.DiscountRuleView
import com.ttokttok.application.port.`in`.DiscountUseCase
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.RefundMethod
import com.ttokttok.application.port.`in`.RefundView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 할인 판단에 쓰는 원생별 사실 */
data class DiscountFacts(
    val studentId: UUID,
    val studentName: String,
    /** 같은 보호자 번호를 가진 재원 형제 중 몇째인지 (먼저 등록한 순, 1 = 첫째) */
    val siblingRank: Int,
    /** 첫 반 배정일부터 기준일까지 개월 수 */
    val monthsEnrolled: Int,
    /** 지금 다니는 반 수 */
    val classCount: Int,
)

interface DiscountPort {
    fun rules(institutionId: InstitutionId, activeOnly: Boolean): List<DiscountRuleView>
    fun findRule(id: UUID): Pair<UUID, DiscountRuleView>?   // (institutionId, view)
    fun insertRule(institutionId: InstitutionId, command: DiscountUseCase.SaveRule, now: Instant): UUID
    fun updateRule(id: UUID, command: DiscountUseCase.SaveRule, now: Instant)
    fun deleteRule(id: UUID)
    fun facts(institutionId: InstitutionId, studentIds: Collection<UUID>, asOf: LocalDate): List<DiscountFacts>
    fun saveInvoiceExtras(invoiceId: UUID, baseAmount: Int, discountAmount: Int, discountNote: String?, periodStart: LocalDate?, periodEnd: LocalDate?)
}

data class RefundInvoice(
    val invoiceId: UUID, val institutionId: UUID, val studentId: UUID, val studentName: String, val title: String,
    val amount: Int, val status: String, val dueDate: LocalDate, val periodStart: LocalDate?, val periodEnd: LocalDate?, val refunded: Boolean,
    val studentWithdrawn: Boolean,
)

interface RefundPort {
    fun invoice(id: UUID): RefundInvoice?
    fun insert(
        institutionId: InstitutionId, invoice: RefundInvoice, method: RefundMethod, withdrawDate: LocalDate, periodStart: LocalDate, periodEnd: LocalDate,
        calculated: Int, refund: Int, refundedOn: LocalDate, payMethod: PayMethod, note: String?, by: UserId, now: Instant,
    ): UUID
    fun find(id: UUID): RefundView?
    fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<RefundView>
}

/** 시트 하나 (헤더 + 행) */
data class ExportSheet(val name: String, val headers: List<String>, val rows: List<List<String>>)

interface DataExportPort {
    fun institutionName(institutionId: InstitutionId): String
    /** 원생·보호자·반·반 배정·출결·알림장·청구·환불·상담 시트 */
    fun sheets(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<ExportSheet>
}
