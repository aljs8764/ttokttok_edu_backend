package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ttokttok.adapter.out.persistence.entity.ClassroomSlotEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomTeacherKey
import com.ttokttok.adapter.out.persistence.entity.ClassroomWaitlistEntity
import com.ttokttok.adapter.out.persistence.entity.EventTemplateEntity
import com.ttokttok.adapter.out.persistence.entity.StaffAttendanceEntity
import com.ttokttok.adapter.out.persistence.entity.StudentCounselNoteEntity
import com.ttokttok.adapter.out.persistence.entity.StudentHealthEntity
import com.ttokttok.adapter.out.persistence.entity.StudentTagEntity
import com.ttokttok.adapter.out.persistence.entity.StudentTagMapEntity
import com.ttokttok.adapter.out.persistence.repository.ClassroomJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomSlotJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomTeacherJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomWaitlistJpaRepository
import com.ttokttok.adapter.out.persistence.repository.EventTemplateJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SchoolEventJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StaffAttendanceJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentCounselNoteJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentHealthJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentTagJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentTagMapJpaRepository
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 교사 담당 반의 현재 원생인지 (s = 원생 별칭, :u = 교사) */
private const val TEACHES_STUDENT = """
    exists (select 1 from EnrollmentEntity e, ClassroomTeacherEntity ct
             where ct.classroomId = e.classroomId and e.studentId = s.id and e.toDate is null and ct.userId = :u)
"""

@Component
class OpsAccessJpaAdapter(
    private val students: StudentJpaRepository,
    private val classrooms: ClassroomJpaRepository,
    private val classroomTeachers: ClassroomTeacherJpaRepository,
    private val q: JpaQueries,
) : OpsAccessPort {

    override fun studentName(studentId: UUID, institutionId: InstitutionId): String? = students.findByIdAndInstitutionId(studentId, institutionId.value)?.name

    override fun teacherCanSee(teacher: UserId, studentId: UUID): Boolean =
        q.jpqlCount("select count(s) from StudentEntity s where s.id = :s and $TEACHES_STUDENT", params("s" to studentId, "u" to teacher.value)) > 0

    override fun classroomName(classroomId: UUID, institutionId: InstitutionId): String? =
        classrooms.findByIdAndInstitutionIdAndDeletedAtIsNull(classroomId, institutionId.value)?.name

    override fun teacherOfClassroom(teacher: UserId, classroomId: UUID): Boolean = classroomTeachers.existsById(ClassroomTeacherKey(classroomId, teacher.value))
}

@Component
class StudentHealthJpaAdapter(private val repo: StudentHealthJpaRepository, private val crypto: FieldCrypto) : StudentHealthPort {

    private fun dec(s: String?) = s?.let(crypto::decrypt)

    override fun find(studentId: UUID): HealthView? =
        repo.findView(studentId).firstOrNull()?.let { r ->
            val h = r[0] as StudentHealthEntity
            HealthView(
                studentId = h.studentId, allergies = dec(h.allergiesEnc), conditions = dec(h.conditionsEnc),
                notes = dec(h.notesEnc), updatedByName = r[1] as String, updatedAt = h.updatedAt,
            )
        }

    override fun upsert(studentId: UUID, allergies: String?, conditions: String?, notes: String?, actor: UserId, now: Instant) {
        repo.save(
            StudentHealthEntity(
                studentId, allergies?.let(crypto::encrypt), conditions?.let(crypto::encrypt), notes?.let(crypto::encrypt), actor.value, now,
            ),
        )
    }

    override fun alerts(classroomId: UUID): List<HealthAlert> =
        repo.allergyRows(classroomId).map { HealthAlert(it[0] as UUID, it[1] as String, crypto.decrypt(it[2] as String)) }
}

@Component
class CounselNoteJpaAdapter(private val repo: StudentCounselNoteJpaRepository, private val q: JpaQueries) : CounselNotePort {

