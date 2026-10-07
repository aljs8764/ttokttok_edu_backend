package com.ttokttok.application.port.out

import com.ttokttok.domain.attendance.AttendanceDay
import com.ttokttok.domain.attendance.AttendanceEvent
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.DeviceToken
import com.ttokttok.domain.institution.Institution
import com.ttokttok.domain.student.Enrollment
import com.ttokttok.domain.student.Guardian
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.User
import java.time.Instant
import java.time.LocalDate

// 영속성 Outbound Port. 구현은 adapter-out-persistence.
// 기관 범위 데이터는 모든 조회에 institutionId를 받아 테넌트 경계를 강제한다.

interface InstitutionPort {
    fun save(institution: Institution): Institution
    fun findById(id: InstitutionId): Institution?
    fun findAllByIds(ids: Collection<InstitutionId>): List<Institution>
}

interface UserPort {
    fun save(user: User): User
    fun findById(id: UserId): User?
    fun findByEmail(email: String): User?
    fun findByPhone(phone: PhoneNumber): User?
}

interface MembershipPort {
    fun save(membership: Membership): Membership
    fun find(userId: UserId, institutionId: InstitutionId): Membership?
    fun findByUser(userId: UserId): List<Membership>
    fun findByInstitution(institutionId: InstitutionId): List<Membership>
}

interface ClassroomPort {
    fun save(classroom: Classroom): Classroom
    fun find(id: ClassroomId, institutionId: InstitutionId): Classroom?
    fun findByInstitution(institutionId: InstitutionId): List<Classroom>
    fun findAllHeldOn(date: LocalDate): List<Classroom>
    fun softDelete(id: ClassroomId, institutionId: InstitutionId, at: Instant)
}

interface StudentPort {
    fun save(student: Student): Student
    fun find(id: StudentId, institutionId: InstitutionId): Student?
    fun findAllByIds(ids: Collection<StudentId>): List<Student>
    fun findByInstitution(institutionId: InstitutionId): List<Student>
    /** 스펙 7-8: 아이의 모든 기관 원생 */
    fun findByChildren(childIds: Collection<com.ttokttok.domain.child.ChildId>): List<Student>
    /** STU-001 목록: 반·상태 필터, 이름/보호자 번호 뒷 4자리 검색, 페이징 */
    fun search(criteria: StudentSearchCriteria): PageResult<Student>
}

data class StudentSearchCriteria(
    val institutionId: InstitutionId,
    /** null = 전체 반, 빈 집합 = 볼 수 있는 반 없음 */
    val classroomIds: Set<ClassroomId>?,
    val status: com.ttokttok.domain.student.StudentStatus?,
    val keyword: String?,
    val page: Int,
    val size: Int,
)

data class PageResult<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long) {
    val totalPages: Int get() = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()
    fun <R> map(f: (T) -> R) = PageResult(items.map(f), page, size, totalElements)
}

interface EnrollmentPort {
    fun save(enrollment: Enrollment): Enrollment
    fun findCurrent(studentId: StudentId): List<Enrollment>
    fun findCurrentStudentIds(classroomId: ClassroomId): List<StudentId>
    /** 현재 소속을 종료일로 마감 (반 이동·퇴원) */
    fun close(studentId: StudentId, classroomId: ClassroomId, toDate: LocalDate)
    fun findHistory(studentId: StudentId): List<Enrollment>
}

interface GuardianPort {
    fun save(guardian: Guardian): Guardian
    fun findByStudent(studentId: StudentId): List<Guardian>
    fun findByStudents(studentIds: Collection<StudentId>): List<Guardian>
    fun findByPhone(phone: PhoneNumber): List<Guardian>
    fun findLinkedByUser(userId: UserId): List<Guardian>
    fun find(id: com.ttokttok.domain.common.GuardianId, institutionId: InstitutionId): Guardian?
}

interface DestinationPort {
    fun save(destination: Destination): Destination
    fun find(id: DestinationId, institutionId: InstitutionId): Destination?
    fun findAllByIds(ids: Collection<DestinationId>): List<Destination>
    /** 삭제되지 않은 목적지만 */
    fun findByInstitution(institutionId: InstitutionId): List<Destination>
    /** 출결 기록이 참조하므로 소프트 삭제 (findAllByIds 로는 계속 조회됨) */
    fun softDelete(id: DestinationId, institutionId: InstitutionId, at: Instant)
}

interface AttendancePort {
    /** 같은 학생·반·날짜 행을 행 잠금(FOR UPDATE)으로 조회 — 더블 터치 동시성 방지 */
    fun findDayForUpdate(studentId: StudentId, classroomId: ClassroomId, date: LocalDate): AttendanceDay?
    fun findDays(classroomId: ClassroomId, date: LocalDate): List<AttendanceDay>
    /** 대시보드·데일리 리포트: 기관의 해당 날짜 전체 */
    fun findDaysByInstitution(institutionId: InstitutionId, date: LocalDate): List<AttendanceDay>
    /** 23:50 통계 집계: 전 기관 */
    fun findDaysByDate(date: LocalDate): List<AttendanceDay>
    /** 월간 출석부: 반의 기간(양 끝 포함) */
    fun findDaysInRange(classroomId: ClassroomId, from: LocalDate, to: LocalDate): List<AttendanceDay>
    fun findDayById(id: AttendanceDayId): AttendanceDay?
    fun findDaysByIds(ids: Collection<AttendanceDayId>): List<AttendanceDay>
    fun saveDay(day: AttendanceDay): AttendanceDay
    /** 이미 있으면 무시하고 false (배치 재실행 안전) */
    fun insertDayIfAbsent(day: AttendanceDay): Boolean
    fun findScheduledBefore(dateInclusive: LocalDate): List<AttendanceDay>
    fun saveEvent(event: AttendanceEvent, idempotencyKey: String?)
    fun findEventByIdempotencyKey(institutionId: InstitutionId, key: String): AttendanceEvent?
    /** 학부모 타임라인: 최신순, before 커서 이전 */
    fun findEvents(studentIds: Collection<StudentId>, before: Instant?, limit: Int): List<AttendanceEvent>
    /** 관리자 대시보드 실시간 타임라인 (DASH-002): 기관 최신순. classroomIds가 null이면 전체 반 */
    fun findRecentEvents(institutionId: InstitutionId, classroomIds: Set<ClassroomId>?, limit: Int): List<AttendanceEvent>
}

interface DeviceTokenPort {
    fun upsert(token: DeviceToken)
    fun findByUsers(userIds: Collection<UserId>, flavor: AppFlavor): List<DeviceToken>
    fun deleteTokens(tokens: Collection<String>)
    /** 로그아웃·앱 삭제 시 본인 토큰 해제 */
    fun deleteForUser(userId: UserId, token: String)
}
