package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AbsenceKind
import com.ttokttok.application.port.`in`.AbsenceRequestStatus
import com.ttokttok.application.port.`in`.AbsenceRequestView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.ParentAbsenceRequestUseCase
import com.ttokttok.application.port.`in`.StaffAbsenceRequestUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal fun AbsenceRequestView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName,
    "studentId" to studentId, "studentName" to studentName, "guardianName" to guardianName,
    "date" to date, "kind" to kind.name, "expectedTime" to expectedTime?.toString(), "reason" to reason,
    "status" to status.name, "acknowledgedAt" to acknowledgedAt, "createdAt" to createdAt,
)

/** ATT-007 결석·지각·조퇴 사전 신청 — 학부모 앱 */
@RestController
@RequestMapping("/api/v1/me/absence-requests")
class MeAbsenceRequestController(
    private val requests: ParentAbsenceRequestUseCase,
    private val children: GetMyChildrenQuery,
) {
    data class SubmitRequest(
        @field:NotNull val studentId: UUID,
        @field:NotNull val date: LocalDate,
        @field:NotNull val kind: AbsenceKind,
        @field:NotBlank @field:Size(max = 300) val reason: String,
        /** 지각·조퇴일 때 HH:mm */
        val expectedTime: LocalTime? = null,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: SubmitRequest) =
        requests.submit(jwt.userId(), ParentAbsenceRequestUseCase.Submit(req.studentId, req.date, req.kind, req.reason, req.expectedTime)).toResponse()

    @GetMapping
    fun mine(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(defaultValue = "30") limit: Int,
    ) = requests.mine(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet(), from, limit).map { it.toResponse() }

    @PostMapping("/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = requests.cancel(jwt.userId(), id).toResponse()
}

/** ATT-007 사전 신청 확인 — 관리자 웹·교사 앱 */
@RestController
@RequestMapping("/api/v1/absence-requests")
class AbsenceRequestController(private val requests: StaffAbsenceRequestUseCase) {
    /** 기본: 오늘부터 2주. 취소된 신청은 제외 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) status: AbsenceRequestStatus?,
    ): List<Map<String, Any?>> {
        val start = from ?: LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        return requests.list(jwt.userId(), inst(institutionId), start, to ?: start.plusDays(14), status).map { it.toResponse() }
    }

    @PostMapping("/{id}/acknowledge")
    fun acknowledge(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        requests.acknowledge(jwt.userId(), inst(institutionId), id).toResponse()
}
