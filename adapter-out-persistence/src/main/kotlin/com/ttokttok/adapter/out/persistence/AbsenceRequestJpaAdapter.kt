package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.AbsenceRequestEntity
import com.ttokttok.adapter.out.persistence.repository.AbsenceRequestJpaRepository
import com.ttokttok.adapter.out.persistence.repository.EnrollmentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.application.port.`in`.AbsenceKind
import com.ttokttok.application.port.`in`.AbsenceRequestStatus
import com.ttokttok.application.port.`in`.AbsenceRequestView
import com.ttokttok.application.port.out.AbsenceRequestPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 결석·지각·조퇴 사전 신청 (ATT-007) */
@Component
class AbsenceRequestJpaAdapter(
    private val repo: AbsenceRequestJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val enrollments: EnrollmentJpaRepository,
    private val q: JpaQueries,
) : AbsenceRequestPort {

    private companion object {
        const val SELECT = """
            select r, i.name, s.name, g.name from AbsenceRequestEntity r
            join InstitutionEntity i on i.id = r.institutionId
            join StudentEntity s on s.id = r.studentId
            join UserEntity g on g.id = r.guardianUserId
        """

        const val TEACHES = """
            exists (select 1 from EnrollmentEntity e, ClassroomTeacherEntity ct
                     where ct.classroomId = e.classroomId and e.studentId = r.studentId and e.toDate is null and ct.userId = :teacher)
        """

        fun view(row: Array<Any?>): AbsenceRequestView {
            val r = row[0] as AbsenceRequestEntity
            return AbsenceRequestView(
                id = r.id, institutionId = r.institutionId, institutionName = row[1] as String,
                studentId = r.studentId, studentName = row[2] as String, guardianName = row[3] as String,
                date = r.date, kind = AbsenceKind.valueOf(r.kind), expectedTime = r.expectedTime, reason = r.reason,
                status = AbsenceRequestStatus.valueOf(r.status), acknowledgedAt = r.acknowledgedAt, createdAt = r.createdAt,
            )
        }
    }

    override fun insert(
        institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, date: LocalDate, kind: AbsenceKind,
        expectedTime: LocalTime?, reason: String, now: Instant,
    ): UUID? {
        val id = Uuid7.next()
        val n = if (expectedTime != null) {
            repo.insertIfNoActive(id, institutionId.value, studentId, guardianUserId.value, date, kind.name, expectedTime, reason, now)
        } else {
            repo.insertIfNoActiveWithoutTime(id, institutionId.value, studentId, guardianUserId.value, date, kind.name, reason, now)
        }
        return if (n == 1) id else null
    }

    override fun find(id: UUID): AbsenceRequestView? = repo.findView(id).firstOrNull()?.let(::view)

    override fun guardianOf(id: UUID): UUID? = repo.findByIdOrNull(id)?.guardianUserId

    override fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, fromDate: LocalDate?, limit: Int): List<AbsenceRequestView> {
        val p = params("guardian" to guardianUserId.value)
        val where = StringBuilder(" where r.guardianUserId = :guardian")
        if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            where.append(" and r.studentId in (:students)")
            p["students"] = studentIds
        }
        if (fromDate != null) {
            where.append(" and r.date >= :from")
            p["from"] = fromDate
        }
        return q.jpqlRows("$SELECT$where order by r.date desc, r.createdAt desc", p, limit = limit).map(::view)
    }

    override fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, from: LocalDate, to: LocalDate, status: AbsenceRequestStatus?): List<AbsenceRequestView> {
        val p = params("inst" to institutionId.value, "from" to from, "to" to to)
        val where = StringBuilder(" where r.institutionId = :inst and r.date between :from and :to and r.status <> 'CANCELLED'")
        if (teacherId != null) {
            where.append(" and $TEACHES")
            p["teacher"] = teacherId.value
        }
        if (status != null) {
            where.append(" and r.status = :status")
            p["status"] = status.name
        }
        return q.jpqlRows("$SELECT$where order by r.date, r.createdAt", p).map(::view)
    }

    override fun teaches(id: UUID, teacherId: UserId): Boolean = repo.countTaughtBy(id, teacherId.value) > 0

    override fun setStatus(id: UUID, status: AbsenceRequestStatus, actor: UserId?, now: Instant) {
        val r = repo.findByIdOrNull(id) ?: return
        r.status = status.name
        r.acknowledgedBy = actor?.value
        if (status == AbsenceRequestStatus.ACKNOWLEDGED) r.acknowledgedAt = now
        repo.save(r)
    }

    override fun staffRecipients(institutionId: InstitutionId, studentId: UUID): List<UserId> {
        val teachers = enrollments.findCurrentTeacherIds(studentId).map(::UserId)
        if (teachers.isNotEmpty()) return teachers
        return memberships.findByInstitutionIdAndRoleIn(institutionId.value, listOf("OWNER", "ADMIN")).map { UserId(it.userId) }
    }
}
