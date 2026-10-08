package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ttokttok.application.port.`in`.CalendarEvent
import com.ttokttok.application.port.`in`.CounselNoteView
import com.ttokttok.application.port.`in`.EventTemplateUseCase
import com.ttokttok.application.port.`in`.EventTemplateView
import com.ttokttok.application.port.`in`.HealthAlert
import com.ttokttok.application.port.`in`.HealthView
import com.ttokttok.application.port.`in`.ParentSlotView
import com.ttokttok.application.port.`in`.SlotView
import com.ttokttok.application.port.`in`.StaffAttendanceView
import com.ttokttok.application.port.`in`.TagView
import com.ttokttok.application.port.`in`.TaggedStudent
import com.ttokttok.application.port.`in`.TemplateTarget
import com.ttokttok.application.port.`in`.TimetableUseCase
import com.ttokttok.application.port.`in`.VacancyView
import com.ttokttok.application.port.`in`.WaitlistEntryView
import com.ttokttok.application.port.`in`.WaitlistStatus
import com.ttokttok.application.port.out.CounselNotePort
import com.ttokttok.application.port.out.EventCalendarPort
import com.ttokttok.application.port.out.EventTemplatePort
import com.ttokttok.application.port.out.OpsAccessPort
import com.ttokttok.application.port.out.StaffAttendancePort
import com.ttokttok.application.port.out.StudentHealthPort
import com.ttokttok.application.port.out.StudentTagPort
import com.ttokttok.application.port.out.TimetablePort
import com.ttokttok.application.port.out.WaitlistPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
private fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()

private fun params(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { p -> pairs.forEach { (k, v) -> p.addValue(k, v) } }

@Component
class OpsAccessJdbcAdapter(jdbc: JdbcTemplate) : OpsAccessPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun studentName(studentId: UUID, institutionId: InstitutionId): String? =
        named.queryForList("select name from student where id = :id and institution_id = :inst", params("id" to studentId, "inst" to institutionId.value), String::class.java).firstOrNull()

    override fun teacherCanSee(teacher: UserId, studentId: UUID): Boolean =
        named.queryForObject(
            """
            select exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                           where e.student_id = :s and e.to_date is null and ct.user_id = :u)
            """.trimIndent(),
            params("s" to studentId, "u" to teacher.value), Boolean::class.java,
        ) ?: false

    override fun classroomName(classroomId: UUID, institutionId: InstitutionId): String? =
        named.queryForList("select name from classroom where id = :id and institution_id = :inst and deleted_at is null", params("id" to classroomId, "inst" to institutionId.value), String::class.java).firstOrNull()

    override fun teacherOfClassroom(teacher: UserId, classroomId: UUID): Boolean =
        named.queryForObject("select exists (select 1 from classroom_teacher where classroom_id = :c and user_id = :u)", params("c" to classroomId, "u" to teacher.value), Boolean::class.java) ?: false
}