    private companion object {
        const val SELECT = """
            select n, i.name, s.name, u.name from StudentCounselNoteEntity n
              join StudentEntity s on s.id = n.studentId join InstitutionEntity i on i.id = n.institutionId join UserEntity u on u.id = n.authorId
        """

        fun view(r: Array<Any?>): CounselNoteView {
            val n = r[0] as StudentCounselNoteEntity
            return CounselNoteView(
                id = n.id, institutionId = n.institutionId, institutionName = r[1] as String,
                studentId = n.studentId, studentName = r[2] as String, authorId = n.authorId, authorName = r[3] as String,
                occurredOn = n.occurredOn, body = n.body, parentVisible = n.parentVisible, createdAt = n.createdAt,
            )
        }
    }

    override fun list(studentId: UUID): List<CounselNoteView> =
        q.jpqlRows("$SELECT where n.studentId = :s order by n.occurredOn desc, n.createdAt desc", params("s" to studentId)).map(::view)

    override fun find(id: UUID, institutionId: InstitutionId): CounselNoteView? =
        q.jpqlRows("$SELECT where n.id = :id and n.institutionId = :inst", params("id" to id, "inst" to institutionId.value)).firstOrNull()?.let(::view)

    override fun insert(institutionId: InstitutionId, studentId: UUID, author: UserId, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant): UUID {
        val id = Uuid7.next()
        repo.save(StudentCounselNoteEntity(id, institutionId.value, studentId, author.value, occurredOn, body, parentVisible, now, now))
        return id
    }

    override fun update(id: UUID, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.occurredOn = occurredOn
        e.body = body
        e.parentVisible = parentVisible
        e.updatedAt = now
        repo.save(e)
    }

    override fun delete(id: UUID) {
        if (repo.existsById(id)) repo.deleteById(id)
    }

    override fun listForParent(parent: UserId, studentIds: Set<UUID>?): List<CounselNoteView> {
        val p = params("p" to parent.value)
        val filter = if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            p["ids"] = studentIds
            " and n.studentId in (:ids)"
        } else ""
        return q.jpqlRows(
            "$SELECT where n.parentVisible = true and n.studentId in (select g.studentId from GuardianEntity g where g.userId = :p)$filter order by n.occurredOn desc, n.createdAt desc",
            p, limit = 100,
        ).map(::view)
    }
}

@Component
class StudentTagJpaAdapter(
    private val tags: StudentTagJpaRepository,
    private val maps: StudentTagMapJpaRepository,
    private val q: JpaQueries,
) : StudentTagPort {

    private companion object {
        const val SELECT = "select t, (select count(m) from StudentTagMapEntity m where m.tagId = t.id) from StudentTagEntity t"

        fun view(r: Array<Any?>): TagView {
            val t = r[0] as StudentTagEntity
            return TagView(t.id, t.name, t.color, (r[1] as Number).toInt())
        }
    }

    override fun list(institutionId: InstitutionId): List<TagView> =
        q.jpqlRows("$SELECT where t.institutionId = :inst order by t.name", params("inst" to institutionId.value)).map(::view)

    override fun find(id: UUID, institutionId: InstitutionId): TagView? =
        q.jpqlRows("$SELECT where t.id = :id and t.institutionId = :inst", params("id" to id, "inst" to institutionId.value)).firstOrNull()?.let(::view)

    override fun nameExists(institutionId: InstitutionId, name: String): Boolean = tags.existsByInstitutionIdAndName(institutionId.value, name)

    override fun insert(institutionId: InstitutionId, name: String, color: String?, now: Instant): UUID {
        val id = Uuid7.next()
        tags.save(StudentTagEntity(id, institutionId.value, name, color, now))
        return id
    }

    override fun delete(id: UUID) {
        tags.deleteTag(id) // student_tag_map 은 on delete cascade
    }

    override fun tagsOf(studentId: UUID): List<TagView> =
        q.jpqlRows("$SELECT where t.id in (select m.tagId from StudentTagMapEntity m where m.studentId = :s) order by t.name", params("s" to studentId)).map(::view)

    override fun setTags(studentId: UUID, tagIds: List<UUID>) {
        maps.deleteByStudent(studentId)
        tagIds.forEach { q.persist(StudentTagMapEntity(studentId, it)) }
    }

    override fun students(tagId: UUID, teacher: UserId?): List<TaggedStudent> {
        val p = params("t" to tagId)
        val scope = if (teacher != null) {
            p["u"] = teacher.value
            " and $TEACHES_STUDENT"
        } else ""
        return q.jpqlRows(
            "select s.id, s.name from StudentTagMapEntity m join StudentEntity s on s.id = m.studentId where m.tagId = :t and s.status = 'ACTIVE'$scope order by s.name",
            p,
        ).map { TaggedStudent(it[0] as UUID, it[1] as String) }
    }

    override fun count(institutionId: InstitutionId): Int = tags.countByInstitutionId(institutionId.value).toInt()
}

