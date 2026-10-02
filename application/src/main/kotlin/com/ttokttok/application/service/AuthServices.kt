package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.CreateStaffUseCase
import com.ttokttok.application.port.`in`.LoadAuthenticatedUserQuery
import com.ttokttok.application.port.`in`.LoginUseCase
import com.ttokttok.application.port.`in`.MembershipView
import com.ttokttok.application.port.`in`.RegisterParentUseCase
import com.ttokttok.application.port.`in`.SignUpInstitutionUseCase
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.PasswordHasherPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.institution.Institution
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import com.ttokttok.domain.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

internal fun validatePassword(raw: String) {
    if (raw.length < 8 || raw.none { it.isDigit() } || raw.none { it.isLetter() })
        throw InvalidInputException("WEAK_PASSWORD", "비밀번호는 영문·숫자 포함 8자 이상이어야 합니다")
}

internal fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) throw InvalidInputException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다")
    return email
}

@Service
class AuthenticatedUserAssembler(
    private val memberships: MembershipPort,
    private val institutions: InstitutionPort,
) {
    fun assemble(user: User): AuthenticatedUser {
        val ms = memberships.findByUser(user.id)
        val names = institutions.findAllByIds(ms.map { it.institutionId }).associate { it.id to it.name }
        return AuthenticatedUser(
            userId = user.id, name = user.name, mustChangePassword = user.mustChangePassword,
            memberships = ms.map { MembershipView(it.institutionId, names[it.institutionId] ?: "", it.role) },
        )
    }
}

@Service
class SignUpInstitutionService(
    private val users: UserPort,
    private val institutions: InstitutionPort,
    private val memberships: MembershipPort,
    private val hasher: PasswordHasherPort,
    private val assembler: AuthenticatedUserAssembler,
) : SignUpInstitutionUseCase {
    @Transactional
    override fun signUp(command: SignUpInstitutionUseCase.Command): AuthenticatedUser {
        val email = normalizeEmail(command.email)
        validatePassword(command.password)
        if (users.findByEmail(email) != null) throw ConflictException("EMAIL_TAKEN", "이미 가입된 이메일입니다")

        val institution = institutions.save(Institution(InstitutionId.new(), command.institutionName.trim(), command.ownerName.trim()))
        val owner = users.save(User(UserId.new(), command.ownerName.trim(), email, null, hasher.hash(command.password)))
        memberships.save(Membership(MembershipId.new(), owner.id, institution.id, Role.OWNER, "원장"))
        return assembler.assemble(owner)
    }
}

@Service
class RegisterParentService(
    private val users: UserPort,
    private val guardians: GuardianPort,
    private val memberships: MembershipPort,
    private val hasher: PasswordHasherPort,
    private val assembler: AuthenticatedUserAssembler,
) : RegisterParentUseCase {
    // TODO(S2): SMS 본인인증 토큰 검증 후에만 가입 허용 (Open Issue #1)
    @Transactional
    override fun register(command: RegisterParentUseCase.Command): AuthenticatedUser {
        val phone = PhoneNumber.of(command.phone)
        validatePassword(command.password)
        if (users.findByPhone(phone) != null) throw ConflictException("PHONE_TAKEN", "이미 가입된 휴대폰 번호입니다")
        val parent = users.save(User(UserId.new(), command.name.trim(), null, phone, hasher.hash(command.password)))
        linkGuardians(parent, phone, guardians, memberships)
        return assembler.assemble(parent)
    }
}

/** 같은 번호로 등록된 보호자 행을 모두 연결하고, 기관별 PARENT 소속을 만든다. 기관·형제 불문. */
internal fun linkGuardians(parent: User, phone: PhoneNumber, guardians: GuardianPort, memberships: MembershipPort) {
    // UNLINKED(관리자가 해제한) 매핑은 재가입으로 되살리지 않는다
    guardians.findByPhone(phone).filter { it.linkStatus == com.ttokttok.domain.student.GuardianLinkStatus.PENDING }.forEach { g ->
        guardians.save(g.linkTo(parent.id))
        if (memberships.find(parent.id, g.institutionId) == null) {
            memberships.save(Membership(MembershipId.new(), parent.id, g.institutionId, Role.PARENT))
        }
    }
}

