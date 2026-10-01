package com.ttokttok.adapter.out.persistence

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
import com.ttokttok.adapter.out.persistence.entity.StudentEntity
import com.ttokttok.adapter.out.persistence.entity.UserEntity
import com.ttokttok.adapter.out.persistence.repository.AttendanceDayJpaRepository
import com.ttokttok.adapter.out.persistence.repository.AttendanceEventJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomJpaRepository
import com.ttokttok.adapter.out.persistence.repository.DestinationJpaRepository
import com.ttokttok.adapter.out.persistence.repository.DeviceTokenJpaRepository
import com.ttokttok.adapter.out.persistence.repository.EnrollmentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.GuardianJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InstitutionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.NotificationLogJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UserJpaRepository
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.DeviceTokenPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.NotificationChannel
import com.ttokttok.application.port.out.NotificationLogPort
import com.ttokttok.application.port.out.NotificationStatus
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.StudentSearchCriteria
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.attendance.AttendanceDay
import com.ttokttok.domain.attendance.AttendanceEvent
import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceSource
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.AttendanceEventId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.destination.DestinationType
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.DeviceToken
import com.ttokttok.domain.device.Platform
import com.ttokttok.domain.institution.Institution
import com.ttokttok.domain.student.Enrollment
import com.ttokttok.domain.student.Guardian
import com.ttokttok.domain.student.GuardianLinkStatus
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import com.ttokttok.domain.user.User
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

// ── 도메인 ↔ 엔티티 변환 규칙 ──
// 요일 비트: 월=1 … 일=64
internal fun Set<DayOfWeek>.toMask() = fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
internal fun Int.toDays(): Set<DayOfWeek> = DayOfWeek.entries.filter { this and (1 shl (it.value - 1)) != 0 }.toSet()

@Component
class InstitutionPersistenceAdapter(private val repo: InstitutionJpaRepository) : InstitutionPort {
    override fun save(institution: Institution): Institution {
        repo.save(InstitutionEntity(institution.id.value, institution.name, institution.ownerName, institution.lateThresholdMinutes, institution.earlyLeaveThresholdMinutes))
        return institution
    }
    override fun findById(id: InstitutionId) = repo.findByIdOrNull(id.value)?.toDomain()
    override fun findAllByIds(ids: Collection<InstitutionId>) = repo.findAllById(ids.map { it.value }).map { it.toDomain() }
    private fun InstitutionEntity.toDomain() = Institution(InstitutionId(id), name, ownerName, lateThresholdMinutes, earlyLeaveThresholdMinutes)
}

@Component
class UserPersistenceAdapter(private val repo: UserJpaRepository, private val crypto: FieldCrypto) : UserPort {
    override fun save(user: User): User {
        repo.save(
            UserEntity(
                user.id.value, user.name, user.email,
                user.phone?.let { crypto.encrypt(it.digits) }, user.phone?.let { crypto.hash(it.digits) },
                user.passwordHash, user.mustChangePassword,
            ),
        )
        return user
    }
    override fun findById(id: UserId) = repo.findByIdOrNull(id.value)?.toDomain()
    override fun findByEmail(email: String) = repo.findByEmail(email)?.toDomain()
    override fun findByPhone(phone: PhoneNumber) = repo.findByPhoneHash(crypto.hash(phone.digits))?.toDomain()
    private fun UserEntity.toDomain() =
        User(UserId(id), name, email, phoneEnc?.let { PhoneNumber.of(crypto.decrypt(it)) }, passwordHash, mustChangePassword)
}

@Component
class MembershipPersistenceAdapter(private val repo: MembershipJpaRepository) : MembershipPort {
    override fun save(membership: Membership): Membership {
        repo.save(MembershipEntity(membership.id.value, membership.userId.value, membership.institutionId.value, membership.role.name, membership.title))
        return membership
    }
    override fun find(userId: UserId, institutionId: InstitutionId) = repo.findByUserIdAndInstitutionId(userId.value, institutionId.value)?.toDomain()
    override fun findByUser(userId: UserId) = repo.findByUserId(userId.value).map { it.toDomain() }
    override fun findByInstitution(institutionId: InstitutionId) = repo.findByInstitutionId(institutionId.value).map { it.toDomain() }
    private fun MembershipEntity.toDomain() = Membership(MembershipId(id), UserId(userId), InstitutionId(institutionId), Role.valueOf(role), title)
}