@Component
class StudentHealthJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : StudentHealthPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private fun dec(s: String?) = s?.let(crypto::decrypt)

    override fun find(studentId: UUID): HealthView? =
        named.query(
            """
            select h.student_id, h.allergies_enc, h.conditions_enc, h.notes_enc, h.updated_at, u.name as updated_by_name
            from student_health h join app_user u on u.id = h.updated_by where h.student_id = :id
            """.trimIndent(),
            params("id" to studentId),
            RowMapper<HealthView> { rs, _ ->
                HealthView(
                    studentId = rs.uuid("student_id"), allergies = dec(rs.getString("allergies_enc")), conditions = dec(rs.getString("conditions_enc")),
                    notes = dec(rs.getString("notes_enc")), updatedByName = rs.getString("updated_by_name"), updatedAt = rs.instant("updated_at"),
                )
            },
        ).firstOrNull()

    override fun upsert(studentId: UUID, allergies: String?, conditions: String?, notes: String?, actor: UserId, now: Instant) {
        named.update(
            """
            insert into student_health (student_id, allergies_enc, conditions_enc, notes_enc, updated_by, updated_at)
            values (:id, :a, :c, :n, :by, :now)
            on conflict (student_id) do update set allergies_enc = excluded.allergies_enc, conditions_enc = excluded.conditions_enc,
                notes_enc = excluded.notes_enc, updated_by = excluded.updated_by, updated_at = excluded.updated_at
            """.trimIndent(),
            params("id" to studentId, "a" to allergies?.let(crypto::encrypt), "c" to conditions?.let(crypto::encrypt), "n" to notes?.let(crypto::encrypt), "by" to actor.value, "now" to Timestamp.from(now)),
        )
    }

    override fun alerts(classroomId: UUID): List<HealthAlert> =
        named.query(
            """
            select s.id, s.name, h.allergies_enc
            from enrollment e join student s on s.id = e.student_id join student_health h on h.student_id = s.id
            where e.classroom_id = :c and e.to_date is null and s.status = 'ACTIVE' and h.allergies_enc is not null
            order by s.name
            """.trimIndent(),
            params("c" to classroomId),
            RowMapper<HealthAlert> { rs, _ -> HealthAlert(rs.uuid("id"), rs.getString("name"), crypto.decrypt(rs.getString("allergies_enc"))) },
        )
}

