package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GuardianView
import com.ttokttok.application.port.`in`.RegisterStudentUseCase
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.GuardianMessageRequested
import com.ttokttok.domain.messaging.MessageTemplate
import com.ttokttok.domain.student.Enrollment
import com.ttokttok.domain.student.Guardian
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 원생 생성 공통 절차 — 개별 등록(STU-003), 가입 승인(STU-004), 엑셀 확정(STU-002)이 모두 사용.
 * 정원 확인 → 원생·반 소속 → 보호자 매핑(가입자면 즉시 연결) → 미가입 보호자에게 설치 안내(Outbox)
 */
@Component
class StudentCreator(
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val guardians: GuardianPort,
    private val users: UserPort,
    private val memberships: MembershipPort,
    private val institutions: InstitutionPort,
    private val outbox: OutboxPort,
    private val links: AppLinksPort,
    private val clock: ClockPort,
) {
    data class GuardianSpec(val phone: PhoneNumber, val relation: String?, val isPrimary: Boolean)
    data class Created(val student: Student, val guardians: List<Guardian>)

    fun create(
        institutionId: InstitutionId, classroom: Classroom, name: String, birthDate: LocalDate,
        grade: String?, memo: String?, guardianSpecs: List<GuardianSpec>, sendInstallGuide: Boolean,
    ): Created {
        if (guardianSpecs.isEmpty()) throw InvalidInputException("GUARDIAN_REQUIRED", "보호자 연락처를 1개 이상 입력하세요")
        ensureCapacity(classroom, enrollments)

        val student = students.save(Student(StudentId.new(), institutionId, name.trim(), birthDate, grade, memo = memo))
        enrollments.save(Enrollment(student.id, classroom.id, clock.today()))
        val saved = guardianSpecs.distinctBy { it.phone }.map { addGuardian(student, it, sendInstallGuide) }
        return Created(student, saved)
    }

    fun addGuardian(student: Student, spec: GuardianSpec, sendInstallGuide: Boolean): Guardian {
        var g = Guardian(GuardianId.new(), student.institutionId, student.id, spec.phone, spec.relation, spec.isPrimary)
        val parent = users.findByPhone(spec.phone)
        if (parent != null) {
            g = g.linkTo(parent.id)
            if (memberships.find(parent.id, student.institutionId) == null)
                memberships.save(Membership(MembershipId.new(), parent.id, student.institutionId, Role.PARENT))
        } else if (sendInstallGuide) {
            val inst = institutions.findById(student.institutionId) ?: throw NotFoundException("기관")
            outbox.publish(
                GuardianMessageRequested(
                    student.institutionId, spec.phone, MessageTemplate.APP_INSTALL_GUIDE,
                    mapOf("institutionName" to inst.name, "studentName" to student.name, "installUrl" to links.appInstallUrl()),
                    clock.now(),
                ),
            )
        }
        return guardians.save(g)
    }
}

internal fun ensureCapacity(classroom: Classroom, enrollments: EnrollmentPort) {
    if (enrollments.findCurrentStudentIds(classroom.id).size >= classroom.capacity)
        throw InvalidInputException("CLASS_FULL", "${classroom.name} 정원(${classroom.capacity}명)이 찼습니다")
}

@Service
class RegisterStudentService(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
    private val creator: StudentCreator,
) : RegisterStudentUseCase {

    @Transactional
    override fun register(command: RegisterStudentUseCase.Command): StudentView {
        guard.requireManager(command.actor, command.institutionId)
        val classroom = classrooms.find(command.classroomId, command.institutionId) ?: throw NotFoundException("반")
        val created = creator.create(
            command.institutionId, classroom, command.name, command.birthDate, command.grade, command.memo,
            command.guardians.map { StudentCreator.GuardianSpec(PhoneNumber.of(it.phone), it.relation, it.isPrimary) },
            command.sendInstallGuide,
        )
        return toView(created.student, listOf(classroom.id), created.guardians, masked = false)
    }
}

internal fun toView(s: Student, classIds: List<ClassroomId>, gs: List<Guardian>, masked: Boolean) = StudentView(
    id = s.id, name = s.name, birthDate = if (masked) null else s.birthDate, grade = s.grade, status = s.status,
    classroomIds = classIds,
    guardians = gs.map { GuardianView(it.id, it.phone.masked, it.relation, it.isPrimary, it.linkStatus) },
)
