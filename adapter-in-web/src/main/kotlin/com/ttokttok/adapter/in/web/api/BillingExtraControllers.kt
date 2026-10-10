package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.DataExportUseCase
import com.ttokttok.application.port.`in`.DiscountKind
import com.ttokttok.application.port.`in`.DiscountUseCase
import com.ttokttok.application.port.`in`.DiscountValueType
import com.ttokttok.application.port.`in`.InvoiceUseCase
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.RefundMethod
import com.ttokttok.application.port.`in`.RefundUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.student.WithdrawalReason
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

private fun billingMonth(month: String?, clock: ClockPort): YearMonth =
    if (month == null) YearMonth.from(clock.today())
    else try { YearMonth.parse(month) } catch (e: DateTimeParseException) { throw InvalidInputException("INVALID_MONTH", "month 는 YYYY-MM 형식입니다") }

// ───────── PAY-006 할인 규칙 ─────────
@RestController
class DiscountController(private val discounts: DiscountUseCase) {
    data class RuleRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:NotNull val kind: DiscountKind,
        @field:NotNull val threshold: Int,
        @field:NotNull val valueType: DiscountValueType,
        @field:NotNull val value: Int,
        val active: Boolean = true,
    ) {
        fun toCommand() = DiscountUseCase.SaveRule(name, kind, threshold, valueType, value, active)
    }
    data class ItemRequest(@field:NotNull val studentId: UUID, @field:NotNull val amount: Int)
    data class PreviewRequest(@field:Size(min = 1, max = 500) val items: List<ItemRequest>)
    data class IssueRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotNull val dueDate: LocalDate,
        @field:Size(max = 300) val memo: String? = null,
        @field:Size(min = 1, max = 500) val items: List<ItemRequest>,
        val notify: Boolean = true,
        val applyDiscounts: Boolean = true,
        val periodStart: LocalDate? = null,
        val periodEnd: LocalDate? = null,
    )

    @GetMapping("/api/v1/discount-rules")
    fun rules(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        discounts.rules(jwt.userId(), inst(institutionId))

    @PostMapping("/api/v1/discount-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: RuleRequest) =
        discounts.addRule(jwt.userId(), inst(institutionId), req.toCommand())

    @PutMapping("/api/v1/discount-rules/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: RuleRequest) =
        discounts.updateRule(jwt.userId(), inst(institutionId), id, req.toCommand())

    @DeleteMapping("/api/v1/discount-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        discounts.deleteRule(jwt.userId(), inst(institutionId), id)

    @PostMapping("/api/v1/invoices/discount-preview")
    fun preview(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: PreviewRequest) =
        discounts.preview(jwt.userId(), inst(institutionId), req.items.map { InvoiceUseCase.Item(it.studentId, it.amount) })

    /** 할인·교습 기간을 함께 기록하는 발행. 응답은 발행된 청구서 id·금액 */
    @PostMapping("/api/v1/invoices/with-discounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: IssueRequest) =
        discounts.issueWithDiscounts(
            jwt.userId(), inst(institutionId),
            InvoiceUseCase.Issue(req.title, req.dueDate, req.memo, req.items.map { InvoiceUseCase.Item(it.studentId, it.amount) }, req.notify),
            req.applyDiscounts, req.periodStart, req.periodEnd,
        ).map { mapOf("id" to it.id, "studentId" to it.studentId, "studentName" to it.studentName, "amount" to it.amount) }
}

// ───────── PAY-007 중도 퇴원 환불 ─────────
@RestController
class RefundController(private val refunds: RefundUseCase, private val clock: ClockPort) {
    data class CalcRequest(
        @field:NotNull val withdrawDate: LocalDate,
        val method: RefundMethod = RefundMethod.LAW,
        val periodStart: LocalDate? = null,
        val periodEnd: LocalDate? = null,
    ) {
        fun toCalc() = RefundUseCase.Calc(withdrawDate, method, periodStart, periodEnd)
    }
    data class RecordRequest(
        @field:NotNull val withdrawDate: LocalDate,
        val method: RefundMethod = RefundMethod.LAW,
        val periodStart: LocalDate? = null,
        val periodEnd: LocalDate? = null,
        val refundAmount: Int? = null,
        val refundedOn: LocalDate? = null,
        @field:NotNull val payMethod: PayMethod,
        @field:Size(max = 300) val note: String? = null,
        val withdrawReason: WithdrawalReason? = null,
    )

    @PostMapping("/api/v1/invoices/{id}/refund-calc")
    fun calc(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: CalcRequest) =
        refunds.calculate(jwt.userId(), inst(institutionId), id, req.toCalc())

    @PostMapping("/api/v1/invoices/{id}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: RecordRequest) =
        refunds.record(
            jwt.userId(), inst(institutionId), id,
            RefundUseCase.Record(RefundUseCase.Calc(req.withdrawDate, req.method, req.periodStart, req.periodEnd), req.refundAmount, req.refundedOn, req.payMethod, req.note, req.withdrawReason),
        )

    @GetMapping("/api/v1/refunds")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) month: String?) =
        refunds.list(jwt.userId(), inst(institutionId), billingMonth(month, clock))
}

// ───────── SET-006 데이터 내보내기 ─────────
@RestController
class DataExportController(private val exporter: DataExportUseCase) {
    @GetMapping("/api/v1/institution/export.zip")
    fun export(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        val file = exporter.export(jwt.userId(), inst(institutionId), from, to)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename, StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(file.bytes)
    }
}
