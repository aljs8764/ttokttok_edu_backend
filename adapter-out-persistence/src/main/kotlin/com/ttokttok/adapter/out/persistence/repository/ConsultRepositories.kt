package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.ConsultSlotEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ConsultSlotJpaRepository : JpaRepository<ConsultSlotEntity, UUID> {
    /** 같은 교사·같은 시작 시각의 살아있는 슬롯(부분 유니크 인덱스)이 있으면 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into consult_slot (id, institution_id, teacher_id, starts_at, ends_at, mode, location, created_by, created_at, updated_at)
                   values (:id, :i, :t, :s, :e, :m, :loc, :by, :now, :now)
                   on conflict (teacher_id, starts_at) where status <> 'CANCELED' do nothing""",
        nativeQuery = true,
    )
    fun insertIfFree(
        @Param("id") id: UUID, @Param("i") institutionId: UUID, @Param("t") teacherId: UUID, @Param("s") startsAt: Instant,
        @Param("e") endsAt: Instant, @Param("m") mode: String, @Param("loc") location: String, @Param("by") createdBy: UUID,
        @Param("now") now: Instant,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into consult_slot (id, institution_id, teacher_id, starts_at, ends_at, mode, created_by, created_at, updated_at)
                   values (:id, :i, :t, :s, :e, :m, :by, :now, :now)
                   on conflict (teacher_id, starts_at) where status <> 'CANCELED' do nothing""",
        nativeQuery = true,
    )
    fun insertIfFreeWithoutLocation(
        @Param("id") id: UUID, @Param("i") institutionId: UUID, @Param("t") teacherId: UUID, @Param("s") startsAt: Instant,
        @Param("e") endsAt: Instant, @Param("m") mode: String, @Param("by") createdBy: UUID, @Param("now") now: Instant,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update ConsultSlotEntity cs set cs.status = 'BOOKED', cs.studentId = :s, cs.bookedBy = :u, cs.topic = :topic, cs.bookedAt = :now, cs.updatedAt = :now
            where cs.id = :id and cs.status = 'OPEN'""",
    )
    fun book(@Param("id") id: UUID, @Param("s") studentId: UUID, @Param("u") parent: UUID, @Param("topic") topic: String?, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update ConsultSlotEntity cs set cs.status = 'OPEN', cs.studentId = null, cs.bookedBy = null, cs.topic = null, cs.bookedAt = null, cs.updatedAt = :now
            where cs.id = :id and cs.status = 'BOOKED' and cs.bookedBy = :u""",
    )
    fun release(@Param("id") id: UUID, @Param("u") parent: UUID, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConsultSlotEntity cs where cs.id = :id and cs.status in ('OPEN', 'CANCELED') and cs.bookedBy is null")
    fun deleteOpen(@Param("id") id: UUID): Int

    /** 원생의 현재 반 담당 교사이거나, 그 기관의 원장·실장이 연 슬롯인지 */
    @Query(
        """select count(cs) from ConsultSlotEntity cs, StudentEntity st
            where cs.id = :id and st.id = :sid and st.institutionId = cs.institutionId
              and (exists (select 1 from ClassroomTeacherEntity ct, EnrollmentEntity e
                            where e.classroomId = ct.classroomId and ct.userId = cs.teacherId and e.toDate is null and e.studentId = :sid)
                   or exists (select 1 from MembershipEntity m
                               where m.userId = cs.teacherId and m.institutionId = cs.institutionId and m.role in ('OWNER', 'ADMIN')))""",
    )
    fun countEligible(@Param("id") slotId: UUID, @Param("sid") studentId: UUID): Long
}