@Component
class CounselNoteJdbcAdapter(jdbc: JdbcTemplate) : CounselNotePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select n.id, n.institution_id, i.name as institution_name, n.student_id, s.name as student_name, n.author_id, u.name as author_name,
                   n.occurred_on, n.body, n.parent_visible, n.created_at
            from student_counsel_note n
            join student s on s.id = n.student_id join institution i on i.id = n.institution_id join app_user u on u.id = n.author_id
        """

        val MAPPER = RowMapper<CounselNoteView> { rs, _ ->
            CounselNoteView(
                id = rs.uuid("id"), institutionId = rs.uuid("institution_id"), institutionName = rs.getString("institution_name"),
                studentId = rs.uuid("student_id"), studentName = rs.getString("student_name"), authorId = rs.uuid("author_id"), authorName = rs.getString("author_name"),
                occurredOn = rs.getDate("occurred_on").toLocalDate(), body = rs.getString("body"), parentVisible = rs.getBoolean("parent_visible"), createdAt = rs.instant("created_at"),
            )
        }
    }

    override fun list(studentId: UUID): List<CounselNoteView> =
        named.query("$SELECT where n.student_id = :s order by n.occurred_on desc, n.created_at desc", params("s" to studentId), MAPPER)

    override fun find(id: UUID, institutionId: InstitutionId): CounselNoteView? =
        named.query("$SELECT where n.id = :id and n.institution_id = :inst", params("id" to id, "inst" to institutionId.value), MAPPER).firstOrNull()

    override fun insert(institutionId: InstitutionId, studentId: UUID, author: UserId, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """
            insert into student_counsel_note (id, institution_id, student_id, author_id, occurred_on, body, parent_visible, created_at, updated_at)
            values (:id, :inst, :s, :a, :d, :body, :v, :now, :now)
            """.trimIndent(),
            params("id" to id, "inst" to institutionId.value, "s" to studentId, "a" to author.value, "d" to java.sql.Date.valueOf(occurredOn), "body" to body, "v" to parentVisible, "now" to Timestamp.from(now)),
        )
        return id
    }

    override fun update(id: UUID, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant) {
        named.update(
            "update student_counsel_note set occurred_on = :d, body = :body, parent_visible = :v, updated_at = :now where id = :id",
            params("id" to id, "d" to java.sql.Date.valueOf(occurredOn), "body" to body, "v" to parentVisible, "now" to Timestamp.from(now)),
        )
    }

    override fun delete(id: UUID) {
        named.update("delete from student_counsel_note where id = :id", params("id" to id))
    }

    override fun listForParent(parent: UserId, studentIds: Set<UUID>?): List<CounselNoteView> {
        val p = params("p" to parent.value)
        val filter = if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            p.addValue("ids", studentIds)
            " and n.student_id in (:ids)"
        } else ""
        return named.query(
            "$SELECT where n.parent_visible and n.student_id in (select g.student_id from guardian g where g.user_id = :p)$filter order by n.occurred_on desc, n.created_at desc limit 100",
            p, MAPPER,
        )
    }
}

@Component
class StudentTagJdbcAdapter(jdbc: JdbcTemplate) : StudentTagPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select t.id, t.name, t.color, (select count(*) from student_tag_map m where m.tag_id = t.id) as cnt
            from student_tag t
        """
        val MAPPER = RowMapper<TagView> { rs, _ -> TagView(rs.uuid("id"), rs.getString("name"), rs.getString("color"), rs.getInt("cnt")) }
    }

    override fun list(institutionId: InstitutionId): List<TagView> =
        named.query("$SELECT where t.institution_id = :inst order by t.name", params("inst" to institutionId.value), MAPPER)

    override fun find(id: UUID, institutionId: InstitutionId): TagView? =
        named.query("$SELECT where t.id = :id and t.institution_id = :inst", params("id" to id, "inst" to institutionId.value), MAPPER).firstOrNull()

    override fun nameExists(institutionId: InstitutionId, name: String): Boolean =
        named.queryForObject("select exists (select 1 from student_tag where institution_id = :inst and name = :n)", params("inst" to institutionId.value, "n" to name), Boolean::class.java) ?: false

    override fun insert(institutionId: InstitutionId, name: String, color: String?, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            "insert into student_tag (id, institution_id, name, color, created_at) values (:id, :inst, :n, :c, :now)",
            params("id" to id, "inst" to institutionId.value, "n" to name, "c" to color, "now" to Timestamp.from(now)),
        )
        return id
    }

    override fun delete(id: UUID) {
        named.update("delete from student_tag where id = :id", params("id" to id)) // student_tag_map 은 on delete cascade
    }

    override fun tagsOf(studentId: UUID): List<TagView> =
        named.query("$SELECT where t.id in (select tag_id from student_tag_map where student_id = :s) order by t.name", params("s" to studentId), MAPPER)

    override fun setTags(studentId: UUID, tagIds: List<UUID>) {
        named.update("delete from student_tag_map where student_id = :s", params("s" to studentId))
        tagIds.forEach { named.update("insert into student_tag_map (student_id, tag_id) values (:s, :t)", params("s" to studentId, "t" to it)) }
    }

    override fun students(tagId: UUID, teacher: UserId?): List<TaggedStudent> {
        val p = params("t" to tagId)
        val scope = if (teacher != null) {
            p.addValue("u", teacher.value)
            """ and exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                            where e.student_id = s.id and e.to_date is null and ct.user_id = :u)"""
        } else ""
        return named.query(
            "select s.id, s.name from student_tag_map m join student s on s.id = m.student_id where m.tag_id = :t and s.status = 'ACTIVE'$scope order by s.name",
            p, RowMapper<TaggedStudent> { rs, _ -> TaggedStudent(rs.uuid("id"), rs.getString("name")) },
        )
    }

    override fun count(institutionId: InstitutionId): Int =
        named.queryForObject("select count(*) from student_tag where institution_id = :inst", params("inst" to institutionId.value), Int::class.java) ?: 0
}

