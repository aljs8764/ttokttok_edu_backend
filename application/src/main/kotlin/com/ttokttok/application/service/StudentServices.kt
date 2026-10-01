package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GuardianView
import com.ttokttok.application.port.`in`.ListStudentsQuery
import com.ttokttok.application.port.`in`.RegisterStudentUseCase
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.Enrollment
import com.ttokttok.domain.student.Guardian
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RegisterStudentService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val users: UserPort,
    private val memberships: MembershipPort,
    private val clock: ClockPort,
) : RegisterStudentUseCase {

    @Transactional
    override fun register(command: RegisterStudentUseCase.Command): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val classroom = classrooms.find(command.classroomId, command.institutionId) ?: throw NotFoundException("반")
        if (command.guardians.isEmpty()) throw InvalidInputException("GUARDIAN_REQUIRED", "보호자 연락처를 1개 이상 입력하세요")
        if (enrollments.findCurrentStudentIds(classroom.id).size >= classroom.capacity)
            throw InvalidInputException("CLASS_FULL", "반 정원(${classroom.capacity}명)이 찼습니다")

        val student = students.save(Student(StudentId.new(), command.institutionId, command.name.trim(), command.birthDate, command.grade))
        enrollments.save(Enrollment(student.id, classroom.id, clock.today()))

        val saved = command.guardians.distinctBy { PhoneNumber.of(it.phone) }.map { input ->
            val phone = PhoneNumber.of(input.phone)
            var g = Guardian(GuardianId.new(), command.institutionId, student.id, phone, input.relation, input.isPrimary)
            // 이미 가입한 학부모(다른 기관·형제 등록 등)면 즉시 연결
            users.findByPhone(phone)?.let { parent ->
                g = g.linkTo(parent.id)
                if (memberships.find(parent.id, command.institutionId) == null)
                    memberships.save(Membership(MembershipId.new(), parent.id, command.institutionId, Role.PARENT))
            }
            guardians.save(g)
        }
        // TODO(S3): 미가입 보호자에게 앱 설치 안내 알림톡 발송 (STU-003) — Outbox 이벤트로
        return toView(student, listOf(classroom.id), saved, masked = false)
    }
}

@Service
class ListStudentsService(
    private val guard: AccessGuard,
    private val students: StudentPort,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
) : ListStudentsQuery {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId?): List<StudentView> {
        val m = guard.requireStaff(actor, institutionId)
        val visibleClassIds: Set<ClassroomId>? =
            if (m.role.isManager) null
            else classrooms.findByInstitution(institutionId).filter { it.isTaughtBy(actor) }.map { it.id }.toSet()

        val targetIds: List<StudentId> = when {
            classroomId != null -> {
                if (visibleClassIds != null && classroomId !in visibleClassIds) return emptyList()
                enrollments.findCurrentStudentIds(classroomId)
            }
            visibleClassIds != null -> visibleClassIds.flatMap { enrollments.findCurrentStudentIds(it) }.distinct()
            else -> students.findByInstitution(institutionId).map { it.id }
        }
        return students.findAllByIds(targetIds)
            .filter { it.institutionId == institutionId }
            .sortedBy { it.name }
            .map { s ->
                // SEC-003: 일반 교사에게는 연락처·생년월일 마스킹
                toView(s, enrollments.findCurrent(s.id).map { it.classroomId }, guardians.findByStudent(s.id), masked = !m.role.isManager)
            }
    }
}

internal fun toView(s: Student, classIds: List<ClassroomId>, gs: List<Guardian>, masked: Boolean) = StudentView(
    id = s.id, name = s.name, birthDate = if (masked) null else s.birthDate, grade = s.grade, status = s.status,
    classroomIds = classIds,
    guardians = gs.map { GuardianView(it.phone.masked, it.relation, it.isPrimary, it.linkStatus) },
)
