package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ActivePlatformNoticeQuery
import com.ttokttok.application.port.`in`.InstitutionSupportUseCase
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
import com.ttokttok.domain.common.InvalidInputException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

// ───────── 응답 변환 ─────────
private fun PlatformPaymentView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "planCode" to planCode, "amount" to amount,
    "status" to status, "periodStart" to periodStart, "periodEnd" to periodEnd, "paidAt" to paidAt, "failureReason" to failureReason, "createdAt" to createdAt,
)

private fun PlatformInstitutionRow.toResponse() = mapOf(
    "id" to id, "name" to name, "ownerName" to ownerName, "type" to type, "serviceStatus" to serviceStatus.name, "statusReason" to statusReason,
    "planCode" to planCode, "subscriptionStatus" to subscriptionStatus, "studentCount" to studentCount, "voucherCode" to voucherCode, "createdAt" to createdAt,
)

private fun PlatformInstitutionDetail.toResponse() = row.toResponse() + mapOf(
    "nextBillingDate" to nextBillingDate, "trialEndsOn" to trialEndsOn, "cancelAtPeriodEnd" to cancelAtPeriodEnd,
    "card" to cardLast4?.let { mapOf("brand" to cardBrand, "last4" to it) }, "staffCount" to staffCount,
    "recentPayments" to recentPayments.map { it.toResponse() },
)

private fun VoucherView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "voucherCode" to voucherCode, "supportRatio" to supportRatio,
    "status" to status, "startedOn" to startedOn, "endedOn" to endedOn, "memo" to memo,
)

private fun VoucherSettlementView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "periodMonth" to periodMonth, "billedAmount" to billedAmount,
    "supportAmount" to supportAmount, "status" to status, "settledOn" to settledOn, "memo" to memo,
)

private fun TicketView.toResponse(): Map<String, Any?> = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "createdByName" to createdByName, "subject" to subject,
    "category" to category.name, "categoryLabel" to category.label, "status" to status.name, "assigneeName" to assigneeName,
    "lastMessageAt" to lastMessageAt, "createdAt" to createdAt,
    "messages" to messages?.map { mapOf("id" to it.id, "authorName" to it.authorName, "fromOperator" to it.fromOperator, "body" to it.body, "createdAt" to it.createdAt) },
)

private fun PlatformNoticeView.toResponse() = mapOf(
    "id" to id, "kind" to kind.name, "title" to title, "body" to body, "startsAt" to startsAt, "endsAt" to endsAt, "cancelled" to cancelled, "createdAt" to createdAt,
)

private fun PlatformMetrics.toResponse() = mapOf(
    "month" to month, "mrr" to mrr, "arr" to arr, "payingInstitutions" to payingInstitutions, "trialInstitutions" to trialInstitutions,
    "newPaid" to newPaid, "churned" to churned, "churnRate" to churnRate, "marketingCost" to marketingCost, "cac" to cac,
    "trend" to trend.map { mapOf("month" to it.month, "newPaid" to it.newPaid, "churned" to it.churned, "activeAtEnd" to it.activeAtEnd, "mrr" to it.mrr) },
)

private fun parseYm(month: String?, clock: ClockPort): YearMonth =
    if (month == null) YearMonth.from(clock.today())
    else try { YearMonth.parse(month) } catch (e: DateTimeParseException) { throw InvalidInputException("INVALID_MONTH", "month 는 YYYY-MM 형식입니다") }

private fun <T : Enum<T>> parseEnum(value: String?, parse: (String) -> T, code: String): T? =
    value?.takeIf { it.isNotBlank() }?.let { try { parse(it) } catch (e: IllegalArgumentException) { throw InvalidInputException(code, "허용되지 않는 값입니다: $it") } }

// ───────── 운영사 여부 (웹이 콘솔 메뉴를 보일지 판단) ─────────
@RestController
class PlatformMeController(private val access: PlatformAccessQuery) {
    @GetMapping("/api/v1/platform/me")
    fun me(@AuthenticationPrincipal jwt: Jwt) = mapOf("platformAdmin" to access.isPlatformAdmin(jwt.userId()))
}

// ───────── SAAS-001 기관 ─────────
@RestController
class PlatformInstitutionController(private val institutions: PlatformInstitutionUseCase) {
    data class SuspendRequest(@field:NotBlank @field:Size(max = 200) val reason: String)
    data class SubscriptionOverrideRequest(@field:NotBlank val planCode: String, @field:NotBlank val status: String, val nextBillingDate: LocalDate?)