@Component
class ClassroomPersistenceAdapter(private val repo: ClassroomJpaRepository) : ClassroomPort {
    override fun save(classroom: Classroom): Classroom {
        repo.save(
            ClassroomEntity(
                classroom.id.value, classroom.institutionId.value, classroom.name, classroom.capacity, classroom.days.toMask(),
                classroom.startTime, classroom.endTime, classroom.teacherIds.map { it.value }.toMutableSet(),
            ),
        )
        return classroom
    }
    override fun find(id: ClassroomId, institutionId: InstitutionId) = repo.findByIdAndInstitutionIdAndDeletedAtIsNull(id.value, institutionId.value)?.toDomain()
    override fun findByInstitution(institutionId: InstitutionId) = repo.findByInstitutionIdAndDeletedAtIsNull(institutionId.value).map { it.toDomain() }
    override fun findAllHeldOn(date: LocalDate) = repo.findHeldOn(1 shl (date.dayOfWeek.value - 1)).map { it.toDomain() }
    override fun softDelete(id: ClassroomId, institutionId: InstitutionId, at: Instant) {
        repo.findByIdAndInstitutionIdAndDeletedAtIsNull(id.value, institutionId.value)?.deletedAt = at
    }
    private fun ClassroomEntity.toDomain() = Classroom(
        ClassroomId(id), InstitutionId(institutionId), name, capacity, daysMask.toDays(), startTime, endTime, teacherIds.map { UserId(it) }.toSet(),
    )
}

