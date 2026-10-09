package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.LoginChallengeUseCase
import com.ttokttok.application.port.`in`.OtpSetup
import com.ttokttok.application.port.`in`.OtpStatus
import com.ttokttok.application.port.`in`.OtpUseCase
import com.ttokttok.application.port.`in`.SampleInstitutionUseCase
import com.ttokttok.application.port.`in`.SampleInstitutionView
import com.ttokttok.application.port.`in`.SessionPolicyUseCase
import com.ttokttok.application.port.`in`.SocialLinkView
import com.ttokttok.application.port.`in`.SocialLoginResult
import com.ttokttok.application.port.`in`.SocialLoginUseCase
import com.ttokttok.application.port.`in`.SocialProvider
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.LoginChallengePort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.OtpPort
import com.ttokttok.application.port.out.PasswordHasherPort
import com.ttokttok.application.port.out.SampleDataPort
import com.ttokttok.application.port.out.SessionPolicyPort
import com.ttokttok.application.port.out.SocialAccountPort
import com.ttokttok.application.port.out.SocialProfilePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.institution.Institution
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import com.ttokttok.domain.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30초, 6자리) — Google Authenticator·Microsoft Authenticator 등과 호환.
 * 앞뒤 1구간(±30초)까지 허용하고, 이미 쓴 구간(lastStep 이하)은 받지 않는다.
 */
object Totp {
    private const val PERIOD = 30L
    private const val DIGITS = 6
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(): String = base32(ByteArray(20).also(random::nextBytes))

    fun step(now: Instant): Long = now.epochSecond / PERIOD

    /** 맞으면 그 구간 번호, 틀리면 null */
    fun verify(secret: String, code: String, now: Instant, lastStep: Long): Long? {
        val digits = code.filter { it.isDigit() }
        if (digits.length != DIGITS) return null
        val key = decode(secret)
        val current = step(now)
        for (d in -1L..1L) {
            val s = current + d
            if (s <= lastStep) continue
            if (generate(key, s) == digits) return s
        }
        return null
    }

