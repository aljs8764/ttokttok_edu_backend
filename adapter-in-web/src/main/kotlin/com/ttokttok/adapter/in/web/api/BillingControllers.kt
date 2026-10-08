package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.BillingWidgetQuery
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.InvoiceFilter
import com.ttokttok.application.port.`in`.InvoiceSummary
import com.ttokttok.application.port.`in`.InvoiceUseCase
import com.ttokttok.application.port.`in`.InvoiceView
import com.ttokttok.application.port.`in`.ParentInvoiceUseCase
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.PlanView
import com.ttokttok.application.port.`in`.SubscriptionPaymentView
import com.ttokttok.application.port.`in`.SubscriptionUseCase
import com.ttokttok.application.port.`in`.SubscriptionView
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.domain.common.InvalidInputException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

// ───────── 응답 변환 ─────────
private fun InvoiceView.toResponse(today: LocalDate) = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName,
    "student" to mapOf("id" to studentId, "name" to studentName), "classroomName" to classroomName,
    "title" to title, "amount" to amount, "dueDate" to dueDate, "memo" to memo, "status" to status.name,
    "overdueDays" to overdueDays(today), "paidOn" to paidOn, "payMethod" to payMethod?.name, "paidNote" to paidNote,
    "sentAt" to sentAt, "createdAt" to createdAt,
)

private fun InvoiceSummary.toResponse() = mapOf(
    "month" to month.toString(), "count" to count, "totalAmount" to totalAmount, "paidAmount" to paidAmount,
    "unpaidCount" to unpaidCount, "overdueCount" to overdueCount, "overdueAmount" to overdueAmount, "collectionRate" to collectionRate,
)

private fun PlanView.toResponse() = mapOf("code" to code, "name" to name, "monthlyPrice" to monthlyPrice, "studentLimit" to studentLimit, "description" to description)

private fun SubscriptionView.toResponse() = mapOf(
    "plan" to plan.toResponse(), "status" to status.name, "trialEndsOn" to trialEndsOn, "nextBillingDate" to nextBillingDate,
    "cancelAtPeriodEnd" to cancelAtPeriodEnd, "card" to cardLast4?.let { mapOf("brand" to cardBrand, "last4" to it) },
    "studentCount" to studentCount, "studentLimit" to plan.studentLimit, "usagePercent" to usagePercent,
)

private fun SubscriptionPaymentView.toResponse() = mapOf(
    "id" to id, "planCode" to planCode, "amount" to amount, "status" to status, "periodStart" to periodStart, "periodEnd" to periodEnd,
    "paidAt" to paidAt, "failureReason" to failureReason,
)

private fun parseMonth(month: String?, clock: ClockPort): YearMonth =
    if (month == null) YearMonth.from(clock.today())
    else try { YearMonth.parse(month) } catch (e: DateTimeParseException) { throw InvalidInputException("INVALID_MONTH", "month 는 YYYY-MM 형식입니다") }

// ───────── PAY-001·002 청구·수납 (원장·실장) ─────────
@RestController
class InvoiceController(private val invoices: InvoiceUseCase, private val clock: ClockPort) {
    data class ItemRequest(@field:NotNull val studentId: UUID, @field:NotNull val amount: Int)
    data class IssueRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotNull val dueDate: LocalDate,
        @field:Size(max = 300) val memo: String? = null,
        @field:Size(min = 1, max = 500) val items: List<ItemRequest>,
        val notify: Boolean = true,
    )
    data class PaidRequest(@field:NotNull val method: PayMethod, val paidOn: LocalDate? = null, @field:Size(max = 200) val note: String? = null)

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: IssueRequest) =
        invoices.issue(
            jwt.userId(), inst(institutionId),
            InvoiceUseCase.Issue(req.title, req.dueDate, req.memo, req.items.map { InvoiceUseCase.Item(it.studentId, it.amount) }, req.notify),
        ).map { it.toResponse(clock.today()) }

    @GetMapping("/api/v1/invoices")
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) month: String?, @RequestParam(defaultValue = "ALL") status: InvoiceFilter,
        @RequestParam(required = false) classroomId: UUID?, @RequestParam(required = false) studentId: UUID?,
    ) = invoices.list(jwt.userId(), inst(institutionId), parseMonth(month, clock), status, classroomId, studentId).map { it.toResponse(clock.today()) }

    @GetMapping("/api/v1/invoices/summary")
    fun summary(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) month: String?) =
        invoices.summary(jwt.userId(), inst(institutionId), parseMonth(month, clock)).toResponse()

    @PostMapping("/api/v1/invoices/{id}/paid")
    fun paid(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: PaidRequest) =
        invoices.markPaid(jwt.userId(), inst(institutionId), id, req.method, req.paidOn, req.note).toResponse(clock.today())

    @PostMapping("/api/v1/invoices/{id}/unpaid")
    fun unpaid(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        invoices.markUnpaid(jwt.userId(), inst(institutionId), id).toResponse(clock.today())

    @PostMapping("/api/v1/invoices/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        invoices.cancel(jwt.userId(), inst(institutionId), id).toResponse(clock.today())

    @PostMapping("/api/v1/invoices/{id}/remind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remind(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        invoices.remind(jwt.userId(), inst(institutionId), id)
}

// ───────── 학부모 앱 ─────────
@RestController
class MeInvoiceController(private val invoices: ParentInvoiceUseCase, private val children: GetMyChildrenQuery, private val clock: ClockPort) {
    @GetMapping("/api/v1/me/invoices")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?, @RequestParam(defaultValue = "50") limit: Int) =
        invoices.mine(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet(), limit).map { it.toResponse(clock.today()) }
}

// ───────── SET-003 구독 ─────────
@RestController
class SubscriptionController(private val subscription: SubscriptionUseCase) {
    data class PlanRequest(@field:NotBlank val planCode: String)
    data class CardRequest(@field:NotBlank @field:Size(max = 20) val brand: String, @field:NotBlank val last4: String)
    data class CancelRequest(val cancel: Boolean)

    @GetMapping("/api/v1/subscription")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        subscription.get(jwt.userId(), inst(institutionId)).toResponse()

    @GetMapping("/api/v1/subscription/plans")
    fun plans(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        subscription.plans(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @PutMapping("/api/v1/subscription/plan")
    fun changePlan(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: PlanRequest) =
        subscription.changePlan(jwt.userId(), inst(institutionId), req.planCode).toResponse()

    @PutMapping("/api/v1/subscription/card")
    fun setCard(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CardRequest) =
        subscription.setCard(jwt.userId(), inst(institutionId), req.brand, req.last4).toResponse()

    @PutMapping("/api/v1/subscription/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestBody req: CancelRequest) =
        subscription.setCancelAtPeriodEnd(jwt.userId(), inst(institutionId), req.cancel).toResponse()

    @GetMapping("/api/v1/subscription/payments")
    fun payments(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        subscription.payments(jwt.userId(), inst(institutionId)).map { it.toResponse() }
}

// ───────── DASH-007 ─────────
@RestController
class BillingWidgetController(private val widget: BillingWidgetQuery) {
    @GetMapping("/api/v1/dashboard/billing")
    fun billing(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID): Map<String, Any?> {
        val w = widget.widget(jwt.userId(), inst(institutionId))
        return mapOf(
            "planName" to w.planName, "status" to w.status.name, "nextBillingDate" to w.nextBillingDate, "trialDaysLeft" to w.trialDaysLeft,
            "studentCount" to w.studentCount, "studentLimit" to w.studentLimit, "usagePercent" to w.usagePercent,
            "invoices" to w.invoiceSummary.toResponse(),
        )
    }
}
