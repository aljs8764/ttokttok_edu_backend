package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.CheckinQrAdminUseCase
import com.ttokttok.application.port.`in`.StudentAppUseCase
import com.ttokttok.application.port.`in`.StudentDeviceLinkUseCase
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.qr.CheckinQrId
import com.ttokttok.domain.qr.Geofence
import com.ttokttok.domain.qr.StudentDeviceId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

// 학생앱 QR 출석 (스펙 7-7)

/** 학생앱 인증 헤더 — 보호자가 연결한 기기 토큰 (JWT 아님) */
const val DEVICE_TOKEN_HEADER = "X-Device-Token"

/** QR-001 출석 QR · QR-002 기관 위치 (관리자 웹) */
@RestController
@RequestMapping("/api/v1")
class CheckinQrController(private val qrs: CheckinQrAdminUseCase) {
    data class NameRequest(@field:NotBlank @field:Size(max = 50) val name: String)
    data class GeofenceRequest(val latitude: Double, val longitude: Double, val radiusMeters: Int = Geofence.DEFAULT_RADIUS_M)

    @GetMapping("/checkin-qrs")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        qrs.list(jwt.userId(), inst(institutionId)).map { it.toMap() }

    @PostMapping("/checkin-qrs")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: NameRequest) =
        qrs.create(jwt.userId(), inst(institutionId), req.name).toMap()

    @PatchMapping("/checkin-qrs/{id}")
    fun rename(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable id: UUID, @Valid @RequestBody req: NameRequest,
    ) = qrs.rename(jwt.userId(), inst(institutionId), CheckinQrId(id), req.name).toMap()

    /** 토큰 교체 — 이전 인쇄물은 바로 무효. 다시 인쇄해서 붙인다 */
    @PostMapping("/checkin-qrs/{id}/rotate")
    fun rotate(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        qrs.rotate(jwt.userId(), inst(institutionId), CheckinQrId(id)).toMap()

    @DeleteMapping("/checkin-qrs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        qrs.delete(jwt.userId(), inst(institutionId), CheckinQrId(id))
    }

    /** 최근 7일 스캔 실패 (위치 밖·남의 QR 등) */
    @GetMapping("/checkin-qrs/failures")
    fun failures(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        qrs.recentFailures(jwt.userId(), inst(institutionId)).map {
            mapOf("studentId" to it.studentId.value, "studentName" to it.studentName, "reason" to it.reason, "distanceMeters" to it.distanceMeters, "at" to it.at)
        }

    @GetMapping("/institution/geofence")
    fun geofence(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        mapOf("geofence" to qrs.geofence(jwt.userId(), inst(institutionId))?.toMap())

    /** OWNER. 본문 없이(null) 보내면 위치 확인을 끈다 */
    @PutMapping("/institution/geofence")
    fun setGeofence(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestBody(required = false) req: GeofenceRequest?,
    ) = mapOf(
        "geofence" to qrs.setGeofence(jwt.userId(), inst(institutionId), req?.let { Geofence(it.latitude, it.longitude, it.radiusMeters) })?.toMap(),
    )

    private fun CheckinQrAdminUseCase.QrView.toMap() =
        mapOf("id" to id.value, "name" to name, "content" to content, "createdAt" to createdAt, "rotatedAt" to rotatedAt)

    private fun Geofence.toMap() = mapOf("latitude" to latitude, "longitude" to longitude, "radiusMeters" to radiusMeters)
}

/** PAR-007 보호자: 자녀 기기 연결 */
@RestController
@RequestMapping("/api/v1/me/children/{studentId}")
class ChildDeviceController(private val links: StudentDeviceLinkUseCase) {

    /** 8자리 연결 코드 (10분, 1회용) — 학생앱에 입력 */
    @PostMapping("/device-links")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(@AuthenticationPrincipal jwt: Jwt, @PathVariable studentId: UUID) =
        links.issueCode(jwt.userId(), StudentId(studentId)).let { mapOf("code" to it.code, "expiresAt" to it.expiresAt) }

    @GetMapping("/devices")
    fun devices(@AuthenticationPrincipal jwt: Jwt, @PathVariable studentId: UUID) =
        links.devices(jwt.userId(), StudentId(studentId)).map {
            mapOf("id" to it.id.value, "deviceName" to it.deviceName, "createdAt" to it.createdAt, "lastSeenAt" to it.lastSeenAt)
        }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal jwt: Jwt, @PathVariable studentId: UUID, @PathVariable deviceId: UUID) {
        links.revoke(jwt.userId(), StudentId(studentId), StudentDeviceId(deviceId))
    }
}

/** STD-001·002 학생앱 — SecurityConfig 에서 JWT 없이 열고, 여기서 기기 토큰으로 인증한다 */
@RestController
@RequestMapping("/api/v1/student")
class StudentAppController(private val app: StudentAppUseCase) {
    data class LinkRequest(@field:NotBlank @field:Size(max = 20) val code: String, @field:Size(max = 50) val deviceName: String = "")

    data class ScanRequest(
        @field:NotBlank @field:Size(max = 300) val qr: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracy: Double? = null,
        val destinationId: UUID? = null,
        val clientAt: Instant? = null,
    )

    @PostMapping("/link")
    fun link(@Valid @RequestBody req: LinkRequest) = app.link(req.code, req.deviceName).let {
        mapOf(
            "deviceToken" to it.deviceToken,
            "student" to mapOf("id" to it.studentId.value, "name" to it.studentName),
            "institution" to mapOf("id" to it.institutionId.value, "name" to it.institutionName),
        )
    }

    @GetMapping("/me")
    fun me(@RequestHeader(DEVICE_TOKEN_HEADER) token: String) = app.me(token).let { h ->
        mapOf(
            "student" to mapOf("id" to h.studentId.value, "name" to h.studentName),
            "institution" to mapOf("id" to h.institutionId.value, "name" to h.institutionName),
            "today" to h.today.map { c ->
                mapOf(
                    "classroomId" to c.classroomId, "classroomName" to c.classroomName,
                    "startTime" to c.startTime, "endTime" to c.endTime, "attendance" to c.attendance?.toResponse(),
                )
            },
        )
    }

    @PostMapping("/scan")
    fun scan(
        @RequestHeader(DEVICE_TOKEN_HEADER) token: String,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyKey: String?,
        @Valid @RequestBody req: ScanRequest,
    ) = app.scan(
        token,
        StudentAppUseCase.ScanCommand(
            req.qr, req.latitude, req.longitude, req.accuracy, req.destinationId?.let(::DestinationId), req.clientAt, idempotencyKey,
        ),
    ).let { r ->
        mapOf(
            "outcome" to r.outcome.name,
            "classroomName" to r.classroomName,
            "attendance" to r.attendance?.toResponse(),
            "destinations" to r.destinations.map { mapOf("id" to it.id.value, "name" to it.name, "type" to it.type) },
        )
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestHeader(DEVICE_TOKEN_HEADER) token: String) {
        app.logout(token)
    }
}