@Service
class LoginService(
    private val users: UserPort,
    private val hasher: PasswordHasherPort,
    private val assembler: AuthenticatedUserAssembler,
    private val attempts: com.ttokttok.application.port.out.LoginAttemptPort,
    private val clock: com.ttokttok.application.port.out.ClockPort,
) : LoginUseCase {
    /** 실패 기록은 예외를 던져도 남아야 하므로 롤백하지 않는다 */
    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    override fun login(command: LoginUseCase.Command): AuthenticatedUser {
        val id = command.loginId.trim()
        val user = if (id.contains("@")) users.findByEmail(id.lowercase())
        else runCatching { PhoneNumber.of(id) }.getOrNull()?.let { users.findByPhone(it) }
        // 계정 존재 여부를 노출하지 않도록 같은 메시지 사용
        if (user == null) throw UnauthenticatedException("아이디 또는 비밀번호가 올바르지 않습니다")

        val now = clock.now()
        val attempt = attempts.find(user.id) ?: com.ttokttok.domain.user.LoginAttempt(user.id)
        if (attempt.isLocked(now)) {
            throw ConflictException("ACCOUNT_LOCKED", "로그인에 5회 실패해 10분간 잠겼습니다. 잠시 후 다시 시도하거나 비밀번호를 재설정하세요")
        }
        if (!hasher.matches(command.password, user.passwordHash)) {
            val failed = attempt.recordFailure(now)
            attempts.save(failed)
            throw UnauthenticatedException(
                if (failed.isLocked(now)) "로그인에 5회 실패해 10분간 잠겼습니다"
                // 남은 횟수는 알려주지 않는다 — 없는 계정과 응답이 달라져 존재 여부가 드러남
                else "아이디 또는 비밀번호가 올바르지 않습니다",
            )
        }
        if (attempt.failedCount > 0) attempts.save(com.ttokttok.domain.user.LoginAttempt(user.id))
        return assembler.assemble(user)
    }
}

@Service
class LoadAuthenticatedUserService(
    private val users: UserPort,
    private val assembler: AuthenticatedUserAssembler,
) : LoadAuthenticatedUserQuery {
    @Transactional(readOnly = true)
    override fun load(userId: UserId): AuthenticatedUser =
        assembler.assemble(users.findById(userId) ?: throw UnauthenticatedException())
}

@Service
class CreateStaffService(
    private val guard: AccessGuard,
    private val users: UserPort,
    private val memberships: MembershipPort,
    private val hasher: PasswordHasherPort,
) : CreateStaffUseCase {
    @Transactional
    override fun create(command: CreateStaffUseCase.Command): CreateStaffUseCase.StaffView {
        guard.require(command.actor, command.institutionId, Role.OWNER)
        if (command.role !in setOf(Role.ADMIN, Role.TEACHER)) throw InvalidInputException("INVALID_ROLE", "직원 권한은 ADMIN 또는 TEACHER입니다")
        val email = normalizeEmail(command.email)
        validatePassword(command.temporaryPassword)

        val user = users.findByEmail(email)
            ?: users.save(User(UserId.new(), command.name.trim(), email, null, hasher.hash(command.temporaryPassword), mustChangePassword = true))
        if (memberships.find(user.id, command.institutionId) != null) throw ConflictException("ALREADY_MEMBER", "이미 소속된 직원입니다")
        memberships.save(Membership(MembershipId.new(), user.id, command.institutionId, command.role, command.title))
        return CreateStaffUseCase.StaffView(user.id, user.name, email, command.role)
    }
}
