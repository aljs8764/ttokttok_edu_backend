package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.SchoolEventEntity
import com.ttokttok.adapter.out.persistence.repository.SchoolEventJpaRepository
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
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
class EventTargetJdbcAdapter(jdbc: JdbcTemplate) : EventTargetPort {
    private val plain = jdbc
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun saveTargets(eventId: SchoolEventId, studentIds: Collection<StudentId>) {
        studentIds.distinct().chunked(500).forEach { chunk ->
            plain.batchUpdate(
                "insert into school_event_target (event_id, student_id) values (?, ?) on conflict do nothing",
                chunk.map { arrayOf<Any>(eventId.value, it.value) },
            )
        }
    }

    override fun findTargetStudents(eventId: SchoolEventId): List<StudentId> =
        plain.query("select student_id from school_event_target where event_id = ?", { rs, _ -> StudentId(rs.getObject(1, UUID::class.java)) }, eventId.value)

    override fun findEventIdsForStudents(studentIds: Collection<StudentId>, startsFrom: Instant?, limit: Int): List<SchoolEventId> {
        if (studentIds.isEmpty()) return emptyList()
        val params = MapSqlParameterSource().addValue("ids", studentIds.map { it.value }).addValue("limit", limit)
        val from = if (startsFrom == null) "" else {
            params.addValue("from", Timestamp.from(startsFrom))
            " and e.starts_at >= :from"
        }
        return named.query(
            """select e.id from school_event e
               where exists (select 1 from school_event_target t where t.event_id = e.id and t.student_id in (:ids))$from
               order by e.starts_at ${if (startsFrom == null) "desc" else "asc"} limit :limit""",
            params,
        ) { rs, _ -> SchoolEventId(rs.getObject("id", UUID::class.java)) }
    }
}

@Component
class RsvpResponseJdbcAdapter(jdbc: JdbcTemplate) : RsvpResponsePort {
    private val plain = jdbc
    private val named = NamedParameterJdbcTemplate(jdbc)

    private val mapper = RowMapper { rs, _ ->
        RsvpResponse(
            eventId = SchoolEventId(rs.getObject("event_id", UUID::class.java)),
            studentId = StudentId(rs.getObject("student_id", UUID::class.java)),
            answer = RsvpAnswer.valueOf(rs.getString("answer")),
            reason = rs.getString("reason"),
            respondedBy = UserId(rs.getObject("responded_by", UUID::class.java)),
            respondedAt = rs.getTimestamp("responded_at").toInstant(),
        )
    }

    override fun upsert(response: RsvpResponse) {
        plain.update(
            """insert into event_response (event_id, student_id, answer, reason, responded_by, responded_at)
               values (?, ?, ?, ?, ?, ?)
               on conflict (event_id, student_id) do update set
                   answer = excluded.answer, reason = excluded.reason,
                   responded_by = excluded.responded_by, responded_at = excluded.responded_at""",
            response.eventId.value, response.studentId.value, response.answer.name, response.reason,
            response.respondedBy.value, Timestamp.from(response.respondedAt),
        )
    }

    override fun findByEvent(eventId: SchoolEventId): List<RsvpResponse> =
        plain.query("select * from event_response where event_id = ?", mapper, eventId.value)

    override fun findByEvents(eventIds: Collection<SchoolEventId>): List<RsvpResponse> =
        eventIds.map { it.value }.distinct().chunked(1000).flatMap { ids ->
            named.query("select * from event_response where event_id in (:ids)", MapSqlParameterSource("ids", ids), mapper)
        }
}