@Component
class TimetableJpaAdapter(
    private val slots: ClassroomSlotJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
) : TimetablePort {

    private companion object {
        /** 삭제되지 않은 반의 시간표 칸 + 반 이름 + 담당 교사 이름 */
        const val FROM = """
            from ClassroomSlotEntity sl join ClassroomEntity c on c.id = sl.classroomId and c.deletedAt is null
            left join UserEntity t on t.id = sl.teacherId
        """

        /** r[at] = slot, r[at+1] = 반 이름, r[at+2] = 교사 이름 */
        fun slot(r: Array<Any?>, at: Int = 0): SlotView {
            val sl = r[at] as ClassroomSlotEntity
            return SlotView(
                id = sl.id, classroomId = sl.classroomId, classroomName = r[at + 1] as String, weekday = sl.weekday.toInt(),
                startTime = sl.startTime, endTime = sl.endTime, subject = sl.subject,
                room = sl.room, teacherId = sl.teacherId, teacherName = r[at + 2] as String?,
            )
        }
    }

    override fun list(institutionId: InstitutionId, teacher: UserId?): List<SlotView> {
        val p = params("inst" to institutionId.value)
        val scope = if (teacher != null) {
            p["u"] = teacher.value
            " and exists (select 1 from ClassroomTeacherEntity ct where ct.classroomId = c.id and ct.userId = :u)"
        } else ""
        return q.jpqlRows("select sl, c.name, t.name $FROM where c.institutionId = :inst$scope order by sl.weekday, sl.startTime, c.name", p).map { slot(it) }
    }

    override fun replace(classroomId: UUID, slots: List<TimetableUseCase.SlotInput>) {
        this.slots.deleteByClassroom(classroomId)
        slots.forEach {
            q.persist(
                ClassroomSlotEntity(
                    Uuid7.next(), classroomId, it.weekday.toShort(), it.startTime, it.endTime,
                    it.subject?.trim()?.ifEmpty { null }, it.room?.trim()?.ifEmpty { null }, it.teacherId,
                ),
            )
        }
    }

    override fun isStaffOf(userId: UUID, institutionId: InstitutionId): Boolean =
        memberships.existsByUserIdAndInstitutionIdAndRoleIn(userId, institutionId.value, listOf("OWNER", "ADMIN", "TEACHER"))

    override fun forParent(parent: UserId, studentIds: Set<UUID>?): List<ParentSlotView> {
        val p = params("p" to parent.value)
        val filter = if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            p["ids"] = studentIds
            " and s.id in (:ids)"
        } else ""
        return q.jpqlRows(
            """
            select s.id, s.name, i.name, sl, c.name, t.name
            from GuardianEntity g
            join StudentEntity s on s.id = g.studentId and s.status = 'ACTIVE'
            join InstitutionEntity i on i.id = s.institutionId
            join EnrollmentEntity e on e.studentId = s.id and e.toDate is null
            join ClassroomSlotEntity sl on sl.classroomId = e.classroomId
            join ClassroomEntity c on c.id = sl.classroomId and c.deletedAt is null
            left join UserEntity t on t.id = sl.teacherId
            where g.userId = :p$filter
            order by sl.weekday, sl.startTime
            """.trimIndent(),
            p,
        ).map { ParentSlotView(it[0] as UUID, it[1] as String, it[2] as String, slot(it, 3)) }
    }
}

