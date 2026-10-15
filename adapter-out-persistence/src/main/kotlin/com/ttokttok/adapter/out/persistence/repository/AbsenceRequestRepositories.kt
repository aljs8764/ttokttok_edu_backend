package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.AbsenceRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

interface AbsenceRequestJpaRepository : JpaRepository<AbsenceRequestEntity, UUID> {
    // 네이티브 쿼리에는 null 파라미터를 넘기지 않는다 (PostgreSQL 이 null 의 타입을 추론하지 못함) → 예상 시각 유무로 나눔

    /** 같은 아이·날짜·종류의 살아있는 신청(부분 유니크 인덱스)이 있으면 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into absence_request (id, institution_id, student_id, guardian_user_id, date, kind, expected_time, reason, status, created_at)
                   values (:id, :inst, :student, :guardian, :date, :kind, :time, :reason, 'SUBMITTED', :now)
                   on conflict (student_id, date, kind) where status <> 'CANCELLED' do nothing""",
        nativeQuery = true,
    )
    fun insertIfNoActive(
        @Param("id") id: UUID, @Param("inst") institutionId: UUID, @Param("student") studentId: UUID, @Param("guardian") guardianUserId: UUID,
        @Param("date") date: LocalDate, @Param("kind") kind: String, @Param("time") expectedTime: LocalTime, @Param("reason") reason: String,
        @Param("now") now: Instant,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into absence_request (id, institution_id, student_id, guardian_user_id, date, kind, reason, status, created_at)
                   values (:id, :inst, :student, :guardian, :date, :kind, :reason, 'SUBMITTED', :now)
                   on conflict (student_id, date, kind) where status <> 'CANCELLED' do nothing""",
        nativeQuery = true,
    )
    fun insertIfNoActiveWithoutTime(
        @Param("id") id: UUID, @Param("inst") institutionId: UUID, @Param("student") studentId: UUID, @Param("guardian") guardianUserId: UUID,
        @Param("date") date: LocalDate, @Param("kind") kind: String, @Param("reason") reason: String, @Param("now") now: Instant,
    ): Int

    /** 신청 + 기관명·원생명·보호자명 */
    @Query(
        """select r, i.name, s.name, g.name from AbsenceRequestEntity r
           join InstitutionEntity i on i.id = r.institutionId
           join StudentEntity s on s.id = r.studentId
           join UserEntity g on g.id = r.guardianUserId
           where r.id = :id""",
    )
    fun findView(@Param("id") id: UUID): List<Array<Any?>>

    /** 이 교사가 담당하는 반의 현재 원생에 대한 신청인지 */
    @Query(
        """select count(r) from AbsenceRequestEntity r
           where r.id = :id and exists (
               select 1 from EnrollmentEntity e, ClassroomTeacherEntity ct
                where ct.classroomId = e.classroomId and e.studentId = r.studentId and e.toDate is null and ct.userId = :teacher)""",
    )
    fun countTaughtBy(@Param("id") id: UUID, @Param("teacher") teacherId: UUID): Long
}