@Component
class TimetableJdbcAdapter(jdbc: JdbcTemplate) : TimetablePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val COLUMNS = """
            sl.id as slot_id, sl.classroom_id, c.name as classroom_name, sl.weekday, sl.start_time, sl.end_time, sl.subject, sl.room,
            sl.teacher_id, t.name as teacher_name
        """
        const val FROM = """
            from classroom_slot sl join classroom c on c.id = sl.classroom_id and c.deleted_at is null
            left join app_user t on t.id = sl.teacher_id
        """

        fun slot(rs: ResultSet) = SlotView(
            id = rs.uuid("slot_id"), classroomId = rs.uuid("classroom_id"), classroomName = rs.getString("classroom_name"), weekday = rs.getInt("weekday"),
            startTime = rs.getTime("start_time").toLocalTime(), endTime = rs.getTime("end_time").toLocalTime(), subject = rs.getString("subject"),
            room = rs.getString("room"), teacherId = rs.uuidOrNull("teacher_id"), teacherName = rs.getString("teacher_name"),
        )
    }

    override fun list(institutionId: InstitutionId, teacher: UserId?): List<SlotView> {
        val p = params("inst" to institutionId.value)
        val scope = if (teacher != null) {
            p.addValue("u", teacher.value)
            " and exists (select 1 from classroom_teacher ct where ct.classroom_id = c.id and ct.user_id = :u)"
        } else ""
        return named.query("select $COLUMNS $FROM where c.institution_id = :inst$scope order by sl.weekday, sl.start_time, c.name", p, RowMapper<SlotView> { rs, _ -> slot(rs) })
    }

    override fun replace(classroomId: UUID, slots: List<TimetableUseCase.SlotInput>) {
        named.update("delete from classroom_slot where classroom_id = :c", params("c" to classroomId))
        slots.forEach {
            named.update(
                """
                insert into classroom_slot (id, classroom_id, weekday, start_time, end_time, subject, room, teacher_id)
                values (:id, :c, :w, :st, :et, :sub, :room, :t)
                """.trimIndent(),
                params("id" to Uuid7.next(), "c" to classroomId, "w" to it.weekday, "st" to Time.valueOf(it.startTime), "et" to Time.valueOf(it.endTime),
                    "sub" to it.subject?.trim()?.ifEmpty { null }, "room" to it.room?.trim()?.ifEmpty { null }, "t" to it.teacherId),
            )
        }
    }

    override fun isStaffOf(userId: UUID, institutionId: InstitutionId): Boolean =
        named.queryForObject(
            "select exists (select 1 from membership where user_id = :u and institution_id = :inst and role in ('OWNER','ADMIN','TEACHER'))",
            params("u" to userId, "inst" to institutionId.value), Boolean::class.java,
        ) ?: false

    override fun forParent(parent: UserId, studentIds: Set<UUID>?): List<ParentSlotView> {
        val p = params("p" to parent.value)
        val filter = if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            p.addValue("ids", studentIds)
            " and s.id in (:ids)"
        } else ""
        return named.query(
            """
            select s.id as student_id, s.name as student_name, i.name as institution_name, $COLUMNS
            from guardian g
            join student s on s.id = g.student_id and s.status = 'ACTIVE'
            join institution i on i.id = s.institution_id
            join enrollment e on e.student_id = s.id and e.to_date is null
            join classroom_slot sl on sl.classroom_id = e.classroom_id
            join classroom c on c.id = sl.classroom_id and c.deleted_at is null
            left join app_user t on t.id = sl.teacher_id
            where g.user_id = :p$filter
            order by sl.weekday, sl.start_time
            """.trimIndent(),
            p,
            RowMapper<ParentSlotView> { rs, _ -> ParentSlotView(rs.uuid("student_id"), rs.getString("student_name"), rs.getString("institution_name"), slot(rs)) },
        )
    }
}

