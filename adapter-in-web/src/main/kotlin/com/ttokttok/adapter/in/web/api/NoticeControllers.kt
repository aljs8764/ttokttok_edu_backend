package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ComposeNoticeUseCase
import com.ttokttok.application.port.`in`.NoticeQuery
import com.ttokttok.application.port.`in`.NoticeView
import com.ttokttok.application.port.`in`.ParentNoticeItem
import com.ttokttok.application.port.`in`.ParentNoticeUseCase
import com.ttokttok.application.port.`in`.ResendUnreadNoticeUseCase
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticeStatus
import com.ttokttok.domain.notice.NoticeTarget
import com.ttokttok.domain.notice.TargetScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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

data class NoticeTargetRequest(val scope: TargetScope, val id: UUID? = null) {
    fun toDomain(): NoticeTarget = when (scope) {
        TargetScope.ALL -> NoticeTarget.all()
        TargetScope.CLASS -> NoticeTarget.classroom(ClassroomId(id ?: throw InvalidInputException("INVALID_TARGET", "반 id가 필요합니다")))
        TargetScope.STUDENT -> NoticeTarget.student(StudentId(id ?: throw InvalidInputException("INVALID_TARGET", "원생 id가 필요합니다")))
    }
}

internal fun NoticeView.toResponse() = mapOf(
    "id" to id.value, "kind" to kind.name, "title" to title, "body" to body, "pinned" to pinned,
    "targets" to targets.map { mapOf("scope" to it.scope, "id" to it.id, "name" to it.name) },
    "status" to status.name, "scheduledAt" to scheduledAt, "sentAt" to sentAt, "lastResentAt" to lastResentAt,
    "author" to mapOf("id" to authorId.value, "name" to authorName), "createdAt" to createdAt,
    "readStats" to readStats?.let { mapOf("targetStudents" to it.targetStudents, "readStudents" to it.readStudents, "rate" to it.rate) },
    "attachments" to attachments.map { it.toMap() },
)

/** NTC-001·002·004·005·006·009 (관리자 웹 + 교사앱) */
@RestController
@RequestMapping("/api/v1/notices")
class NoticeController(
    private val compose: ComposeNoticeUseCase,
    private val query: NoticeQuery,
    private val resend: ResendUnreadNoticeUseCase,
) {
    data class NoticeRequest(
        val kind: NoticeKind = NoticeKind.NOTE,
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotBlank @field:Size(max = 5000) val body: String,
        val pinned: Boolean = false,
        @field:Size(min = 1, max = 200) val targets: List<NoticeTargetRequest>,
        /** null 이면 즉시 발송 */
        val sendAt: Instant? = null,
        /** /files/presign(purpose=NOTICE_ATTACHMENT) → complete 한 파일 id, 최대 10개 */
        @field:Size(max = 10) val attachments: List<UUID> = emptyList(),
    )

    private fun NoticeRequest.toCommand(jwt: Jwt, institutionId: UUID) = ComposeNoticeUseCase.Command(
        jwt.userId(), inst(institutionId), kind, title, body, pinned, targets.map { it.toDomain() }, sendAt,
        attachments.map(::FileId),
    )

    /** 작성 + 즉시/예약 발송. 전체 공지(ANNOUNCEMENT)는 원장·실장만 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: NoticeRequest) =
        compose.create(req.toCommand(jwt, institutionId)).toResponse()

    /** 발송 이력 (교사는 본인 작성분만) */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) kind: NoticeKind?, @RequestParam(required = false) status: NoticeStatus?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ) = query.list(jwt.userId(), inst(institutionId), kind, status, page, size).toResponse { it.toResponse() }

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        query.detail(jwt.userId(), inst(institutionId), NoticeId(id)).toResponse()

    /** 예약 건 수정 (발송 후 수정 불가) */
    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable id: UUID, @Valid @RequestBody req: NoticeRequest,
    ) = compose.update(jwt.userId(), inst(institutionId), NoticeId(id), req.toCommand(jwt, institutionId)).toResponse()

    /** 예약 취소 */
    @PostMapping("/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        compose.cancel(jwt.userId(), inst(institutionId), NoticeId(id)).toResponse()

    /** NTC-005 수신확인 — 원생별 열람 여부(미열람 먼저), 보호자별 도달·열람 시각 */
    @GetMapping("/{id}/receipts")
    fun receipts(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        query.receipts(jwt.userId(), inst(institutionId), NoticeId(id)).let { r ->
            mapOf(
                "noticeId" to r.noticeId.value,
                "stats" to mapOf("targetStudents" to r.stats.targetStudents, "readStudents" to r.stats.readStudents, "rate" to r.stats.rate),
                "canResendAt" to r.canResendAt,
                "students" to r.students.map { s ->
                    mapOf(
                        "studentId" to s.studentId.value, "studentName" to s.studentName, "classroomNames" to s.classroomNames,
                        "read" to s.read, "firstReadAt" to s.firstReadAt, "resentCount" to s.resentCount,
                        "appLinked" to s.guardians.isNotEmpty(),
                        "guardians" to s.guardians.map { g ->
                            mapOf("userId" to g.userId.value, "name" to g.name, "deliveredAt" to g.deliveredAt, "readAt" to g.readAt)
                        },
                    )
                },
            )
        }

    /** NTC-006 미열람자만 재푸시 (30분 쿨타임) */
    @PostMapping("/{id}/resend-unread")
    fun resendUnread(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        resend.resend(jwt.userId(), inst(institutionId), NoticeId(id)).let {
            mapOf("students" to it.students, "pushRecipients" to it.pushRecipients, "resentAt" to it.resentAt)
        }
}

/** 학부모 알림장함 (PAR-004). 기관 헤더 불필요 — 소속 전 기관 */
@RestController
@RequestMapping("/api/v1/me/notices")
class MeNoticeController(
    private val notices: ParentNoticeUseCase,
    private val children: com.ttokttok.application.port.`in`.GetMyChildrenQuery,
) {

    @GetMapping
    fun inbox(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = notices.inbox(jwt.userId(), children.resolveFilter(jwt.userId(), childId), before, limit).map { it.toResponse() }

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = notices.detail(jwt.userId(), NoticeId(id)).toResponse()

    /** 상세 화면 최초 진입 시 호출 (재호출해도 최초 열람 시각 유지) */
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) {
        notices.markRead(jwt.userId(), NoticeId(id))
    }

    private fun ParentNoticeItem.toResponse() = mapOf(
        "id" to id.value, "institutionId" to institutionId.value, "institutionName" to institutionName,
        "kind" to kind.name, "title" to title, "body" to body, "pinned" to pinned, "sentAt" to sentAt,
        "children" to children.map { mapOf("studentId" to it.studentId.value, "name" to it.name) },
        "readAt" to readAt, "authorName" to authorName,
        "attachments" to attachments.map { it.toMap() },
    )
}