@Component
class WaitlistJpaAdapter(
    private val repo: ClassroomWaitlistJpaRepository,
    private val q: JpaQueries,
    private val crypto: FieldCrypto,
) : WaitlistPort {

    private companion object {
        /** 대기 순번은 같은 반 대기 중인 건끼리 접수 순 (row_number() 창 함수라 네이티브) */
        const val SELECT = """
            select * from (
                select w.id, w.classroom_id, c.name as classroom_name, w.student_name, w.guardian_name, w.phone_enc, w.memo, w.status,
                       w.contacted_at, w.created_at, w.institution_id,
                       case when w.status = 'WAITING' then row_number() over (partition by w.classroom_id, w.status order by w.created_at) end as pos
                from classroom_waitlist w join classroom c on c.id = w.classroom_id
            ) x
        """
    }

    private fun view(r: Row) = WaitlistEntryView(
        id = r.uuid("id"), classroomId = r.uuid("classroom_id"), classroomName = r.str("classroom_name"), studentName = r.str("student_name"),
        guardianName = r.strOrNull("guardian_name"), phone = crypto.decrypt(r.str("phone_enc")), memo = r.strOrNull("memo"),
        status = WaitlistStatus.valueOf(r.str("status")), contactedAt = r.instantOrNull("contacted_at"), createdAt = r.instant("created_at"),
        position = r.intOrNull("pos"),
    )

    override fun list(institutionId: InstitutionId, classroomId: UUID?, status: WaitlistStatus?): List<WaitlistEntryView> {
        val p = params("inst" to institutionId.value)
        val sb = StringBuilder("$SELECT where x.institution_id = :inst")
        if (classroomId != null) { sb.append(" and x.classroom_id = :c"); p["c"] = classroomId }
        if (status != null) { sb.append(" and x.status = :st"); p["st"] = status.name }
        sb.append(" order by x.classroom_name, x.created_at")
        return q.rows(sb.toString(), p).map(::view)
    }

    override fun find(id: UUID, institutionId: InstitutionId): WaitlistEntryView? =
        q.row("$SELECT where x.id = :id and x.institution_id = :inst", params("id" to id, "inst" to institutionId.value))?.let(::view)

    override fun insert(institutionId: InstitutionId, classroomId: UUID, studentName: String, guardianName: String?, phone: String, memo: String?, creator: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        repo.save(
            ClassroomWaitlistEntity(
                id, institutionId.value, classroomId, studentName, guardianName, crypto.encrypt(phone), phone.takeLast(4), memo,
                createdBy = creator.value, createdAt = now, updatedAt = now,
            ),
        )
        return id
    }

    override fun setStatus(id: UUID, status: WaitlistStatus, now: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.status = status.name
        e.updatedAt = now
        repo.save(e)
    }

    override fun markContacted(id: UUID, now: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.contactedAt = now
        e.updatedAt = now
        repo.save(e)
    }

    override fun vacancies(institutionId: InstitutionId): List<VacancyView> =
        q.jpqlRows(
            """
            select c.id, c.name, c.capacity,
                   (select count(e) from EnrollmentEntity e, StudentEntity s
                     where s.id = e.studentId and e.classroomId = c.id and e.toDate is null and s.status = 'ACTIVE'),
                   (select count(w) from ClassroomWaitlistEntity w where w.classroomId = c.id and w.status = 'WAITING')
            from ClassroomEntity c where c.institutionId = :inst and c.deletedAt is null order by c.name
            """.trimIndent(),
            params("inst" to institutionId.value),
        ).map { VacancyView(it[0] as UUID, it[1] as String, it[2] as Int, (it[3] as Number).toInt(), (it[4] as Number).toInt()) }

    override fun waitingCount(classroomId: UUID): Int = repo.countByClassroomIdAndStatus(classroomId, "WAITING").toInt()
}

@Component
class StaffAttendanceJpaAdapter(private val repo: StaffAttendanceJpaRepository, private val q: JpaQueries) : StaffAttendancePort {

    private companion object {
        const val SELECT = "select a, u.name from StaffAttendanceEntity a join UserEntity u on u.id = a.userId"

        fun view(r: Array<Any?>): StaffAttendanceView {
            val a = r[0] as StaffAttendanceEntity
            return StaffAttendanceView(a.id, a.userId, r[1] as String, a.workDate, a.checkIn, a.checkOut)
        }
    }

    override fun find(institutionId: InstitutionId, userId: UserId, date: LocalDate): StaffAttendanceView? =
        q.jpqlRows(
            "$SELECT where a.institutionId = :inst and a.userId = :u and a.workDate = :d",
            params("inst" to institutionId.value, "u" to userId.value, "d" to date),
        ).firstOrNull()?.let(::view)

