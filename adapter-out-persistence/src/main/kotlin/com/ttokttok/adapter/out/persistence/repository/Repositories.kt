package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.AttendanceDayEntity
import com.ttokttok.adapter.out.persistence.entity.AttendanceEventEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomEntity
import com.ttokttok.adapter.out.persistence.entity.DestinationEntity
import com.ttokttok.adapter.out.persistence.entity.DeviceTokenEntity
import com.ttokttok.adapter.out.persistence.entity.EnrollmentEntity
import com.ttokttok.adapter.out.persistence.entity.GuardianEntity
import com.ttokttok.adapter.out.persistence.entity.InstitutionEntity
import com.ttokttok.adapter.out.persistence.entity.MembershipEntity
import com.ttokttok.adapter.out.persistence.entity.NotificationLogEntity
import com.ttokttok.adapter.out.persistence.entity.OutboxEntity
import com.ttokttok.adapter.out.persistence.entity.StudentEntity
import com.ttokttok.adapter.out.persistence.entity.InvitationEntity
import com.ttokttok.adapter.out.persistence.entity.JoinRequestEntity
import com.ttokttok.adapter.out.persistence.entity.StudentImportJobEntity
import com.ttokttok.adapter.out.persistence.entity.StudentStatusHistoryEntity
import com.ttokttok.adapter.out.persistence.entity.UserEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface InstitutionJpaRepository : JpaRepository<InstitutionEntity, UUID> {
    @Query("select count(i) from InstitutionEntity i where i.id = :id and i.isSample = true")
    fun countSample(@Param("id") id: UUID): Long

    /** 이 사용자가 소속된, 아직 이용 중인 가장 최근 샘플 기관 (id, name) */
    @Query(
        """select i.id, i.name from InstitutionEntity i, MembershipEntity m
            where m.institutionId = i.id and m.userId = :u and i.isSample = true and i.serviceStatus = 'ACTIVE'
            order by i.createdAt desc""",
    )
    fun findSamplesOf(@Param("u") userId: UUID, pageable: Pageable): List<Array<Any?>>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update InstitutionEntity i set i.missingAlertMinutes = :m, i.missingAlertParent = :p, i.updatedAt = :now where i.id = :id")
    fun setMissingAlertPolicy(@Param("id") id: UUID, @Param("m") minutes: Int, @Param("p") notifyParent: Boolean, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update InstitutionEntity i set i.sessionIdleMinutes = :m where i.id = :id")
    fun setSessionIdleMinutes(@Param("id") id: UUID, @Param("m") minutes: Int): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update InstitutionEntity i set i.isSample = true where i.id = :id")
    fun markSample(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update InstitutionEntity i set i.serviceStatus = :st, i.statusReason = :reason, i.statusChangedAt = :now, i.updatedAt = :now
            where i.id = :id and (:sampleOnly = false or i.isSample = true)""",
    )
    fun setServiceStatus(
        @Param("id") id: UUID, @Param("st") status: String, @Param("reason") reason: String?, @Param("now") now: Instant,
        @Param("sampleOnly") sampleOnly: Boolean,
    ): Int
}

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
    fun findByPhoneHash(phoneHash: String): UserEntity?

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserEntity u set u.locale = :l, u.updatedAt = :now where u.id = :id")
    fun setLocale(@Param("id") id: UUID, @Param("l") locale: String, @Param("now") now: Instant): Int
}

interface MembershipJpaRepository : JpaRepository<MembershipEntity, UUID> {
    fun findByUserIdAndInstitutionId(userId: UUID, institutionId: UUID): MembershipEntity?
    fun findByUserId(userId: UUID): List<MembershipEntity>
    fun findByInstitutionId(institutionId: UUID): List<MembershipEntity>
    fun findByInstitutionIdAndRoleIn(institutionId: UUID, roles: Collection<String>): List<MembershipEntity>
    fun existsByInstitutionIdAndUserIdAndRoleNot(institutionId: UUID, userId: UUID, role: String): Boolean
    fun existsByUserIdAndInstitutionIdAndRoleIn(userId: UUID, institutionId: UUID, roles: Collection<String>): Boolean
    fun countByInstitutionIdAndRoleNot(institutionId: UUID, role: String): Long

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MembershipEntity m where m.institutionId = :i")
    fun deleteByInstitution(@Param("i") institutionId: UUID): Int
}

interface ClassroomJpaRepository : JpaRepository<ClassroomEntity, UUID> {
    fun findByIdAndInstitutionIdAndDeletedAtIsNull(id: UUID, institutionId: UUID): ClassroomEntity?
    fun findByInstitutionIdAndDeletedAtIsNull(institutionId: UUID): List<ClassroomEntity>

    @Query(value = "select * from classroom where deleted_at is null and (days_mask & :bit) <> 0", nativeQuery = true)
    fun findHeldOn(@Param("bit") dayBit: Int): List<ClassroomEntity>
}

interface StudentJpaRepository : JpaRepository<StudentEntity, UUID>, JpaSpecificationExecutor<StudentEntity> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): StudentEntity?
    fun findByInstitutionId(institutionId: UUID): List<StudentEntity>
    fun findByChildIdIn(childIds: Collection<UUID>): List<StudentEntity>
    fun countByInstitutionIdAndStatusNot(institutionId: UUID, status: String): Long

    @Query("select s.id from StudentEntity s where s.institutionId = :i and s.status <> 'WITHDRAWN' and s.id in (:ids)")
    fun findActiveIds(@Param("i") institutionId: UUID, @Param("ids") ids: Collection<UUID>): List<UUID>
}

interface EnrollmentJpaRepository : JpaRepository<EnrollmentEntity, Long> {
    fun findByStudentIdAndToDateIsNull(studentId: UUID): List<EnrollmentEntity>
    fun findByClassroomIdAndToDateIsNull(classroomId: UUID): List<EnrollmentEntity>
    fun findByStudentIdAndClassroomIdAndToDateIsNull(studentId: UUID, classroomId: UUID): List<EnrollmentEntity>
    fun findByStudentId(studentId: UUID): List<EnrollmentEntity>

    /** 현재 배정(종료일 없음)의 (원생 id, 반 이름, 시작일) */
    @Query(
        """select e.studentId, c.name, e.fromDate from EnrollmentEntity e join ClassroomEntity c on c.id = e.classroomId
            where e.toDate is null and e.studentId in (:ids)""",
    )
    fun findCurrentClassroomNames(@Param("ids") studentIds: Collection<UUID>): List<Array<Any?>>

    /** 원생이 지금 다니는 반들의 담당 교사 */
    @Query(
        """select distinct ct.userId from EnrollmentEntity e, ClassroomTeacherEntity ct
            where ct.classroomId = e.classroomId and e.studentId = :student and e.toDate is null""",
    )
    fun findCurrentTeacherIds(@Param("student") studentId: UUID): List<UUID>
}

/** 원생마다 가장 최근에 시작한 현재 반 이름 */
fun EnrollmentJpaRepository.currentClassroomNameOf(studentIds: Collection<UUID>): Map<UUID, String> {
    if (studentIds.isEmpty()) return emptyMap()
    return studentIds.distinct().chunked(1000).flatMap { findCurrentClassroomNames(it) }
        .groupBy { it[0] as UUID }
        .mapValues { (_, rows) -> rows.maxBy { it[2] as LocalDate }[1] as String }
}

interface GuardianJpaRepository : JpaRepository<GuardianEntity, UUID> {
    fun findByStudentId(studentId: UUID): List<GuardianEntity>
    fun findByStudentIdIn(studentIds: Collection<UUID>): List<GuardianEntity>
    fun findByPhoneHash(phoneHash: String): List<GuardianEntity>
    fun findByUserId(userId: UUID): List<GuardianEntity>
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): GuardianEntity?

    /** 앱이 연결된 보호자 계정 */
    @Query("select distinct g.userId from GuardianEntity g where g.studentId = :s and g.userId is not null and g.linkStatus = 'LINKED'")
    fun findLinkedUserIds(@Param("s") studentId: UUID): List<UUID>
}

interface DestinationJpaRepository : JpaRepository<DestinationEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): DestinationEntity?
    fun findByInstitutionId(institutionId: UUID): List<DestinationEntity>
    fun findByInstitutionIdAndDeletedAtIsNull(institutionId: UUID): List<DestinationEntity>
}

interface AttendanceDayJpaRepository : JpaRepository<AttendanceDayEntity, UUID> {
    @Query(
        value = "select * from attendance_day where student_id = :studentId and classroom_id = :classroomId and date = :date for update",
        nativeQuery = true,
    )
    fun findForUpdate(@Param("studentId") studentId: UUID, @Param("classroomId") classroomId: UUID, @Param("date") date: LocalDate): AttendanceDayEntity?

    fun findByClassroomIdAndDate(classroomId: UUID, date: LocalDate): List<AttendanceDayEntity>

    fun findByInstitutionIdAndDate(institutionId: UUID, date: LocalDate): List<AttendanceDayEntity>

    fun findByDate(date: LocalDate): List<AttendanceDayEntity>

    fun findByClassroomIdAndDateBetween(classroomId: UUID, from: LocalDate, to: LocalDate): List<AttendanceDayEntity>

    fun findByStatusAndDateLessThanEqual(status: String, date: LocalDate): List<AttendanceDayEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into attendance_day (id, institution_id, student_id, classroom_id, date, status)
                   values (:id, :institutionId, :studentId, :classroomId, :date, 'SCHEDULED')
                   on conflict (student_id, classroom_id, date) do nothing""",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID, @Param("institutionId") institutionId: UUID, @Param("studentId") studentId: UUID,
        @Param("classroomId") classroomId: UUID, @Param("date") date: LocalDate,
    ): Int
}

interface AttendanceEventJpaRepository : JpaRepository<AttendanceEventEntity, UUID> {
    fun findByInstitutionIdAndIdempotencyKey(institutionId: UUID, idempotencyKey: String): AttendanceEventEntity?

    fun findByStudentIdInOrderByOccurredAtDesc(studentIds: Collection<UUID>, pageable: Pageable): List<AttendanceEventEntity>

    fun findByStudentIdInAndOccurredAtBeforeOrderByOccurredAtDesc(
        studentIds: Collection<UUID>, before: Instant, pageable: Pageable,
    ): List<AttendanceEventEntity>

    fun findByInstitutionIdOrderByOccurredAtDesc(institutionId: UUID, pageable: Pageable): List<AttendanceEventEntity>

    @Query(
        value = """select e.* from attendance_event e
                   join attendance_day d on d.id = e.attendance_day_id
                   where e.institution_id = :institutionId and d.classroom_id in (:classroomIds)
                   order by e.occurred_at desc limit :limit""",
        nativeQuery = true,
    )
    fun findRecentInClassrooms(
        @Param("institutionId") institutionId: UUID, @Param("classroomIds") classroomIds: Collection<UUID>, @Param("limit") limit: Int,
    ): List<AttendanceEventEntity>
}

interface OutboxJpaRepository : JpaRepository<OutboxEntity, UUID> {
    @Query(
        value = """select * from outbox where status = 'PENDING' and next_attempt_at <= :now
                   order by next_attempt_at limit :limit for update skip locked""",
        nativeQuery = true,
    )
    fun lockPending(@Param("now") now: Instant, @Param("limit") limit: Int): List<OutboxEntity>
}

interface DeviceTokenJpaRepository : JpaRepository<DeviceTokenEntity, String> {
    fun findByUserIdInAndFlavor(userIds: Collection<UUID>, flavor: String): List<DeviceTokenEntity>
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DeviceTokenEntity d where d.token = :token and d.userId = :userId")
    fun deleteByTokenAndUserId(@Param("token") token: String, @Param("userId") userId: UUID): Int
}

interface NotificationLogJpaRepository : JpaRepository<NotificationLogEntity, Long>

interface StudentStatusHistoryJpaRepository : JpaRepository<StudentStatusHistoryEntity, Long> {
    fun findByStudentId(studentId: UUID): List<StudentStatusHistoryEntity>
}

interface InvitationJpaRepository : JpaRepository<InvitationEntity, UUID> {
    fun findByToken(token: String): InvitationEntity?
}

interface JoinRequestJpaRepository : JpaRepository<JoinRequestEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): JoinRequestEntity?
    fun findByInstitutionId(institutionId: UUID): List<JoinRequestEntity>
    fun findByInstitutionIdAndStatus(institutionId: UUID, status: String): List<JoinRequestEntity>
}

interface StudentImportJobJpaRepository : JpaRepository<StudentImportJobEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): StudentImportJobEntity?
}
