package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.InviteParentUseCase
import com.ttokttok.application.port.`in`.JoinByInvitationUseCase
import com.ttokttok.application.port.`in`.PasswordUseCase
import com.ttokttok.application.port.`in`.ReviewJoinRequestUseCase
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.invitation.JoinRequestId
import com.ttokttok.domain.invitation.JoinRequestStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** STU-005 초대장 발송 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController(private val invite: InviteParentUseCase) {
    data class InviteRequest(@field:NotBlank val phone: String)

    @PostMapping("/parents")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: InviteRequest) =
        invite.invite(jwt.userId(), inst(institutionId), req.phone).let { mapOf("expiresAt" to it.expiresAt) } // 토큰은 응답에 싣지 않음(알림톡으로만)
}

/** 공개 — 초대 링크로 들어온 학부모가 자녀 정보를 제출 (로그인 불필요) */
@RestController
@RequestMapping("/api/v1/join")
class JoinController(private val join: JoinByInvitationUseCase) {
    data class JoinRequestBody(
        @field:NotBlank @field:Size(max = 50) val childName: String,
        val birthDate: LocalDate,
        @field:NotBlank @field:Size(max = 50) val guardianName: String,
        @field:Size(max = 20) val relation: String? = null,
    )

    @GetMapping("/{token}")
    fun info(@PathVariable token: String) = join.info(token)

    @PostMapping("/{token}")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@PathVariable token: String, @Valid @RequestBody req: JoinRequestBody) =
        mapOf("requestId" to join.submit(JoinByInvitationUseCase.Command(token, req.childName, req.birthDate, req.guardianName, req.relation)).value)
}

/** STU-004 가입 승인 관리 */
@RestController
@RequestMapping("/api/v1/join-requests")
class JoinRequestController(private val review: ReviewJoinRequestUseCase) {
    data class ApproveRequest(val classroomId: UUID)
    data class RejectRequest(@field:Size(max = 200) val reason: String? = null)

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) status: JoinRequestStatus?,
    ) = review.list(jwt.userId(), inst(institutionId), status).map {
        mapOf(
            "id" to it.id.value, "childName" to it.childName, "birthDate" to it.birthDate, "guardianName" to it.guardianName,
            "guardianPhone" to it.guardianPhoneMasked, "relation" to it.relation, "status" to it.status, "submittedAt" to it.submittedAt,
        )
    }

    @PostMapping("/{id}/approve")
    fun approve(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @RequestBody req: ApproveRequest) =
        review.approve(jwt.userId(), inst(institutionId), JoinRequestId(id), ClassroomId(req.classroomId)).toResponse()

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: RejectRequest) {
        review.reject(jwt.userId(), inst(institutionId), JoinRequestId(id), req.reason)
    }
}

/** AUTH-004·005 */
@RestController
@RequestMapping("/api/v1/auth/password")
class PasswordController(private val passwords: PasswordUseCase) {
    data class TempRequest(@field:NotBlank val email: String)
    data class ChangeRequest(@field:NotBlank val currentPassword: String, @field:NotBlank val newPassword: String)

    /** 공개. 계정 존재 여부와 관계없이 항상 202 */
    @PostMapping("/temp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun issueTemp(@Valid @RequestBody req: TempRequest) {
        passwords.issueTemporary(req.email)
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun change(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: ChangeRequest) {
        passwords.change(jwt.userId(), req.currentPassword, req.newPassword)
    }
}