@Component
class WaitlistJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : WaitlistPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        /** 대기 순번은 같은 반 대기 중인 건끼리 접수 순 */
        const val SELECT = """
            select * from (
                select w.id, w.classroom_id, c.name as classroom_name, w.student_name, w.guardian_name, w.phone_enc, w.memo, w.status,
                       w.contacted_at, w.created_at, w.institution_id,
                       case when w.status = 'WAITING' then row_number() over (partition by w.classroom_id, w.status order by w.created_at) end as pos
                from classroom_waitlist w join classroom c on c.id = w.classroom_id
            ) x
        """
    }

    private val mapper = RowMapper<WaitlistEntryView> { rs, _ ->
        WaitlistEntryView(
            id = rs.uuid("id"), classroomId = rs.uuid("classroom_id"), classroomName = rs.getString("classroom_name"), studentName = rs.getString("student_name"),
            guardianName = rs.getString("guardian_name"), phone = crypto.decrypt(rs.getString("phone_enc")), memo = rs.getString("memo"),
            status = WaitlistStatus.valueOf(rs.getString("status")), contactedAt = rs.instantOrNull("contacted_at"), createdAt = rs.instant("created_at"),
            position = rs.getObject("pos")?.let { (it as Number).toInt() },
        )
    }

    override fun list(institutionId: InstitutionId, classroomId: UUID?, status: WaitlistStatus?): List<WaitlistEntryView> {
        val p = params("inst" to institutionId.value)
        val sb = StringBuilder("$SELECT where x.institution_id = :inst")
        if (classroomId != null) { sb.append(" and x.classroom_id = :c"); p.addValue("c", classroomId) }
        if (status != null) { sb.append(" and x.status = :st"); p.addValue("st", status.name) }
        sb.append(" order by x.classroom_name, x.created_at")
        return named.query(sb.toString(), p, mapper)
    }

    override fun find(id: UUID, institutionId: InstitutionId): WaitlistEntryView? =
        named.query("$SELECT where x.id = :id and x.institution_id = :inst", params("id" to id, "inst" to institutionId.value), mapper).firstOrNull()

    override fun insert(institutionId: InstitutionId, classroomId: UUID, studentName: String, guardianName: String?, phone: String, memo: String?, creator: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """
            insert into classroom_waitlist (id, institution_id, classroom_id, student_name, guardian_name, phone_enc, phone_last4, memo, created_by, created_at, updated_at)
            values (:id, :inst, :c, :sn, :gn, :enc, :last4, :memo, :by, :now, :now)
            """.trimIndent(),
            params("id" to id, "inst" to institutionId.value, "c" to classroomId, "sn" to studentName, "gn" to guardianName, "enc" to crypto.encrypt(phone),
                "last4" to phone.takeLast(4), "memo" to memo, "by" to creator.value, "now" to Timestamp.from(now)),
        )
        return id
    }

    override fun setStatus(id: UUID, status: WaitlistStatus, now: Instant) {
        named.update("update classroom_waitlist set status = :st, updated_at = :now where id = :id", params("id" to id, "st" to status.name, "now" to Timestamp.from(now)))
    }

    override fun markContacted(id: UUID, now: Instant) {
        named.update("update classroom_waitlist set contacted_at = :now, updated_at = :now where id = :id", params("id" to id, "now" to Timestamp.from(now)))
    }

    override fun vacancies(institutionId: InstitutionId): List<VacancyView> =
        named.query(
            """
            select c.id, c.name, c.capacity,
                   (select count(*) from enrollment e join student s on s.id = e.student_id
                    where e.classroom_id = c.id and e.to_date is null and s.status = 'ACTIVE') as enrolled,
                   (select count(*) from classroom_waitlist w where w.classroom_id = c.id and w.status = 'WAITING') as waiting
            from classroom c where c.institution_id = :inst and c.deleted_at is null order by c.name
            """.trimIndent(),
            params("inst" to institutionId.value),
            RowMapper<VacancyView> { rs, _ -> VacancyView(rs.uuid("id"), rs.getString("name"), rs.getInt("capacity"), rs.getInt("enrolled"), rs.getInt("waiting")) },
        )

    override fun waitingCount(classroomId: UUID): Int =
        named.queryForObject("select count(*) from classroom_waitlist where classroom_id = :c and status = 'WAITING'", params("c" to classroomId), Int::class.java) ?: 0
}

