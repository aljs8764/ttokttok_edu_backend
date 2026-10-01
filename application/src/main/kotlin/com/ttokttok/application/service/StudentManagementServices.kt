package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ChangeStudentStatusUseCase
import com.ttokttok.application.port.`in`.ClassHistoryItem
import com.ttokttok.application.port.`in`.GetStudentDetailQuery
import com.ttokttok.application.port.`in`.ManageGuardianUseCase
import com.ttokttok.application.port.`in`.MoveStudentClassUseCase
import com.ttokttok.application.port.`in`.SearchStudentsQuery
import com.ttokttok.application.port.`in`.SiblingView
import com.ttokttok.application.port.`in`.StatusHistoryItem
import com.ttokttok.application.port.`in`.StudentDetailView
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.application.port.`in`.UpdateStudentUseCase
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.StudentSearchCriteria
import com.ttokttok.application.port.out.StudentStatusHistoryPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.Enrollment
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.student.StudentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SearchStudentsService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
) : SearchStudentsQuery {

    @Transactional(readOnly = true)
    override fun search(command: SearchStudentsQuery.Command): PageResult<StudentView> {
        val m = guard.requireStaff(command.actor, command.institutionId)
        if (command.size !in 1..100 || command.page < 0) throw InvalidInputException("INVALID_PAGE", "page ≥ 0, size 1~100")
        // 교사: 담당 반으로 범위 제한
        val visible: Set<ClassroomId>? = if (m.role.isManager) null
        else classrooms.findByInstitution(command.institutionId).filter { it.isTaughtBy(command.actor) }.map { it.id }.toSet()
        val scope: Set<ClassroomId>? = when {
            command.classroomId == null -> visible
            visible == null || command.classroomId in visible -> setOf(command.classroomId)
            else -> emptySet()
        }
        val page = students.search(
            StudentSearchCriteria(command.institutionId, scope, command.status, command.keyword?.trim()?.takeIf { it.isNotEmpty() }, command.page, command.size),
        )
        return page.map { s -> toView(s, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = !m.role.isManager) }
    }
}

@Service
class GetStudentDetailService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val statusHistory: StudentStatusHistoryPort,
) : GetStudentDetailQuery {

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, studentId: StudentId): StudentDetailView {
        val m = guard.requireStaff(actor, institutionId)
        val s = students.find(studentId, institutionId) ?: throw NotFoundException("원생")
        val current = enrollments.findCurrent(s.id)
        if (!m.role.isManager) {
            val mine = classrooms.findByInstitution(institutionId).filter { it.isTaughtBy(actor) }.map { it.id }.toSet()
            if (current.none { it.classroomId in mine }) throw ForbiddenException("담당 반 원생이 아닙니다")
        }
        val classNames = classrooms.findByInstitution(institutionId).associate { it.id to it.name }
        val gs = guardians.findByStudent(s.id)
        // 같은 보호자 계정에 연결된 다른 자녀 (같은 기관 한정 — 타 기관 정보는 노출하지 않음)
        val siblingIds = gs.mapNotNull { it.userId }.distinct()
            .flatMap { guardians.findLinkedByUser(it) }.map { it.studentId }.filter { it != s.id }.distinct()
        val siblings = students.findAllByIds(siblingIds).filter { it.institutionId == institutionId }

        return StudentDetailView(
            student = toView(s, current.map { it.classroomId }, gs, masked = !m.role.isManager),
            memo = s.memo,
            classHistory = enrollments.findHistory(s.id).sortedByDescending { it.fromDate }
                .map { ClassHistoryItem(it.classroomId, classNames[it.classroomId] ?: "(삭제된 반)", it.fromDate, it.toDate) },
            statusHistory = statusHistory.findByStudent(s.id).sortedByDescending { it.effectiveDate }
                .map { StatusHistoryItem(it.from, it.to, it.reason, it.effectiveDate, it.note) },
            siblings = siblings.map { SiblingView(it.id, it.name) },
        )
    }
}

