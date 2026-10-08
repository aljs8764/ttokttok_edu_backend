package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ConversationView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.MessageView
import com.ttokttok.application.port.`in`.ParentMessagingUseCase
import com.ttokttok.application.port.`in`.StaffMessagingUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
import java.time.Instant
import java.util.UUID

internal fun ConversationView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName,
    "studentId" to studentId, "studentName" to studentName,
    "guardian" to mapOf("userId" to guardianUserId, "name" to guardianName),
    "notice" to noticeId?.let { mapOf("id" to it, "title" to noticeTitle) },
    "lastMessageAt" to lastMessageAt, "lastMessagePreview" to lastMessagePreview, "unread" to unread,
)

internal fun MessageView.toResponse() = mapOf(
    "id" to id, "senderId" to senderId, "senderName" to senderName, "side" to side.name, "body" to body, "createdAt" to createdAt,
)

data class SendMessageRequest(@field:NotBlank @field:Size(max = 2000) val body: String)

/** NTC-007 알림장 회신 · NTC-008 1:1 채팅 — 학부모 앱 (기관 헤더 불필요) */
@RestController
@RequestMapping("/api/v1/me/conversations")
class MeConversationController(
    private val messaging: ParentMessagingUseCase,
    private val children: GetMyChildrenQuery,
) {
    data class OpenRequest(val studentId: UUID, val noticeId: UUID? = null)

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(defaultValue = "30") limit: Int,
    ) = messaging.conversations(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet(), before, limit).map { it.toResponse() }

    /** 대화 열기(없으면 만든다). noticeId 가 있으면 그 알림장에 대한 회신 스레드 */
    @PostMapping("/open")
    fun open(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: OpenRequest) =
        messaging.open(jwt.userId(), req.studentId, req.noticeId).toResponse()

    @GetMapping("/unread")
    fun unread(@AuthenticationPrincipal jwt: Jwt) = mapOf("unread" to messaging.unreadTotal(jwt.userId()))

    /** 최신순. 위로 스크롤하면 마지막 createdAt 을 before 로 */
    @GetMapping("/{id}/messages")
    fun messages(
        @AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID,
        @RequestParam(required = false) before: Instant?, @RequestParam(defaultValue = "30") limit: Int,
    ) = messaging.messages(jwt.userId(), id, before, limit).map { it.toResponse() }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: SendMessageRequest) =
        messaging.send(jwt.userId(), id, req.body).toResponse()

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) {
        messaging.markRead(jwt.userId(), id)
    }
}

/** NTC-007 회신 확인 · NTC-008 1:1 채팅 — 관리자 웹·교사 앱 (원장·실장 전체, 교사 담당 반) */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController(private val messaging: StaffMessagingUseCase) {
    data class OpenRequest(val studentId: UUID)

    /** noticeId 로 좁히면 한 알림장의 학부모 회신 목록 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(required = false) noticeId: UUID?, @RequestParam(required = false) studentId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "30") size: Int,
    ) = messaging.conversations(jwt.userId(), inst(institutionId), unreadOnly, noticeId, studentId, page, size).map { it.toResponse() }

    /** 교직원이 먼저 말 걸기 — 원생의 앱 연결 보호자마다 대화 하나씩 */
    @PostMapping("/open")
    fun open(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestBody req: OpenRequest) =
        messaging.openForStudent(jwt.userId(), inst(institutionId), req.studentId).map { it.toResponse() }

    @GetMapping("/unread")
    fun unread(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        mapOf("unread" to messaging.unreadTotal(jwt.userId(), inst(institutionId)))

    @GetMapping("/{id}/messages")
    fun messages(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @RequestParam(required = false) before: Instant?, @RequestParam(defaultValue = "30") limit: Int,
    ) = messaging.messages(jwt.userId(), inst(institutionId), id, before, limit).map { it.toResponse() }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @Valid @RequestBody req: SendMessageRequest,
    ) = messaging.send(jwt.userId(), inst(institutionId), id, req.body).toResponse()

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        messaging.markRead(jwt.userId(), inst(institutionId), id)
    }
}
