package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AttendanceView
import com.ttokttok.application.port.`in`.CheckInUseCase
import com.ttokttok.application.port.`in`.CheckOutUseCase
import com.ttokttok.application.port.`in`.GetDailyAttendanceQuery
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.GetTimelineQuery
import com.ttokttok.application.port.`in`.RegisterDeviceUseCase
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.Platform
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

const val IDEMPOTENCY_HEADER = "Idempotency-Key"

data class AttendanceResponse(
    val studentId: UUID, val studentName: String, val classroomId: UUID, val date: LocalDate,
    val status: String, val isLate: Boolean, val isEarlyLeave: Boolean,
    val checkInAt: Instant?, val checkOutAt: Instant?, val nextDestinationId: UUID?, val nextDestinationName: String?,
    /** 출결 행 id — 수동 변경(PATCH /attendance/{dayId}/status)에 쓴다. 아직 행이 없으면 null */
    val dayId: UUID? = null,
    val absenceReason: String? = null,
)

internal fun AttendanceView.toResponse() = AttendanceResponse(
    studentId.value, studentName, classroomId.value, date, status.name, isLate, isEarlyLeave,
    checkInAt, checkOutAt, nextDestinationId?.value, nextDestinationName,
    dayId?.value, absenceReason,
)

@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceController(
    private val checkIn: CheckInUseCase,
    private val checkOut: CheckOutUseCase,
    private val daily: GetDailyAttendanceQuery,
) {
    data class CheckInRequest(val studentId: UUID, val classroomId: UUID, val clientAt: Instant? = null)
    data class CheckOutRequest(val studentId: UUID, val classroomId: UUID, val destinationId: UUID, val clientAt: Instant? = null)

    /** 원터치 등원 (ATT-005) */
    @PostMapping("/check-in")
    fun checkIn(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyKey: String?,
        @RequestBody req: CheckInRequest,
    ) = checkIn.checkIn(
        CheckInUseCase.Command(jwt.userId(), inst(institutionId), StudentId(req.studentId), ClassroomId(req.classroomId), req.clientAt, idempotencyKey),
    ).toResponse()

    /** 하원 + 다음 목적지 (ATT-006) */
    @PostMapping("/check-out")
    fun checkOut(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyKey: String?,
        @RequestBody req: CheckOutRequest,
    ) = checkOut.checkOut(
        CheckOutUseCase.Command(
            jwt.userId(), inst(institutionId), StudentId(req.studentId), ClassroomId(req.classroomId),
            DestinationId(req.destinationId), req.clientAt, idempotencyKey,
        ),
    ).toResponse()

    /** 반 출결 현황 (교사 앱 메인 / ATT-001) */
    @GetMapping("/daily")
    fun daily(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam classId: UUID, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = daily.get(jwt.userId(), inst(institutionId), ClassroomId(classId), date).map { it.toResponse() }
}

/** 학부모 앱 (기관 헤더 불필요 — 소속 전 기관을 가로지름) */
@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val children: GetMyChildrenQuery,
    private val timeline: GetTimelineQuery,
    private val devices: RegisterDeviceUseCase,
) {
    data class DeviceRequest(val flavor: AppFlavor, val platform: Platform, @field:NotBlank val token: String)

    /** 아이 단위 (스펙 7-8). 한 아이가 여러 기관에 다니면 enrollments 가 여러 개 */
    @GetMapping("/children")
    fun children(@AuthenticationPrincipal jwt: Jwt) = children.children(jwt.userId()).map { it.toResponse() }

    /** 통합 안심 타임라인 (PAR-001). 커서 = 마지막 항목의 occurredAt */
    @GetMapping("/timeline")
    fun timeline(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = timeline.timeline(jwt.userId(), children.resolveFilter(jwt.userId(), childId), before, limit).map {
        mapOf(
            "studentId" to it.studentId.value, "studentName" to it.studentName, "childId" to it.childId?.value,
            "institutionId" to it.institutionId.value, "institutionName" to it.institutionName,
            "type" to it.type.name, "status" to it.status.name, "isLate" to it.isLate,
            "destinationName" to it.destinationName, "occurredAt" to it.occurredAt,
        )
    }

    @PutMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun registerDevice(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: DeviceRequest) {
        devices.register(jwt.userId(), req.flavor, req.platform, req.token)
    }
}

/** GET /me/children 응답 — ChildControllers 도 같은 모양으로 돌려준다 */
internal fun com.ttokttok.application.port.`in`.GetMyChildrenQuery.ChildView.toResponse() = mapOf(
    "childId" to childId.value,
    "name" to name,
    "enrollments" to enrollments.map {
        mapOf(
            "studentId" to it.studentId.value, "studentName" to it.studentName,
            "institutionId" to it.institutionId.value, "institutionName" to it.institutionName,
            "institutionType" to it.institutionType, "status" to it.status,
        )
    },
)
