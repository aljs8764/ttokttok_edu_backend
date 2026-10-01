package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.EventQuery
import com.ttokttok.application.port.`in`.EventView
import com.ttokttok.application.port.`in`.ManageEventUseCase
import com.ttokttok.application.port.`in`.ParentEventItem
import com.ttokttok.application.port.`in`.ParentEventUseCase
import com.ttokttok.application.port.`in`.RemindEventUseCase
import com.ttokttok.application.port.`in`.Tally
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.event.RsvpAnswer
import com.ttokttok.domain.event.SchoolEventId
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

private fun Tally.toMap() = mapOf("targets" to targets, "attend" to attend, "absent" to absent, "pending" to pending)

internal fun EventView.toResponse() = mapOf(
    "id" to id.value, "title" to title, "body" to body, "location" to location,
    "startsAt" to startsAt, "endsAt" to endsAt,
    "targets" to targets.map { mapOf("scope" to it.scope, "id" to it.id, "name" to it.name) },
    "rsvpEnabled" to rsvpEnabled, "rsvpDeadline" to rsvpDeadline, "reminderHoursBefore" to reminderHoursBefore,
    "remindedAt" to remindedAt, "status" to status.name, "authorName" to authorName, "createdAt" to createdAt,
    "tally" to tally?.toMap(),
)

/** EVT-001·003·004 (관리자 웹 + 교사앱) */
@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val manage: ManageEventUseCase,
    private val query: EventQuery,
    private val remind: RemindEventUseCase,
) {
    data class CreateRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 5000) val body: String? = null,
        @field:Size(max = 200) val location: String? = null,
        val startsAt: Instant,
        val endsAt: Instant? = null,
        @field:Size(min = 1, max = 200) val targets: List<NoticeTargetRequest>,
        val rsvpEnabled: Boolean = true,
        val rsvpDeadline: Instant? = null,
        val reminderHoursBefore: Int? = null,
    )

    data class UpdateRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 5000) val body: String? = null,
        @field:Size(max = 200) val location: String? = null,
        val startsAt: Instant,
        val endsAt: Instant? = null,
        val rsvpDeadline: Instant? = null,
        val reminderHoursBefore: Int = 24,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateRequest) =
        manage.create(
            ManageEventUseCase.CreateCommand(
                jwt.userId(), inst(institutionId), req.title, req.body, req.location, req.startsAt, req.endsAt,
                req.targets.map { it.toDomain() }, req.rsvpEnabled, req.rsvpDeadline, req.reminderHoursBefore,
            ),
        ).toResponse()

    /** upcoming=true(기본): 어제 이후 시작 행사를 가까운 순으로 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "true") upcoming: Boolean,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ) = query.list(jwt.userId(), inst(institutionId), upcoming, page, size).toResponse { it.toResponse() }

    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable id: UUID, @Valid @RequestBody req: UpdateRequest,
    ) = manage.update(
        jwt.userId(), inst(institutionId), SchoolEventId(id),
        ManageEventUseCase.UpdateCommand(req.title, req.body, req.location, req.startsAt, req.endsAt, req.rsvpDeadline, req.reminderHoursBefore),
    ).toResponse()

    @PostMapping("/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        manage.cancel(jwt.userId(), inst(institutionId), SchoolEventId(id)).toResponse()

    /** EVT-003 집계 대시보드: 참석/불참/미응답 + 명단(미응답 먼저). 갱신은 STOMP event.responded 수신 후 재조회 */
    @GetMapping("/{id}/summary")
    fun summary(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        query.summary(jwt.userId(), inst(institutionId), SchoolEventId(id)).let { s ->
            mapOf(
                "event" to s.event.toResponse(),
                "tally" to s.tally.toMap(),
                "rows" to s.rows.map { r ->
                    mapOf(
                        "studentId" to r.studentId.value, "studentName" to r.studentName, "classroomNames" to r.classroomNames,
                        "answer" to r.answer?.name, "reason" to r.reason, "respondedAt" to r.respondedAt, "respondedByName" to r.respondedByName,
                    )
                },
            )
        }

    /** 명단 엑셀: 학생명·반·응답·사유·응답시각 */
    @GetMapping("/{id}/responses.xlsx")
    fun export(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        query.exportResponses(jwt.userId(), inst(institutionId), SchoolEventId(id)).let { xlsx(it.bytes, it.filename) }

    /** EVT-004 수동 독촉 (30분 쿨타임). 응답: 푸시 대상 보호자 수 */
    @PostMapping("/{id}/remind")
    fun remind(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        mapOf("pushRecipients" to remind.remind(jwt.userId(), inst(institutionId), SchoolEventId(id)))
}

/** PAR-005 RSVP함 · EVT-005 간편 응답. 기관 헤더 불필요 */
@RestController
@RequestMapping("/api/v1/me/events")
class MeEventController(private val events: ParentEventUseCase) {
    data class RespondRequest(val studentId: UUID, val answer: RsvpAnswer, @field:Size(max = 200) val reason: String? = null)

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) childId: UUID?,
        @RequestParam(defaultValue = "false") includePast: Boolean,
    ) = events.list(jwt.userId(), childId?.let(::StudentId), includePast).map { it.toResponse() }

    /** 마감 후에는 403 */
    @PutMapping("/{id}/response")
    fun respond(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: RespondRequest) =
        events.respond(jwt.userId(), SchoolEventId(id), StudentId(req.studentId), req.answer, req.reason).toResponse()

    private fun ParentEventItem.toResponse() = mapOf(
        "id" to id.value, "institutionId" to institutionId.value, "institutionName" to institutionName,
        "title" to title, "body" to body, "location" to location, "startsAt" to startsAt, "endsAt" to endsAt,
        "status" to status.name, "rsvpEnabled" to rsvpEnabled, "rsvpDeadline" to rsvpDeadline, "open" to open,
        "children" to children.map {
            mapOf("studentId" to it.studentId.value, "name" to it.name, "answer" to it.answer?.name, "reason" to it.reason, "respondedAt" to it.respondedAt)
        },
    )
}
