package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.adapter.`in`.web.security.TokenService
import com.ttokttok.application.port.`in`.AttendanceEvidenceUseCase
import com.ttokttok.application.port.`in`.DashboardScheduleQuery
import com.ttokttok.application.port.`in`.FileRef
import com.ttokttok.application.port.`in`.FileUseCase
import com.ttokttok.application.port.`in`.InstitutionSettingsUseCase
import com.ttokttok.application.port.`in`.ParentScheduleQuery
import com.ttokttok.application.port.`in`.RegisterDeviceUseCase
import com.ttokttok.application.port.`in`.SessionUseCase
import com.ttokttok.application.port.`in`.StaffInvitationUseCase
import com.ttokttok.application.port.`in`.TermsUseCase
import com.ttokttok.application.port.out.PresignedUrl
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.TermsAudience
import com.ttokttok.domain.terms.TermsId
import com.ttokttok.domain.user.Role
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

internal fun FileRef.toMap() = mapOf("id" to id.value, "name" to name, "mime" to mime, "size" to size, "downloadUrl" to downloadUrl)
internal fun PresignedUrl.toMap() = mapOf("url" to url, "method" to method, "headers" to headers, "expiresAt" to expiresAt)

/** SET-001 기관 정보 */
@RestController
@RequestMapping("/api/v1/institution")
class InstitutionSettingsController(private val settings: InstitutionSettingsUseCase) {
    data class UpdateRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:NotBlank @field:Size(max = 50) val ownerName: String,
        @field:Size(max = 200) val address: String? = null,
        @field:Size(max = 20) val phone: String? = null,
        @field:Min(0) @field:Max(120) val lateThresholdMinutes: Int = 10,
        @field:Min(0) @field:Max(120) val earlyLeaveThresholdMinutes: Int = 10,
        val logoFileId: UUID? = null,
        val sealFileId: UUID? = null,
    )

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        settings.get(jwt.userId(), inst(institutionId)).toMap()

    /** 원장 전용. 지각·조퇴 기준 변경은 감사 로그에 남는다 */
    @PutMapping
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: UpdateRequest) =
        settings.update(
            jwt.userId(), inst(institutionId),
            InstitutionSettingsUseCase.UpdateCommand(
                req.name, req.ownerName, req.address, req.phone, req.lateThresholdMinutes, req.earlyLeaveThresholdMinutes,
                req.logoFileId?.let(::FileId), req.sealFileId?.let(::FileId),
            ),
        ).toMap()

    private fun com.ttokttok.application.port.`in`.InstitutionView.toMap() = mapOf(
        "id" to id.value, "name" to name, "ownerName" to ownerName, "address" to address, "phone" to phone,
        "lateThresholdMinutes" to lateThresholdMinutes, "earlyLeaveThresholdMinutes" to earlyLeaveThresholdMinutes,
        "logo" to logo?.toMap(), "seal" to seal?.toMap(),
    )
}

/** 공통 파일: presign → 클라이언트가 S3 로 PUT → complete → 다른 API 에서 fileId 로 참조 */
@RestController
@RequestMapping("/api/v1/files")
class FileController(private val files: FileUseCase) {
    data class PresignRequest(
        val purpose: FilePurpose,
        @field:NotBlank @field:Size(max = 200) val filename: String,
        @field:NotBlank val mime: String,
        @field:Positive val size: Long,
    )

    @PostMapping("/presign")
    @ResponseStatus(HttpStatus.CREATED)
    fun presign(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: PresignRequest) =
        files.presign(jwt.userId(), inst(institutionId), req.purpose, req.filename, req.mime, req.size).let {
            mapOf("fileId" to it.fileId.value, "upload" to it.upload.toMap())
        }

    @PostMapping("/{id}/complete")
    fun complete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        files.complete(jwt.userId(), inst(institutionId), FileId(id)).toMap()

    /** 5분 만료 다운로드 URL */
    @GetMapping("/{id}/download-url")
    fun download(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        files.download(jwt.userId(), inst(institutionId), FileId(id)).toMap()
}

/** ATT-003 결석 증빙 */
@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceEvidenceController(private val evidence: AttendanceEvidenceUseCase) {
    data class EvidenceRequest(val fileId: UUID?)

    /** fileId = null 이면 해제 */
    @PutMapping("/{dayId}/evidence")
    fun attach(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable dayId: UUID, @RequestBody req: EvidenceRequest,
    ) = evidence.attach(jwt.userId(), inst(institutionId), AttendanceDayId(dayId), req.fileId?.let(::FileId)).toResponse()
}

/** STF-002 교직원 이메일 초대 */
@RestController
@RequestMapping("/api/v1/staff/invitations")
class StaffInvitationController(private val invitations: StaffInvitationUseCase) {
    data class InviteRequest(@field:NotBlank val email: String, @field:NotBlank @field:Size(max = 50) val name: String, val role: Role)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: InviteRequest) =
        invitations.invite(jwt.userId(), inst(institutionId), req.email, req.name, req.role).toMap()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        invitations.list(jwt.userId(), inst(institutionId)).map { it.toMap() }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        invitations.revoke(jwt.userId(), inst(institutionId), StaffInvitationId(id))
    }

    private fun StaffInvitationUseCase.StaffInvitationView.toMap() = mapOf(
        "id" to id.value, "email" to email, "name" to name, "role" to role.name,
        "status" to status, "expiresAt" to expiresAt, "acceptedAt" to acceptedAt,
    )
}