    @GetMapping("/api/v1/platform/institutions")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: String?, @RequestParam(required = false) q: String?) =
        institutions.list(jwt.userId(), parseEnum(status, ServiceStatus::valueOf, "INVALID_STATUS"), q).map { it.toResponse() }

    @GetMapping("/api/v1/platform/institutions/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = institutions.detail(jwt.userId(), id).toResponse()

    @PostMapping("/api/v1/platform/institutions/{id}/approve")
    fun approve(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = institutions.approve(jwt.userId(), id).toResponse()

    @PostMapping("/api/v1/platform/institutions/{id}/suspend")
    fun suspend(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: SuspendRequest) =
        institutions.suspend(jwt.userId(), id, req.reason).toResponse()

    @PutMapping("/api/v1/platform/institutions/{id}/subscription")
    fun override(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: SubscriptionOverrideRequest) =
        institutions.overrideSubscription(
            jwt.userId(), id, req.planCode,
            parseEnum(req.status, SubscriptionStatus::valueOf, "INVALID_STATUS")!!, req.nextBillingDate,
        ).toResponse()
}

// ───────── SAAS-002 구독 결제 ─────────
@RestController
class PlatformBillingController(private val billing: PlatformBillingUseCase) {
    data class RecordRequest(
        @field:NotBlank val planCode: String,
        @field:NotNull val amount: Int,
        @field:NotNull val paid: Boolean,
        @field:NotNull val periodStart: LocalDate,
        @field:NotNull val periodEnd: LocalDate,
        @field:Size(max = 200) val failureReason: String?,
    )

    @GetMapping("/api/v1/platform/payments")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: String?, @RequestParam(required = false) month: String?): List<Map<String, Any?>> {
        val ym = month?.let { try { YearMonth.parse(it) } catch (e: DateTimeParseException) { throw InvalidInputException("INVALID_MONTH", "month 는 YYYY-MM 형식입니다") } }
        return billing.payments(jwt.userId(), status?.takeIf { it.isNotBlank() }, ym).map { it.toResponse() }
    }

    @PostMapping("/api/v1/platform/institutions/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: RecordRequest) =
        billing.record(jwt.userId(), id, PlatformBillingUseCase.Record(req.planCode, req.amount, req.paid, req.periodStart, req.periodEnd, req.failureReason)).toResponse()

    @PostMapping("/api/v1/platform/payments/{id}/retry")
    fun retry(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = billing.retry(jwt.userId(), id).toResponse()

    @PostMapping("/api/v1/platform/payments/{id}/refund")
    fun refund(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = billing.refund(jwt.userId(), id).toResponse()
}

// ───────── SAAS-003 바우처 ─────────
@RestController
class PlatformVoucherController(private val vouchers: VoucherUseCase, private val clock: ClockPort) {
    data class SaveRequest(
        @field:NotBlank @field:Size(max = 50) val voucherCode: String,
        @field:NotNull val supportRatio: Int,
        val status: String?,
        val startedOn: LocalDate?,
        val endedOn: LocalDate?,
        @field:Size(max = 300) val memo: String?,
    )
    data class SettleRequest(@field:Size(max = 300) val memo: String?)

    @GetMapping("/api/v1/platform/vouchers")
    fun list(@AuthenticationPrincipal jwt: Jwt) = vouchers.list(jwt.userId()).map { it.toResponse() }

    @PutMapping("/api/v1/platform/institutions/{id}/voucher")
    fun save(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: SaveRequest) =
        vouchers.save(jwt.userId(), id, VoucherUseCase.Save(req.voucherCode, req.supportRatio, req.status ?: "ACTIVE", req.startedOn, req.endedOn, req.memo)).toResponse()

    @DeleteMapping("/api/v1/platform/institutions/{id}/voucher")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = vouchers.remove(jwt.userId(), id)

    @GetMapping("/api/v1/platform/voucher-settlements")
    fun settlements(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) month: String?) =
        vouchers.settlements(jwt.userId(), parseYm(month, clock)).map { it.toResponse() }

    @PostMapping("/api/v1/platform/voucher-settlements/generate")
    fun generate(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) month: String?) =
        mapOf("created" to vouchers.generateSettlements(jwt.userId(), parseYm(month, clock)))

    @PostMapping("/api/v1/platform/voucher-settlements/{id}/settle")
    fun settle(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody(required = false) req: SettleRequest?) =
        vouchers.settle(jwt.userId(), id, req?.memo).toResponse()
}

// ───────── SAAS-004 CS 티켓: 운영사 ─────────
@RestController
class PlatformSupportController(private val support: PlatformSupportUseCase) {
    data class ReplyRequest(@field:NotBlank @field:Size(max = 4000) val body: String)
    data class UpdateRequest(val status: String?, val assignToMe: Boolean?)

    @GetMapping("/api/v1/platform/tickets")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: String?, @RequestParam(required = false) institutionId: UUID?) =
        support.list(jwt.userId(), parseEnum(status, TicketStatus::valueOf, "INVALID_STATUS"), institutionId).map { it.toResponse() }

    @GetMapping("/api/v1/platform/tickets/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = support.get(jwt.userId(), id).toResponse()

    @PostMapping("/api/v1/platform/tickets/{id}/messages")
    fun reply(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: ReplyRequest) =
        support.reply(jwt.userId(), id, req.body).toResponse()

    @PatchMapping("/api/v1/platform/tickets/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody req: UpdateRequest) =
        support.update(jwt.userId(), id, parseEnum(req.status, TicketStatus::valueOf, "INVALID_STATUS"), req.assignToMe).toResponse()
}

// ───────── SAAS-004 CS 티켓: 기관(원장·실장) ─────────
@RestController
class InstitutionSupportController(private val support: InstitutionSupportUseCase) {
    data class OpenRequest(
        @field:NotBlank @field:Size(max = 100) val subject: String,
        val category: String?,
        @field:NotBlank @field:Size(max = 4000) val body: String,
    )
    data class ReplyRequest(@field:NotBlank @field:Size(max = 4000) val body: String)

    @PostMapping("/api/v1/support/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: OpenRequest) =
        support.open(jwt.userId(), inst(institutionId), req.subject, parseEnum(req.category, TicketCategory::valueOf, "INVALID_CATEGORY") ?: TicketCategory.GENERAL, req.body).toResponse()

    @GetMapping("/api/v1/support/tickets")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        support.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @GetMapping("/api/v1/support/tickets/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        support.get(jwt.userId(), inst(institutionId), id).toResponse()

    @PostMapping("/api/v1/support/tickets/{id}/messages")
    fun reply(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: ReplyRequest) =
        support.reply(jwt.userId(), inst(institutionId), id, req.body).toResponse()
}

// ───────── SAAS-005 공지/점검 ─────────
@RestController
class PlatformNoticeController(private val notices: PlatformNoticeUseCase, private val active: ActivePlatformNoticeQuery) {
    data class CreateRequest(
        val kind: String?,
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotBlank @field:Size(max = 2000) val body: String,
        val startsAt: Instant?,
        val endsAt: Instant?,
    )

    @GetMapping("/api/v1/platform/notices")
    fun list(@AuthenticationPrincipal jwt: Jwt) = notices.list(jwt.userId()).map { it.toResponse() }

    @PostMapping("/api/v1/platform/notices")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: CreateRequest) =
        notices.create(
            jwt.userId(), parseEnum(req.kind, PlatformNoticeKind::valueOf, "INVALID_KIND") ?: PlatformNoticeKind.NOTICE,
            req.title, req.body, req.startsAt, req.endsAt,
        ).toResponse()

    @PostMapping("/api/v1/platform/notices/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = notices.cancel(jwt.userId(), id).toResponse()

    /** 모든 로그인 사용자(기관 헤더 불필요) */
    @GetMapping("/api/v1/platform-notices/active")
    fun active() = active.active().map { it.toResponse() }
}

// ───────── SAAS-007 지표 ─────────
@RestController
class PlatformMetricsController(private val metrics: PlatformMetricsUseCase, private val clock: ClockPort) {
    data class CostRequest(@field:NotNull val amount: Long)

    @GetMapping("/api/v1/platform/metrics")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) month: String?) =
        metrics.metrics(jwt.userId(), parseYm(month, clock)).toResponse()

    @PutMapping("/api/v1/platform/metrics/{month}/marketing-cost")
    fun setCost(@AuthenticationPrincipal jwt: Jwt, @PathVariable month: String, @Valid @RequestBody req: CostRequest): Map<String, Any?> {
        val ym = parseYm(month, clock)
        metrics.setMarketingCost(jwt.userId(), ym, req.amount)
        return metrics.metrics(jwt.userId(), ym).toResponse()
    }
}
