package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ClassroomSummaryQuery
import com.ttokttok.application.port.`in`.ListStaffQuery
import com.ttokttok.application.port.`in`.ManageClassroomUseCase
import com.ttokttok.application.port.`in`.PasswordUseCase
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.PasswordHasherPort
import com.ttokttok.application.port.out.SendEmailPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

@Service
class ManageClassroomService(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val memberships: MembershipPort,
    private val clock: ClockPort,
) : ManageClassroomUseCase {

    @Transactional
    override fun update(command: ManageClassroomUseCase.UpdateCommand): Classroom {
        guard.requireManager(command.actor, command.institutionId)
        val c = load(command.institutionId, command.classroomId)
        val headcount = enrollments.findCurrentStudentIds(c.id).size
        return classrooms.save(c.update(command.name, command.capacity, command.days, command.startTime, command.endTime, headcount))
    }

    /** STF-003 담당 교사 배정 (다중 배정 가능) */
    @Transactional
    override fun assignTeachers(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, teacherIds: Set<UserId>): Classroom {
        guard.requireManager(actor, institutionId)
        val c = load(institutionId, classroomId)
        teacherIds.forEach { t ->
            val m = memberships.find(t, institutionId)
            if (m == null || !m.role.isStaff) throw InvalidInputException("INVALID_TEACHER", "이 기관의 교직원만 담당으로 지정할 수 있습니다")
        }
        return classrooms.save(c.assignTeachers(teacherIds))
    }

    /** CLS-002 소속 학생 0명일 때만 (소프트) 삭제 */
    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId) {
        guard.requireManager(actor, institutionId)
        val c = load(institutionId, classroomId)
        c.ensureDeletable(enrollments.findCurrentStudentIds(c.id).size)
        classrooms.softDelete(c.id, institutionId, clock.now())
    }

    private fun load(institutionId: InstitutionId, id: ClassroomId) = classrooms.find(id, institutionId) ?: throw NotFoundException("반")
}

@Service
class ClassroomSummaryService(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val users: UserPort,
) : ClassroomSummaryQuery {
    @Transactional(readOnly = true)
    override fun summaries(actor: UserId, institutionId: InstitutionId): List<ClassroomSummaryQuery.ClassroomSummary> {
        val m = guard.requireStaff(actor, institutionId)
        val list = classrooms.findByInstitution(institutionId).let { all -> if (m.role.isManager) all else all.filter { it.isTaughtBy(actor) } }
        val names = list.flatMap { it.teacherIds }.distinct().mapNotNull { users.findById(it) }.associate { it.id to it.name }
        return list.sortedBy { it.name }.map { c ->
            ClassroomSummaryQuery.ClassroomSummary(c, enrollments.findCurrentStudentIds(c.id).size, c.teacherIds.mapNotNull { names[it] }.sorted())
        }
    }
}

/** STF-001 교직원 목록 + 담당 반 */
@Service
class ListStaffService(
    private val guard: AccessGuard,
    private val memberships: MembershipPort,
    private val users: UserPort,
    private val classrooms: ClassroomPort,
) : ListStaffQuery {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<ListStaffQuery.StaffItem> {
        guard.requireManager(actor, institutionId)
        val classes = classrooms.findByInstitution(institutionId)
        return memberships.findByInstitution(institutionId).filter { it.role.isStaff }.mapNotNull { m ->
            val u = users.findById(m.userId) ?: return@mapNotNull null
            ListStaffQuery.StaffItem(
                u.id, u.name, u.email, m.role, m.title,
                classes.filter { it.isTaughtBy(u.id) }.sortedBy { it.name }.map { it.id to it.name },
            )
        }.sortedWith(compareBy({ it.role.ordinal }, { it.name }))
    }
}

/** AUTH-004 임시 비밀번호 / AUTH-005 비밀번호 변경 */
@Service
class PasswordService(
    private val users: UserPort,
    private val hasher: PasswordHasherPort,
    private val email: SendEmailPort,
    private val sessions: com.ttokttok.application.port.out.RefreshTokenPort,
    private val clock: com.ttokttok.application.port.out.ClockPort,
) : PasswordUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    override fun issueTemporary(email: String) {
        val user = runCatching { users.findByEmail(normalizeEmail(email)) }.getOrNull()
        if (user == null) {
            log.info("임시 비밀번호 요청: 미가입 이메일") // 존재 여부를 응답으로 노출하지 않음
            return
        }
        val temp = temporaryPassword()
        users.save(user.withPassword(hasher.hash(temp), mustChange = true))
        sessions.revokeAllForUser(user.id, clock.now()) // 기존 로그인 세션 모두 종료
        this.email.send(
            user.email!!, "[똑똑] 임시 비밀번호 안내",
            "${user.name}님, 임시 비밀번호는 $temp 입니다.\n로그인 후 바로 새 비밀번호로 변경해 주세요.",
        )
    }

    @Transactional
    override fun change(actor: UserId, currentPassword: String, newPassword: String) {
        val user = users.findById(actor) ?: throw NotFoundException("계정")
        if (!hasher.matches(currentPassword, user.passwordHash)) throw UnauthenticatedException("현재 비밀번호가 올바르지 않습니다")
        if (currentPassword == newPassword) throw InvalidInputException("SAME_PASSWORD", "이전과 다른 비밀번호를 입력하세요")
        validatePassword(newPassword)
        users.save(user.withPassword(hasher.hash(newPassword), mustChange = false))
        sessions.revokeAllForUser(actor, clock.now()) // 다른 기기 세션 종료 (현재 기기는 다시 로그인)
    }

    /** 영문 대소문자+숫자 10자 — 혼동 문자(0/O, 1/l) 제외 */
    private fun temporaryPassword(): String {
        val letters = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val chars = (1..7).map { letters[random.nextInt(letters.length)] } + (1..3).map { digits[random.nextInt(digits.length)] }
        return chars.shuffled(random).joinToString("")
    }
}
