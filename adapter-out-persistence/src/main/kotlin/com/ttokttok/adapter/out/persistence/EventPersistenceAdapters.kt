package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.EventResponseEntity
import com.ttokttok.adapter.out.persistence.entity.SchoolEventEntity
import com.ttokttok.adapter.out.persistence.repository.EventResponseJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SchoolEventJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SchoolEventTargetJpaRepository
import com.ttokttok.application.port.out.EventTargetPort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.RsvpResponsePort
import com.ttokttok.application.port.out.SchoolEventPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.event.RsvpAnswer
import com.ttokttok.domain.event.RsvpResponse
import com.ttokttok.domain.event.SchoolEvent
import com.ttokttok.domain.event.SchoolEventId
import com.ttokttok.domain.event.SchoolEventStatus
import com.ttokttok.domain.notice.NoticeTarget
import com.ttokttok.domain.notice.TargetScope
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class SchoolEventPersistenceAdapter(private val repo: SchoolEventJpaRepository) : SchoolEventPort {
    private val json = jacksonObjectMapper()

    private data class TargetJson(val scope: String, val id: UUID?)

    override fun save(event: SchoolEvent): SchoolEvent {
        repo.save(
            SchoolEventEntity(
                id = event.id.value, institutionId = event.institutionId.value, authorId = event.authorId.value,
                title = event.title, body = event.body, location = event.location, startsAt = event.startsAt, endsAt = event.endsAt,
                targets = json.writeValueAsString(event.targets.map { TargetJson(it.scope.name, it.classroomId?.value ?: it.studentId?.value) }),
                rsvpEnabled = event.rsvpEnabled, rsvpDeadline = event.rsvpDeadline, reminderHoursBefore = event.reminderHoursBefore,
                remindedAt = event.remindedAt, status = event.status.name, createdAt = event.createdAt, updatedAt = Instant.now(),
            ),
        )
        return event
    }

    override fun find(id: SchoolEventId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()
    override fun findForUpdate(id: SchoolEventId, institutionId: InstitutionId) = repo.findForUpdate(id.value, institutionId.value)?.toDomain()
    override fun findAllByIds(ids: Collection<SchoolEventId>) = if (ids.isEmpty()) emptyList() else repo.findAllById(ids.map { it.value }).map { it.toDomain() }
    override fun lockReminderCandidates(now: Instant, limit: Int) = repo.lockReminderCandidates(now, limit).map { it.toDomain() }

    override fun search(institutionId: InstitutionId, authorId: UserId?, startsFrom: Instant?, page: Int, size: Int): PageResult<SchoolEvent> {
        val pageable = PageRequest.of(page, size)
        val result = if (startsFrom == null) repo.search(institutionId.value, authorId?.value, pageable)
        else repo.searchFrom(institutionId.value, authorId?.value, startsFrom, pageable)
        return PageResult(result.content.map { it.toDomain() }, page, size, result.totalElements)
    }

    private fun SchoolEventEntity.toDomain() = SchoolEvent(
        id = SchoolEventId(id), institutionId = InstitutionId(institutionId), authorId = UserId(authorId),
        title = title, body = body, location = location, startsAt = startsAt, endsAt = endsAt,
        targets = json.readValue<List<TargetJson>>(targets).map { t ->
            when (TargetScope.valueOf(t.scope)) {
                TargetScope.ALL -> NoticeTarget.all()
                TargetScope.CLASS -> NoticeTarget.classroom(ClassroomId(t.id!!))
                TargetScope.STUDENT -> NoticeTarget.student(StudentId(t.id!!))
            }
        },
        rsvpEnabled = rsvpEnabled, rsvpDeadline = rsvpDeadline, reminderHoursBefore = reminderHoursBefore,
        remindedAt = remindedAt, status = SchoolEventStatus.valueOf(status), createdAt = createdAt,
    )
}

@Component
class EventTargetJpaAdapter(
    private val targets: SchoolEventTargetJpaRepository,
    private val q: JpaQueries,
) : EventTargetPort {

    override fun saveTargets(eventId: SchoolEventId, studentIds: Collection<StudentId>) {
        q.insertValues(
            "insert into school_event_target (event_id, student_id)",
            studentIds.distinct().map { listOf(eventId.value, it.value) },
            "on conflict do nothing",
        )
    }

    override fun findTargetStudents(eventId: SchoolEventId): List<StudentId> = targets.findByEventId(eventId.value).map { StudentId(it.studentId) }

    override fun findEventIdsForStudents(studentIds: Collection<StudentId>, startsFrom: Instant?, limit: Int): List<SchoolEventId> {
        if (studentIds.isEmpty()) return emptyList()
        val p = params("ids" to studentIds.map { it.value })
        val from = if (startsFrom == null) "" else {
            p["from"] = startsFrom
            " and e.startsAt >= :from"
        }
        return q.jpql(
            """select e.id from SchoolEventEntity e
               where exists (select 1 from SchoolEventTargetEntity t where t.eventId = e.id and t.studentId in (:ids))$from
               order by e.startsAt ${if (startsFrom == null) "desc" else "asc"}""",
            UUID::class.java, p, limit = limit,
        ).map(::SchoolEventId)
    }
}

@Component
class RsvpResponseJpaAdapter(private val repo: EventResponseJpaRepository) : RsvpResponsePort {

    private fun EventResponseEntity.toDomain() = RsvpResponse(
        eventId = SchoolEventId(eventId), studentId = StudentId(studentId), answer = RsvpAnswer.valueOf(answer),
        reason = reason, respondedBy = UserId(respondedBy), respondedAt = respondedAt,
    )

    /** (행사, 학생) 키로 덮어쓴다 */
    override fun upsert(response: RsvpResponse) {
        repo.save(
            EventResponseEntity(
                response.eventId.value, response.studentId.value, response.answer.name, response.reason,
                response.respondedBy.value, response.respondedAt,
            ),
        )
    }

    override fun findByEvent(eventId: SchoolEventId): List<RsvpResponse> = repo.findByEventId(eventId.value).map { it.toDomain() }

    override fun findByEvents(eventIds: Collection<SchoolEventId>): List<RsvpResponse> =
        eventIds.map { it.value }.distinct().chunked(1000).flatMap { repo.findByEventIdIn(it) }.map { it.toDomain() }
}