/** 공개 — 초대 링크 열람·수락. 수락하면 바로 로그인 토큰을 준다 */
@RestController
@RequestMapping("/api/v1/staff-invitations")
class PublicStaffInvitationController(
    private val invitations: StaffInvitationUseCase,
    private val tokens: TokenService,
    private val sessions: SessionUseCase,
) {
    data class AcceptRequest(@field:NotBlank val password: String)

    @GetMapping("/{token}")
    fun info(@PathVariable token: String) = invitations.info(token).let {
        mapOf(
            "institutionName" to it.institutionName, "name" to it.name, "email" to it.email,
            "role" to it.role.name, "existingAccount" to it.existingAccount, "expiresAt" to it.expiresAt,
        )
    }

    @PostMapping("/{token}/accept")
    fun accept(@PathVariable token: String, @Valid @RequestBody req: AcceptRequest): AuthController.AuthResponse {
        val user = invitations.accept(token, req.password)
        val session = sessions.start(user.userId, false)
        val pair = tokens.issue(user.userId, false, session.jti, session.expiresAt)
        return AuthController.AuthResponse(pair.accessToken, pair.refreshToken, pair.accessExpiresAt, user.toResponse())
    }
}

/** SET-005 약관 */
@RestController
class TermsController(private val terms: TermsUseCase) {
    data class AgreeRequest(@field:Size(min = 1, max = 20) val termsIds: List<UUID>)

    /** 공개 — 가입 화면용 현재 약관 */
    @GetMapping("/api/v1/terms")
    fun current(@RequestParam(defaultValue = "PARENT") audience: TermsAudience) = terms.current(audience).map { it.toMap() }

    /** 아직 동의하지 않은 필수 약관 (개정 시 재동의). 비어 있지 않으면 앱이 동의 화면을 띄운다 */
    @GetMapping("/api/v1/me/terms/pending")
    fun pending(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "PARENT") audience: TermsAudience) =
        terms.pending(jwt.userId(), audience).map { it.toMap() }

    @PostMapping("/api/v1/me/terms/agreements")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun agree(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: AgreeRequest, request: HttpServletRequest) {
        terms.agree(jwt.userId(), req.termsIds.map(::TermsId), request.remoteAddr)
    }

    private fun TermsUseCase.TermsView.toMap() = mapOf(
        "id" to id.value, "type" to type.name, "version" to version, "title" to title, "body" to body,
        "required" to required, "effectiveAt" to effectiveAt,
    )
}

/** PAR-003 주간 스케줄 · 기기 해제 · 전체 로그아웃 (학부모·교직원 공통 /me) */
@RestController
@RequestMapping("/api/v1/me")
class MeSettingsController(
    private val schedule: ParentScheduleQuery,
    private val devices: RegisterDeviceUseCase,
    private val sessions: SessionUseCase,
) {
    data class DeviceDeleteRequest(@field:NotBlank val token: String)

    /** week = 그 주 아무 날짜(월요일로 맞춤), 없으면 이번 주 */
    @GetMapping("/schedule")
    fun schedule(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) week: LocalDate?,
    ) = schedule.week(jwt.userId(), childId?.let(::StudentId), week).let { w ->
        mapOf(
            "weekStart" to w.weekStart,
            "children" to w.children.map { c ->
                mapOf(
                    "studentId" to c.studentId.value, "name" to c.name,
                    "days" to c.days.map { d ->
                        mapOf(
                            "date" to d.date, "dayOfWeek" to d.dayOfWeek.name,
                            "items" to d.items.map {
                                mapOf(
                                    "kind" to it.kind, "title" to it.title, "institutionName" to it.institutionName,
                                    "startsAt" to it.startsAt, "endsAt" to it.endsAt, "classroomId" to it.classroomId?.value,
                                    "eventId" to it.eventId, "status" to it.status,
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    /** 앱 로그아웃·삭제 시 FCM 토큰 해제 */
    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unregisterDevice(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: DeviceDeleteRequest) {
        devices.unregister(jwt.userId(), req.token)
    }

    /** 모든 기기에서 로그아웃 (refresh 전부 폐기) */
    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll(@AuthenticationPrincipal jwt: Jwt) {
        sessions.endAll(jwt.userId())
    }
}

/** DASH-003 주요 일정 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardScheduleController(private val schedule: DashboardScheduleQuery) {
    @GetMapping("/schedule")
    fun upcoming(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "7") days: Int,
    ) = schedule.upcoming(jwt.userId(), inst(institutionId), days).map {
        mapOf("kind" to it.kind, "at" to it.at, "title" to it.title, "refId" to it.refId, "detail" to it.detail)
    }
}