@Component
class StaffAttendanceJdbcAdapter(jdbc: JdbcTemplate) : StaffAttendancePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select a.id, a.user_id, u.name as user_name, a.work_date, a.check_in, a.check_out
            from staff_attendance a join app_user u on u.id = a.user_id
        """
        val MAPPER = RowMapper<StaffAttendanceView> { rs, _ ->
            StaffAttendanceView(rs.uuid("id"), rs.uuid("user_id"), rs.getString("user_name"), rs.getDate("work_date").toLocalDate(), rs.instant("check_in"), rs.instantOrNull("check_out"))
        }
    }

    override fun find(institutionId: InstitutionId, userId: UserId, date: LocalDate): StaffAttendanceView? =
        named.query(
            "$SELECT where a.institution_id = :inst and a.user_id = :u and a.work_date = :d",
            params("inst" to institutionId.value, "u" to userId.value, "d" to java.sql.Date.valueOf(date)), MAPPER,
        ).firstOrNull()

    override fun insert(institutionId: InstitutionId, userId: UserId, date: LocalDate, checkIn: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            "insert into staff_attendance (id, institution_id, user_id, work_date, check_in) values (:id, :inst, :u, :d, :ci)",
            params("id" to id, "inst" to institutionId.value, "u" to userId.value, "d" to java.sql.Date.valueOf(date), "ci" to Timestamp.from(checkIn)),
        )
        return id
    }

    override fun checkOut(id: UUID, at: Instant) {
        named.update("update staff_attendance set check_out = :at where id = :id", params("id" to id, "at" to Timestamp.from(at)))
    }

    override fun between(institutionId: InstitutionId, user: UserId?, from: LocalDate, to: LocalDate): List<StaffAttendanceView> {
        val p = params("inst" to institutionId.value, "f" to java.sql.Date.valueOf(from), "t" to java.sql.Date.valueOf(to))
        val scope = if (user != null) {
            p.addValue("u", user.value)
            " and a.user_id = :u"
        } else ""
        return named.query("$SELECT where a.institution_id = :inst and a.work_date between :f and :t$scope order by u.name, a.work_date", p, MAPPER)
    }
}

@Component
class EventTemplateJdbcAdapter(jdbc: JdbcTemplate) : EventTemplatePort {
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val json = jacksonObjectMapper()

    private companion object {
        const val SELECT = """
            select t.id, t.name, t.title, t.body, t.location, t.start_time, t.duration_minutes, t.targets::text as targets, t.rsvp_enabled,
                   t.rsvp_deadline_days_before, t.reminder_hours_before, u.name as created_by_name, t.updated_at
            from event_template t join app_user u on u.id = t.created_by
        """
    }

    private val mapper = RowMapper<EventTemplateView> { rs, _ ->
        val raw: List<Map<String, String?>> = json.readValue(rs.getString("targets"), object : TypeReference<List<Map<String, String?>>>() {})
        EventTemplateView(
            id = rs.uuid("id"), name = rs.getString("name"), title = rs.getString("title"), body = rs.getString("body"), location = rs.getString("location"),
            startTime = rs.getTime("start_time").toLocalTime(), durationMinutes = rs.getInt("duration_minutes"),
            targets = raw.map { TemplateTarget(it["scope"] ?: "ALL", it["id"]?.let(UUID::fromString)) },
            rsvpEnabled = rs.getBoolean("rsvp_enabled"),
            rsvpDeadlineDaysBefore = rs.getObject("rsvp_deadline_days_before")?.let { (it as Number).toInt() },
            reminderHoursBefore = rs.getObject("reminder_hours_before")?.let { (it as Number).toInt() },
            createdByName = rs.getString("created_by_name"), updatedAt = rs.instant("updated_at"),
        )
    }

    private fun targetsJson(c: EventTemplateUseCase.Command) = json.writeValueAsString(c.targets.map { mapOf("scope" to it.scope, "id" to it.id?.toString()) })

    override fun list(institutionId: InstitutionId): List<EventTemplateView> =
        named.query("$SELECT where t.institution_id = :inst order by t.name", params("inst" to institutionId.value), mapper)

    override fun find(id: UUID, institutionId: InstitutionId): EventTemplateView? =
        named.query("$SELECT where t.id = :id and t.institution_id = :inst", params("id" to id, "inst" to institutionId.value), mapper).firstOrNull()

    override fun insert(institutionId: InstitutionId, creator: UserId, command: EventTemplateUseCase.Command, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """
            insert into event_template (id, institution_id, name, title, body, location, start_time, duration_minutes, targets, rsvp_enabled,
                                        rsvp_deadline_days_before, reminder_hours_before, created_by, created_at, updated_at)
            values (:id, :inst, :name, :title, :body, :loc, :st, :dur, cast(:targets as jsonb), :rsvp, :dl, :rem, :by, :now, :now)
            """.trimIndent(),
            params("id" to id, "inst" to institutionId.value, "name" to command.name.trim(), "title" to command.title.trim(), "body" to command.body?.trim()?.ifEmpty { null },
                "loc" to command.location?.trim()?.ifEmpty { null }, "st" to Time.valueOf(command.startTime), "dur" to command.durationMinutes, "targets" to targetsJson(command),
                "rsvp" to command.rsvpEnabled, "dl" to command.rsvpDeadlineDaysBefore, "rem" to command.reminderHoursBefore, "by" to creator.value, "now" to Timestamp.from(now)),
        )
        return id
    }

    override fun update(id: UUID, command: EventTemplateUseCase.Command, now: Instant) {
        named.update(
            """
            update event_template set name = :name, title = :title, body = :body, location = :loc, start_time = :st, duration_minutes = :dur,
                targets = cast(:targets as jsonb), rsvp_enabled = :rsvp, rsvp_deadline_days_before = :dl, reminder_hours_before = :rem, updated_at = :now
            where id = :id
            """.trimIndent(),
            params("id" to id, "name" to command.name.trim(), "title" to command.title.trim(), "body" to command.body?.trim()?.ifEmpty { null },
                "loc" to command.location?.trim()?.ifEmpty { null }, "st" to Time.valueOf(command.startTime), "dur" to command.durationMinutes, "targets" to targetsJson(command),
                "rsvp" to command.rsvpEnabled, "dl" to command.rsvpDeadlineDaysBefore, "rem" to command.reminderHoursBefore, "now" to Timestamp.from(now)),
        )
    }

    override fun delete(id: UUID) {
        named.update("delete from event_template where id = :id", params("id" to id))
    }

    override fun count(institutionId: InstitutionId): Int =
        named.queryForObject("select count(*) from event_template where institution_id = :inst", params("inst" to institutionId.value), Int::class.java) ?: 0
}

@Component
class EventCalendarJdbcAdapter(jdbc: JdbcTemplate) : EventCalendarPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun between(institutionId: InstitutionId, from: Instant, to: Instant): List<CalendarEvent> =
        named.query(
            """
            select id, title, starts_at, ends_at, location, status, rsvp_enabled from school_event
            where institution_id = :inst and starts_at >= :f and starts_at < :t order by starts_at
            """.trimIndent(),
            params("inst" to institutionId.value, "f" to Timestamp.from(from), "t" to Timestamp.from(to)),
            RowMapper<CalendarEvent> { rs, _ ->
                CalendarEvent(rs.uuid("id"), rs.getString("title"), rs.instant("starts_at"), rs.instantOrNull("ends_at"), rs.getString("location"), rs.getString("status"), rs.getBoolean("rsvp_enabled"))
            },
        )
}