@Component
class StudentPersistenceAdapter(private val repo: StudentJpaRepository, private val crypto: FieldCrypto) : StudentPort {
    override fun save(student: Student): Student {
        repo.save(
            StudentEntity(
                student.id.value, student.institutionId.value, student.name, crypto.encrypt(student.birthDate.toString()),
                student.grade, student.status.name, student.memo,
            ),
        )
        return student
    }
    override fun find(id: StudentId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()
    override fun findAllByIds(ids: Collection<StudentId>) = if (ids.isEmpty()) emptyList() else repo.findAllById(ids.map { it.value }).map { it.toDomain() }
    override fun findByInstitution(institutionId: InstitutionId) = repo.findByInstitutionId(institutionId.value).map { it.toDomain() }
    private fun StudentEntity.toDomain() =
        Student(StudentId(id), InstitutionId(institutionId), name, LocalDate.parse(crypto.decrypt(birthEnc)), grade, StudentStatus.valueOf(status), memo)

    override fun search(criteria: StudentSearchCriteria): PageResult<Student> {
        if (criteria.classroomIds?.isEmpty() == true) return PageResult(emptyList(), criteria.page, criteria.size, 0)
        val page = repo.findAll(StudentSpecs.of(criteria), PageRequest.of(criteria.page, criteria.size, Sort.by("name", "id")))
        return PageResult(page.content.map { it.toDomain() }, criteria.page, criteria.size, page.totalElements)
    }
}

@Component
class EnrollmentPersistenceAdapter(private val repo: EnrollmentJpaRepository) : EnrollmentPort {
    override fun save(enrollment: Enrollment): Enrollment {
        repo.save(EnrollmentEntity(null, enrollment.studentId.value, enrollment.classroomId.value, enrollment.fromDate, enrollment.toDate))
        return enrollment
    }
    override fun findCurrent(studentId: StudentId) = repo.findByStudentIdAndToDateIsNull(studentId.value).map { it.toDomain() }
    override fun findCurrentStudentIds(classroomId: ClassroomId) = repo.findByClassroomIdAndToDateIsNull(classroomId.value).map { StudentId(it.studentId) }
    override fun close(studentId: StudentId, classroomId: ClassroomId, toDate: LocalDate) {
        repo.findByStudentIdAndClassroomIdAndToDateIsNull(studentId.value, classroomId.value).forEach { it.toDate = toDate }
    }
    override fun findHistory(studentId: StudentId) = repo.findByStudentId(studentId.value).map { it.toDomain() }
    private fun EnrollmentEntity.toDomain() = Enrollment(StudentId(studentId), ClassroomId(classroomId), fromDate, toDate)
}

@Component
class GuardianPersistenceAdapter(private val repo: GuardianJpaRepository, private val crypto: FieldCrypto) : GuardianPort {
    override fun save(guardian: Guardian): Guardian {
        repo.save(
            GuardianEntity(
                guardian.id.value, guardian.institutionId.value, guardian.studentId.value,
                crypto.encrypt(guardian.phone.digits), crypto.hash(guardian.phone.digits), guardian.phone.last4,
                guardian.relation, guardian.isPrimary, guardian.userId?.value, guardian.linkStatus.name,
            ),
        )
        return guardian
    }
    override fun findByStudent(studentId: StudentId) = repo.findByStudentId(studentId.value).map { it.toDomain() }
    override fun findByPhone(phone: PhoneNumber) = repo.findByPhoneHash(crypto.hash(phone.digits)).map { it.toDomain() }
    override fun find(id: GuardianId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()
    override fun findLinkedByUser(userId: UserId) =
        repo.findByUserId(userId.value).filter { it.linkStatus == GuardianLinkStatus.LINKED.name }.map { it.toDomain() }
    private fun GuardianEntity.toDomain() = Guardian(
        GuardianId(id), InstitutionId(institutionId), StudentId(studentId), PhoneNumber.of(crypto.decrypt(phoneEnc)),
        relation, isPrimary, userId?.let { UserId(it) }, GuardianLinkStatus.valueOf(linkStatus),
    )
}

@Component
class DestinationPersistenceAdapter(private val repo: DestinationJpaRepository) : DestinationPort {
    override fun save(destination: Destination): Destination {
        repo.save(DestinationEntity(destination.id.value, destination.institutionId.value, destination.name, destination.type.name, destination.sortOrder))
        return destination
    }
    override fun find(id: DestinationId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()
    override fun findAllByIds(ids: Collection<DestinationId>) = if (ids.isEmpty()) emptyList() else repo.findAllById(ids.map { it.value }).map { it.toDomain() }
    override fun findByInstitution(institutionId: InstitutionId) = repo.findByInstitutionId(institutionId.value).map { it.toDomain() }
    private fun DestinationEntity.toDomain() = Destination(DestinationId(id), InstitutionId(institutionId), name, DestinationType.valueOf(type), sortOrder)
}

@Component
class AttendancePersistenceAdapter(
    private val days: AttendanceDayJpaRepository,
    private val events: AttendanceEventJpaRepository,
) : AttendancePort {
    override fun findDayForUpdate(studentId: StudentId, classroomId: ClassroomId, date: LocalDate) =
        days.findForUpdate(studentId.value, classroomId.value, date)?.toDomain()
    override fun findDays(classroomId: ClassroomId, date: LocalDate) = days.findByClassroomIdAndDate(classroomId.value, date).map { it.toDomain() }
    override fun findDayById(id: AttendanceDayId) = days.findByIdOrNull(id.value)?.toDomain()
    override fun findDaysByIds(ids: Collection<AttendanceDayId>) = if (ids.isEmpty()) emptyList() else days.findAllById(ids.map { it.value }).map { it.toDomain() }

    override fun saveDay(day: AttendanceDay): AttendanceDay {
        days.save(
            AttendanceDayEntity(
                day.id.value, day.institutionId.value, day.studentId.value, day.classroomId.value, day.date, day.status.name,
                day.isLate, day.isEarlyLeave, day.checkInAt, day.checkOutAt, day.nextDestinationId?.value, Instant.now(),
            ),
        )
        return day
    }

    override fun insertDayIfAbsent(day: AttendanceDay) =
        days.insertIfAbsent(day.id.value, day.institutionId.value, day.studentId.value, day.classroomId.value, day.date) > 0

    override fun findScheduledBefore(dateInclusive: LocalDate) =
        days.findByStatusAndDateLessThanEqual(AttendanceStatus.SCHEDULED.name, dateInclusive).map { it.toDomain() }

    override fun saveEvent(event: AttendanceEvent, idempotencyKey: String?) {
        events.save(
            AttendanceEventEntity(
                event.id.value, event.attendanceDayId.value, event.institutionId.value, event.studentId.value, event.type.name,
                event.fromStatus.name, event.toStatus.name, event.actorId.value, event.source.name, event.reason,
                event.destinationId?.value, event.occurredAt, idempotencyKey?.takeIf { it.isNotBlank() },
            ),
        )
    }

    override fun findEventByIdempotencyKey(institutionId: InstitutionId, key: String) =
        events.findByInstitutionIdAndIdempotencyKey(institutionId.value, key)?.toDomain()

    override fun findEvents(studentIds: Collection<StudentId>, before: Instant?, limit: Int): List<AttendanceEvent> {
        if (studentIds.isEmpty()) return emptyList()
        val ids = studentIds.map { it.value }
        val page = PageRequest.of(0, limit)
        val rows = if (before == null) events.findByStudentIdInOrderByOccurredAtDesc(ids, page)
        else events.findByStudentIdInAndOccurredAtBeforeOrderByOccurredAtDesc(ids, before, page)
        return rows.map { it.toDomain() }
    }

    private fun AttendanceDayEntity.toDomain() = AttendanceDay(
        AttendanceDayId(id), InstitutionId(institutionId), StudentId(studentId), ClassroomId(classroomId), date,
        AttendanceStatus.valueOf(status), isLate, isEarlyLeave, checkInAt, checkOutAt, nextDestinationId?.let { DestinationId(it) },
    )

    private fun AttendanceEventEntity.toDomain() = AttendanceEvent(
        AttendanceEventId(id), AttendanceDayId(attendanceDayId), InstitutionId(institutionId), StudentId(studentId),
        AttendanceEventType.valueOf(type), AttendanceStatus.valueOf(fromStatus), AttendanceStatus.valueOf(toStatus),
        UserId(actorId), AttendanceSource.valueOf(source), reason, destinationId?.let { DestinationId(it) }, occurredAt,
    )
}

@Component
class DeviceTokenPersistenceAdapter(private val repo: DeviceTokenJpaRepository) : DeviceTokenPort {
    override fun upsert(token: DeviceToken) {
        // 같은 토큰이 다른 계정으로 로그인되면 소유자 교체 (기기 공유·재로그인)
        repo.save(DeviceTokenEntity(token.token, token.userId.value, token.flavor.name, token.platform.name, Instant.now()))
    }
    override fun findByUsers(userIds: Collection<UserId>, flavor: AppFlavor) =
        if (userIds.isEmpty()) emptyList()
        else repo.findByUserIdInAndFlavor(userIds.map { it.value }, flavor.name)
            .map { DeviceToken(UserId(it.userId), AppFlavor.valueOf(it.flavor), Platform.valueOf(it.platform), it.token) }
    override fun deleteTokens(tokens: Collection<String>) = repo.deleteAllById(tokens)
}

@Component
class NotificationLogPersistenceAdapter(private val repo: NotificationLogJpaRepository) : NotificationLogPort {
    override fun record(
        institutionId: InstitutionId, channel: NotificationChannel, templateCode: String,
        recipientCount: Int, status: NotificationStatus, error: String?, at: Instant,
    ) {
        repo.save(NotificationLogEntity(null, institutionId.value, channel.name, templateCode, recipientCount, status.name, error, at))
    }
}