@Service
class UpdateStudentService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
) : UpdateStudentUseCase {
    @Transactional
    override fun update(command: UpdateStudentUseCase.Command): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val s = students.find(command.studentId, command.institutionId) ?: throw NotFoundException("원생")
        val updated = students.save(s.updateInfo(command.name, command.birthDate, command.grade, command.memo))
        return toView(updated, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = false)
    }
}

@Service
class MoveStudentClassService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val clock: ClockPort,
) : MoveStudentClassUseCase {
    @Transactional
    override fun move(command: MoveStudentClassUseCase.Command): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val s = students.find(command.studentId, command.institutionId) ?: throw NotFoundException("원생")
        if (s.status == StudentStatus.WITHDRAWN) throw ConflictException("STUDENT_WITHDRAWN", "퇴원한 원생은 반을 이동할 수 없습니다")
        val to = classrooms.find(command.toClassroomId, command.institutionId) ?: throw NotFoundException("이동할 반")
        val date = command.effectiveDate ?: clock.today()
        val current = enrollments.findCurrent(s.id)
        if (current.any { it.classroomId == to.id }) throw ConflictException("ALREADY_IN_CLASS", "이미 ${to.name} 소속입니다")

        // fromClassroomId 미지정 = 현재 소속 전부 마감 (단일 반 운영 기관의 기본 동작)
        val closing = command.fromClassroomId?.let { from ->
            current.filter { it.classroomId == from }.ifEmpty { throw InvalidInputException("NOT_IN_CLASS", "현재 소속 반이 아닙니다") }
        } ?: current
        ensureCapacity(to, enrollments)
        closing.forEach { enrollments.close(s.id, it.classroomId, date.minusDays(1).coerceAtLeast(it.fromDate)) }
        enrollments.save(Enrollment(s.id, to.id, date))
        return toView(s, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = false)
    }
}

@Service
class ChangeStudentStatusService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val history: StudentStatusHistoryPort,
    private val clock: ClockPort,
) : ChangeStudentStatusUseCase {
    @Transactional
    override fun change(command: ChangeStudentStatusUseCase.Command): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val s = students.find(command.studentId, command.institutionId) ?: throw NotFoundException("원생")
        val date = command.effectiveDate ?: clock.today()
        val t = s.changeStatus(command.status, command.reason, date, command.note)
        students.save(t.student)
        history.save(t.change, command.actor, clock.now())
        // 퇴원 시 반 소속 마감 → 출석부·정원·출결 예정 생성에서 제외
        if (t.student.status == StudentStatus.WITHDRAWN) {
            enrollments.findCurrent(s.id).forEach { enrollments.close(s.id, it.classroomId, date.coerceAtLeast(it.fromDate)) }
        }
        return toView(t.student, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = false)
    }
}

@Service
class ManageGuardianService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val creator: StudentCreator,
) : ManageGuardianUseCase {

    @Transactional
    override fun add(command: ManageGuardianUseCase.AddCommand): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val s = students.find(command.studentId, command.institutionId) ?: throw NotFoundException("원생")
        val phone = PhoneNumber.of(command.phone)
        if (guardians.findByStudent(s.id).any { it.phone == phone && it.userId != null })
            throw ConflictException("GUARDIAN_EXISTS", "이미 연결된 보호자 번호입니다")
        creator.addGuardian(s, StudentCreator.GuardianSpec(phone, command.relation, command.isPrimary), command.sendInstallGuide)
        return view(s)
    }

    /** STU-007 연결 해제 — 해당 학부모는 즉시 타임라인·푸시 대상에서 빠진다 */
    @Transactional
    override fun unlink(actor: UserId, institutionId: InstitutionId, studentId: StudentId, guardianId: GuardianId): StudentView {
        guard.requireManager(actor, institutionId)
        val s = students.find(studentId, institutionId) ?: throw NotFoundException("원생")
        val g = guardians.find(guardianId, institutionId)?.takeIf { it.studentId == s.id } ?: throw NotFoundException("보호자")
        guardians.save(g.unlink())
        return view(s)
    }

    private fun view(s: Student) = toView(s, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = false)
}