    override fun insert(institutionId: InstitutionId, userId: UserId, date: LocalDate, checkIn: Instant): UUID {
        val id = Uuid7.next()
        repo.save(StaffAttendanceEntity(id, institutionId.value, userId.value, date, checkIn))
        return id
    }

    override fun checkOut(id: UUID, at: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.checkOut = at
        repo.save(e)
    }

    override fun between(institutionId: InstitutionId, user: UserId?, from: LocalDate, to: LocalDate): List<StaffAttendanceView> {
        val p = params("inst" to institutionId.value, "f" to from, "t" to to)
        val scope = if (user != null) {
            p["u"] = user.value
            " and a.userId = :u"
        } else ""
        return q.jpqlRows("$SELECT where a.institutionId = :inst and a.workDate between :f and :t$scope order by u.name, a.workDate", p).map(::view)
    }
}

@Component
class EventTemplateJpaAdapter(private val repo: EventTemplateJpaRepository) : EventTemplatePort {
    private val json = jacksonObjectMapper()

    private fun view(r: Array<Any?>): EventTemplateView {
        val t = r[0] as EventTemplateEntity
        val raw: List<Map<String, String?>> = json.readValue(t.targets, object : TypeReference<List<Map<String, String?>>>() {})
        return EventTemplateView(
            id = t.id, name = t.name, title = t.title, body = t.body, location = t.location,
            startTime = t.startTime, durationMinutes = t.durationMinutes,
            targets = raw.map { TemplateTarget(it["scope"] ?: "ALL", it["id"]?.let(UUID::fromString)) },
            rsvpEnabled = t.rsvpEnabled,
            rsvpDeadlineDaysBefore = t.rsvpDeadlineDaysBefore,
            reminderHoursBefore = t.reminderHoursBefore,
            createdByName = r[1] as String, updatedAt = t.updatedAt,
        )
    }

    private fun targetsJson(c: EventTemplateUseCase.Command) = json.writeValueAsString(c.targets.map { mapOf("scope" to it.scope, "id" to it.id?.toString()) })

    private fun EventTemplateEntity.fill(command: EventTemplateUseCase.Command, now: Instant) {
        name = command.name.trim()
        title = command.title.trim()
        body = command.body?.trim()?.ifEmpty { null }
        location = command.location?.trim()?.ifEmpty { null }
        startTime = command.startTime
        durationMinutes = command.durationMinutes
        targets = targetsJson(command)
        rsvpEnabled = command.rsvpEnabled
        rsvpDeadlineDaysBefore = command.rsvpDeadlineDaysBefore
        reminderHoursBefore = command.reminderHoursBefore
        updatedAt = now
    }

    override fun list(institutionId: InstitutionId): List<EventTemplateView> = repo.findViews(institutionId.value).map(::view)

    override fun find(id: UUID, institutionId: InstitutionId): EventTemplateView? = repo.findView(id, institutionId.value).firstOrNull()?.let(::view)

    override fun insert(institutionId: InstitutionId, creator: UserId, command: EventTemplateUseCase.Command, now: Instant): UUID {
        val id = Uuid7.next()
        val e = EventTemplateEntity(
            id, institutionId.value, "", "", null, null, LocalTime.MIDNIGHT, 60, "[]", false, null, null, creator.value, now, now,
        )
        e.fill(command, now)
        repo.save(e)
        return id
    }

    override fun update(id: UUID, command: EventTemplateUseCase.Command, now: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.fill(command, now)
        repo.save(e)
    }

    override fun delete(id: UUID) {
        if (repo.existsById(id)) repo.deleteById(id)
    }

    override fun count(institutionId: InstitutionId): Int = repo.countByInstitutionId(institutionId.value).toInt()
}

@Component
class EventCalendarJpaAdapter(private val events: SchoolEventJpaRepository) : EventCalendarPort {

    override fun between(institutionId: InstitutionId, from: Instant, to: Instant): List<CalendarEvent> =
        events.findByInstitutionIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(institutionId.value, from, to).map {
            CalendarEvent(it.id, it.title, it.startsAt, it.endsAt, it.location, it.status, it.rsvpEnabled)
        }
}
