package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ConsultSlotEntity
import com.ttokttok.adapter.out.persistence.repository.ConsultSlotJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.application.port.`in`.ConsultMode
import com.ttokttok.application.port.`in`.ConsultSlotView
import com.ttokttok.application.port.`in`.ConsultStatus
import com.ttokttok.application.port.out.ConsultSlotPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** NTC-008 상담 예약 슬롯 */
@Component
class ConsultSlotJpaAdapter(
    private val repo: ConsultSlotJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
) : ConsultSlotPort {

    private companion object {
        const val SELECT = """
            select cs, i.name, t.name, s.name, b.name
              from ConsultSlotEntity cs
              join InstitutionEntity i on i.id = cs.institutionId
              join UserEntity t on t.id = cs.teacherId
              left join StudentEntity s on s.id = cs.studentId
              left join UserEntity b on b.id = cs.bookedBy
        """
        /** 원생의 현재 반 담당 교사이거나, 그 기관의 원장·실장이 연 슬롯 */
        const val ELIGIBLE = """
            (exists (select 1 from ClassroomTeacherEntity ct, EnrollmentEntity e
                      where e.classroomId = ct.classroomId and ct.userId = cs.teacherId and e.toDate is null and e.studentId in (:ids))
             or exists (select 1 from MembershipEntity m where m.userId = cs.teacherId and m.institutionId = cs.institutionId and m.role in ('OWNER', 'ADMIN')))
        """

        fun view(r: Array<Any?>): ConsultSlotView {
            val cs = r[0] as ConsultSlotEntity
            return ConsultSlotView(
                cs.id, cs.institutionId, r[1] as String, cs.teacherId, r[2] as String,
                cs.startsAt, cs.endsAt, ConsultMode.valueOf(cs.mode), cs.location,
                ConsultStatus.valueOf(cs.status), cs.studentId, r[3] as String?,
                cs.bookedBy, r[4] as String?, cs.topic, cs.bookedAt, cs.cancelReason,
            )
        }
    }

    override fun insert(institutionId: InstitutionId, teacherId: UUID, startsAt: Instant, endsAt: Instant, mode: ConsultMode, location: String?, createdBy: UserId, now: Instant): UUID? {
        val id = Uuid7.next()
        val n = if (location != null) {
            repo.insertIfFree(id, institutionId.value, teacherId, startsAt, endsAt, mode.name, location, createdBy.value, now)
        } else {
            repo.insertIfFreeWithoutLocation(id, institutionId.value, teacherId, startsAt, endsAt, mode.name, createdBy.value, now)
        }
        return if (n == 1) id else null
    }

    override fun find(id: UUID): ConsultSlotView? = q.jpqlRows("$SELECT where cs.id = :id", params("id" to id)).firstOrNull()?.let(::view)

    override fun findAll(ids: Collection<UUID>): List<ConsultSlotView> =
        if (ids.isEmpty()) emptyList() else q.jpqlRows("$SELECT where cs.id in (:ids)", params("ids" to ids)).map(::view)

    override fun list(institutionId: InstitutionId, from: Instant, to: Instant, teacherId: UUID?): List<ConsultSlotView> {
        val p = params("i" to institutionId.value, "from" to from, "to" to to)
        val teacher = if (teacherId != null) {
            p["t"] = teacherId
            " and cs.teacherId = :t"
        } else ""
        return q.jpqlRows("$SELECT where cs.institutionId = :i and cs.startsAt >= :from and cs.startsAt < :to$teacher order by cs.startsAt, t.name", p).map(::view)
    }

    override fun availableFor(studentIds: Collection<UUID>, after: Instant): List<ConsultSlotView> {
        if (studentIds.isEmpty()) return emptyList()
        return q.jpqlRows(
            """$SELECT where cs.status = 'OPEN' and cs.startsAt > :after
                 and cs.institutionId in (select st.institutionId from StudentEntity st where st.id in (:ids))
                 and $ELIGIBLE
               order by cs.startsAt""",
            params("ids" to studentIds, "after" to after), limit = 300,
        ).map(::view)
    }

    override fun bookedBy(parent: UserId, studentIds: Collection<UUID>?, after: Instant): List<ConsultSlotView> {
        val p = params("u" to parent.value, "after" to after)
        val filter = if (studentIds != null) {
            p["ids"] = studentIds.ifEmpty { setOf(UUID(0, 0)) }
            " and cs.studentId in (:ids)"
        } else ""
        return q.jpqlRows(
            "$SELECT where cs.bookedBy = :u and cs.status in ('BOOKED', 'DONE', 'CANCELED') and cs.startsAt > :after$filter order by cs.startsAt desc",
            p, limit = 100,
        ).map(::view)
    }

    override fun book(slotId: UUID, studentId: UUID, parent: UserId, topic: String?, now: Instant): Boolean =
        repo.book(slotId, studentId, parent.value, topic, now) > 0

    override fun release(slotId: UUID, parent: UserId, now: Instant): Boolean = repo.release(slotId, parent.value, now) > 0

    override fun setStatus(slotId: UUID, status: String, cancelReason: String?, now: Instant) {
        val cs = repo.findByIdOrNull(slotId) ?: return
        cs.status = status
        if (cancelReason != null) cs.cancelReason = cancelReason
        cs.updatedAt = now
        repo.save(cs)
    }

    override fun deleteOpen(slotId: UUID): Boolean = repo.deleteOpen(slotId) > 0

    override fun eligible(slotId: UUID, studentId: UUID): Boolean = repo.countEligible(slotId, studentId) > 0

    override fun isStaff(institutionId: InstitutionId, userId: UUID): Boolean =
        memberships.existsByInstitutionIdAndUserIdAndRoleNot(institutionId.value, userId, "PARENT")
}