    private fun generate(key: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    fun uri(secret: String, account: String): String {
        val issuer = "똑똑에듀"
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD"
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it in ALPHABET }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** 복구 코드 8개 (XXXXX-XXXXX, 혼동되는 글자 제외) */
    fun recoveryCodes(): List<String> {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return List(8) { (1..10).map { chars[random.nextInt(chars.length)] }.joinToString("").let { "${it.take(5)}-${it.drop(5)}" } }
    }

    fun normalizeRecovery(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
}

@Service
class OtpService(
    private val otp: OtpPort,
    private val users: UserPort,
    private val hasher: PasswordHasherPort,
    private val clock: ClockPort,
) : OtpUseCase {

    @Transactional(readOnly = true)
    override fun status(actor: UserId): OtpStatus {
        val r = otp.find(actor)
        return if (r == null || !r.enabled) OtpStatus(false, null, 0) else OtpStatus(true, r.enabledAt, r.recoveryHashes.size)
    }

    @Transactional
    override fun setup(actor: UserId): OtpSetup {
        val user = users.findById(actor) ?: throw UnauthenticatedException()
        if (otp.find(actor)?.enabled == true) throw ConflictException("OTP_ALREADY_ENABLED", "이미 2단계 인증을 쓰고 있습니다. 다시 등록하려면 먼저 해제해 주세요")
        val secret = Totp.newSecret()
        otp.saveSecret(actor, secret, clock.now())
        return OtpSetup(secret, Totp.uri(secret, user.email ?: user.phone?.masked ?: user.name))
    }

    @Transactional
    override fun enable(actor: UserId, code: String): List<String> {
        val r = otp.find(actor) ?: throw InvalidInputException("OTP_NOT_SETUP", "먼저 인증 앱에 등록해 주세요")
        if (r.enabled) throw ConflictException("OTP_ALREADY_ENABLED", "이미 2단계 인증을 쓰고 있습니다")
        val now = clock.now()
        val step = Totp.verify(r.secret, code, now, r.lastStep) ?: throw InvalidInputException("OTP_INVALID", "인증 코드가 올바르지 않습니다. 휴대폰 시간이 맞는지도 확인해 주세요")
        val codes = Totp.recoveryCodes()
        otp.enable(actor, codes.map { otp.hashRecovery(Totp.normalizeRecovery(it)) }, step, now)
        return codes
    }

    @Transactional
    override fun disable(actor: UserId, password: String, code: String) {
        val user = users.findById(actor) ?: throw UnauthenticatedException()
        if (!hasher.matches(password, user.passwordHash)) throw InvalidInputException("WRONG_PASSWORD", "비밀번호가 올바르지 않습니다")
        val r = otp.find(actor)?.takeIf { it.enabled } ?: return
        val now = clock.now()
        val ok = Totp.verify(r.secret, code, now, r.lastStep) != null ||
            otp.hashRecovery(Totp.normalizeRecovery(code)) in r.recoveryHashes
        if (!ok) throw InvalidInputException("OTP_INVALID", "인증 코드가 올바르지 않습니다")
        otp.delete(actor)
    }

    @Transactional
    override fun regenerateRecoveryCodes(actor: UserId, code: String): List<String> {
        val r = otp.find(actor)?.takeIf { it.enabled } ?: throw InvalidInputException("OTP_NOT_ENABLED", "2단계 인증을 쓰고 있지 않습니다")
        val step = Totp.verify(r.secret, code, clock.now(), r.lastStep) ?: throw InvalidInputException("OTP_INVALID", "인증 코드가 올바르지 않습니다")
        otp.markStep(actor, step)
        val codes = Totp.recoveryCodes()
        otp.setRecovery(actor, codes.map { otp.hashRecovery(Totp.normalizeRecovery(it)) })
        return codes
    }
}

@Service
class LoginChallengeService(
    private val otp: OtpPort,
    private val challenges: LoginChallengePort,
    private val users: UserPort,
    private val assembler: AuthenticatedUserAssembler,
    private val clock: ClockPort,
) : LoginChallengeUseCase {

    @Transactional
    override fun challengeIfRequired(userId: UserId, rememberMe: Boolean): LoginChallengeUseCase.Challenge? {
        if (otp.find(userId)?.enabled != true) return null
        val expires = clock.now().plus(TTL)
        return LoginChallengeUseCase.Challenge(challenges.create(userId, rememberMe, expires), expires)
    }

    /** 실패 횟수는 예외를 던져도 남아야 한다 */
    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    override fun verify(challengeId: UUID, code: String): LoginChallengeUseCase.Passed {
        val now = clock.now()
        val c = challenges.find(challengeId)
        if (c == null || c.usedAt != null || c.expiresAt.isBefore(now)) throw UnauthenticatedException("인증 시간이 지났습니다. 처음부터 다시 로그인해 주세요")
        if (c.attempts >= MAX_ATTEMPTS) throw UnauthenticatedException("인증 코드를 여러 번 틀렸습니다. 처음부터 다시 로그인해 주세요")
        val r = otp.find(c.userId)?.takeIf { it.enabled }
        if (r == null) {
            // 그 사이 OTP 를 해제한 경우
            challenges.markUsed(challengeId, now)
            return LoginChallengeUseCase.Passed(load(c.userId), c.rememberMe)
        }
        val step = Totp.verify(r.secret, code, now, r.lastStep)
        if (step != null) {
            otp.markStep(c.userId, step)
        } else {
            val hash = otp.hashRecovery(Totp.normalizeRecovery(code))
            if (code.filter { it.isLetterOrDigit() }.length != 10 || hash !in r.recoveryHashes) {
                challenges.incrementAttempts(challengeId)
                throw UnauthenticatedException("인증 코드가 올바르지 않습니다")
            }
            otp.setRecovery(c.userId, r.recoveryHashes - hash)
        }
        challenges.markUsed(challengeId, now)
        return LoginChallengeUseCase.Passed(load(c.userId), c.rememberMe)
    }

    private fun load(userId: UserId): AuthenticatedUser = assembler.assemble(users.findById(userId) ?: throw UnauthenticatedException())

    companion object {
        private val TTL: Duration = Duration.ofMinutes(5)
        private const val MAX_ATTEMPTS = 5
    }
}

/** 카카오 "+82 10-1234-5678" · 네이버 "010-1234-5678" → 01012345678 */
internal fun normalizeSocialPhone(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val digits = raw.filter { it.isDigit() }
    val local = if (raw.trim().startsWith("+82") && digits.startsWith("82")) "0" + digits.drop(2) else digits
    return runCatching { PhoneNumber.of(local).digits }.getOrNull()
}

@Service
class SocialLoginService(
    private val profiles: SocialProfilePort,
    private val social: SocialAccountPort,
    private val users: UserPort,
    private val hasher: PasswordHasherPort,
    private val assembler: AuthenticatedUserAssembler,
    private val guardians: GuardianPort,
    private val memberships: MembershipPort,
    private val students: StudentPort,
    private val childLinker: ChildLinker,
    private val clock: ClockPort,
) : SocialLoginUseCase {

    override fun enabledProviders(): List<SocialProvider> = SocialProvider.entries.filter { profiles.enabled(it) }

    @Transactional
    override fun login(provider: SocialProvider, accessToken: String): SocialLoginResult {
        val profile = fetch(provider, accessToken)
        val linked = social.findUser(provider, profile.providerUserId)
        if (linked != null) {
            val user = users.findById(linked) ?: throw UnauthenticatedException()
            return SocialLoginResult.LoggedIn(assembler.assemble(user))
        }
        val id = social.createSignup(provider, profile, clock.now().plus(Duration.ofMinutes(30)))
        return SocialLoginResult.SignupRequired(id, profile.name, profile.email, normalizeSocialPhone(profile.phone))
    }

    @Transactional
    override fun signupParent(signupToken: UUID, name: String, phone: String): AuthenticatedUser {
        val now = clock.now()
        val rec = social.findSignup(signupToken)
        if (rec == null || rec.usedAt != null || rec.expiresAt.isBefore(now)) throw UnauthenticatedException("가입 시간이 지났습니다. 다시 소셜 로그인해 주세요")
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 50) throw InvalidInputException("INVALID_NAME", "이름은 1~50자입니다")
        val p = PhoneNumber.of(normalizeSocialPhone(phone) ?: phone)
        if (users.findByPhone(p) != null) {
            throw ConflictException("PHONE_TAKEN", "이미 가입된 휴대폰 번호입니다. 비밀번호로 로그인한 뒤 '더보기 > 간편 로그인 연결'에서 연결해 주세요")
        }
        if (social.findUser(rec.provider, rec.providerUserId) != null) throw ConflictException("SOCIAL_TAKEN", "이미 가입된 소셜 계정입니다. 다시 로그인해 주세요")
        if (!social.useSignup(signupToken, now)) throw UnauthenticatedException("가입 시간이 지났습니다. 다시 소셜 로그인해 주세요")

        // 비밀번호 로그인은 쓰지 않는 계정 — 추측할 수 없는 값으로 채운다
        val parent = users.save(User(UserId.new(), cleanName, null, p, hasher.hash(UUID.randomUUID().toString() + UUID.randomUUID())))
        linkGuardians(parent, p, guardians, memberships) { g ->
            students.find(g.studentId, g.institutionId)?.let { childLinker.onGuardianLinked(it, parent.id) }
        }
        social.link(rec.provider, rec.providerUserId, parent.id, rec.email, true, now)
        return assembler.assemble(parent)
    }

    @Transactional(readOnly = true)
    override fun links(actor: UserId): List<SocialLinkView> = social.links(actor)

    @Transactional
    override fun link(actor: UserId, provider: SocialProvider, accessToken: String): List<SocialLinkView> {
        val profile = fetch(provider, accessToken)
        val owner = social.findUser(provider, profile.providerUserId)
        if (owner != null && owner != actor) throw ConflictException("SOCIAL_TAKEN", "이 ${label(provider)} 계정은 이미 다른 똑똑 계정에 연결돼 있습니다")
        if (owner == null) {
            social.unlink(actor, provider)   // 같은 종류의 다른 계정이 연결돼 있었다면 교체
            social.link(provider, profile.providerUserId, actor, profile.email, false, clock.now())
        }
        return social.links(actor)
    }

    @Transactional
    override fun unlink(actor: UserId, provider: SocialProvider): List<SocialLinkView> {
        if (social.isSignupLink(actor, provider)) {
            throw ConflictException("SIGNUP_LINK", "${label(provider)}로 가입한 계정이라 연결을 해제할 수 없습니다")
        }
        social.unlink(actor, provider)
        return social.links(actor)
    }

    private fun fetch(provider: SocialProvider, accessToken: String) =
        if (!profiles.enabled(provider)) throw InvalidInputException("SOCIAL_DISABLED", "${label(provider)} 로그인은 아직 준비 중입니다")
        else profiles.fetch(provider, accessToken.trim())

    private fun label(p: SocialProvider) = if (p == SocialProvider.KAKAO) "카카오" else "네이버"
}

@Service
class SessionPolicyService(
    private val guard: AccessGuard,
    private val policy: SessionPolicyPort,
) : SessionPolicyUseCase {
    @Transactional(readOnly = true)
    override fun idleMinutes(actor: UserId, institutionId: InstitutionId): Int {
        guard.requireStaff(actor, institutionId)
        return policy.idleMinutes(institutionId)
    }

    @Transactional
    override fun setIdleMinutes(actor: UserId, institutionId: InstitutionId, minutes: Int): Int {
        guard.requireManager(actor, institutionId)
        if (minutes != 0 && minutes !in 5..480) throw InvalidInputException("INVALID_MINUTES", "자동 로그아웃은 5~480분 또는 0(끔)입니다")
        policy.setIdleMinutes(institutionId, minutes)
        return minutes
    }
}

@Service
class SampleInstitutionService(
    private val guard: AccessGuard,
    private val users: UserPort,
    private val institutions: InstitutionPort,
    private val memberships: MembershipPort,
    private val samples: SampleDataPort,
    private val clock: ClockPort,
) : SampleInstitutionUseCase {

    @Transactional
    override fun create(actor: UserId): SampleInstitutionView {
        samples.sampleOf(actor)?.let { return SampleInstitutionView(it.first, it.second) }
        if (memberships.findByUser(actor).none { it.role.isStaff }) throw ForbiddenException("기관 관리자 계정에서만 체험할 수 있습니다")
        val user = users.findById(actor) ?: throw UnauthenticatedException()
        val inst = institutions.save(Institution(InstitutionId.new(), SAMPLE_NAME, user.name))
        samples.markSample(inst.id)
        memberships.save(Membership(MembershipId.new(), actor, inst.id, Role.OWNER, "원장"))
        samples.seed(inst.id, actor, clock.today(), clock.now())
        return SampleInstitutionView(inst.id.value, inst.name)
    }

    @Transactional(readOnly = true)
    override fun mine(actor: UserId): SampleInstitutionView? = samples.sampleOf(actor)?.let { SampleInstitutionView(it.first, it.second) }

    @Transactional
    override fun remove(actor: UserId, institutionId: UUID) {
        if (!samples.isSample(institutionId)) throw NotFoundException("체험 기관")
        guard.require(actor, InstitutionId(institutionId), Role.OWNER)
        samples.archive(institutionId, clock.now())
    }

    companion object {
        const val SAMPLE_NAME = "[체험] 똑똑 샘플학원"
    }
}
