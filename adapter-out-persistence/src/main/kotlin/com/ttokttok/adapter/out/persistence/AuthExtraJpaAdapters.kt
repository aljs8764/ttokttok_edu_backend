package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.AttendanceDayEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomEntity
import com.ttokttok.adapter.out.persistence.entity.DestinationEntity
import com.ttokttok.adapter.out.persistence.entity.EnrollmentEntity
import com.ttokttok.adapter.out.persistence.entity.LoginChallengeEntity
import com.ttokttok.adapter.out.persistence.entity.SocialSignupEntity
import com.ttokttok.adapter.out.persistence.entity.StudentEntity
import com.ttokttok.adapter.out.persistence.entity.UserOtpEntity
import com.ttokttok.adapter.out.persistence.entity.UserSocialEntity
import com.ttokttok.adapter.out.persistence.entity.UserSocialKey
import com.ttokttok.adapter.out.persistence.repository.InstitutionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.LoginChallengeJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SocialSignupJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UserOtpJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UserSocialJpaRepository
import com.ttokttok.application.port.`in`.SocialLinkView
import com.ttokttok.application.port.`in`.SocialProvider
import com.ttokttok.application.port.out.LoginChallengePort
import com.ttokttok.application.port.out.LoginChallengeRecord
import com.ttokttok.application.port.out.OtpPort
import com.ttokttok.application.port.out.OtpRecord
import com.ttokttok.application.port.out.SampleDataPort
import com.ttokttok.application.port.out.SessionPolicyPort
import com.ttokttok.application.port.out.SocialAccountPort
import com.ttokttok.application.port.out.SocialProfile
import com.ttokttok.application.port.out.SocialSignupRecord
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

// ───────── AUTH-007 ─────────
@Component
class OtpJpaAdapter(private val repo: UserOtpJpaRepository, private val crypto: FieldCrypto) : OtpPort {

    override fun find(userId: UserId): OtpRecord? =
        repo.findByIdOrNull(userId.value)?.let {
            OtpRecord(crypto.decrypt(it.secretEnc), it.enabled, it.lastStep, it.recoveryHashes.split(',').filter { h -> h.isNotBlank() }, it.enabledAt)
        }

    override fun saveSecret(userId: UserId, secret: String, now: Instant) {
        // 새로 등록하면 이전 설정(활성화·복구 코드)은 모두 초기화
        val e = repo.findByIdOrNull(userId.value) ?: UserOtpEntity(userId.value, crypto.encrypt(secret), updatedAt = now)
        e.secretEnc = crypto.encrypt(secret)
        e.enabled = false
        e.lastStep = 0
        e.recoveryHashes = ""
        e.enabledAt = null
        e.updatedAt = now
        repo.save(e)
    }

    override fun enable(userId: UserId, recoveryHashes: List<String>, step: Long, now: Instant) {
        val e = repo.findByIdOrNull(userId.value) ?: return
        e.enabled = true
        e.lastStep = step
        e.recoveryHashes = recoveryHashes.joinToString(",")
        e.enabledAt = now
        e.updatedAt = now
        repo.save(e)
    }

    override fun markStep(userId: UserId, step: Long) {
        val e = repo.findByIdOrNull(userId.value) ?: return
        e.lastStep = maxOf(e.lastStep, step)
        repo.save(e)
    }

    override fun setRecovery(userId: UserId, recoveryHashes: List<String>) {
        val e = repo.findByIdOrNull(userId.value) ?: return
        e.recoveryHashes = recoveryHashes.joinToString(",")
        repo.save(e)
    }

    override fun delete(userId: UserId) {
        if (repo.existsById(userId.value)) repo.deleteById(userId.value)
    }

    override fun hashRecovery(code: String): String = crypto.hash("otp-recovery:$code")
}

@Component
class LoginChallengeJpaAdapter(private val repo: LoginChallengeJpaRepository) : LoginChallengePort {

    override fun create(userId: UserId, rememberMe: Boolean, expiresAt: Instant): UUID {
        val id = UUID.randomUUID()   // 추측할 수 없어야 하므로 시간 순 UUID 를 쓰지 않는다
        repo.save(LoginChallengeEntity(id, userId.value, rememberMe, expiresAt = expiresAt))
        // 오래된 챌린지 정리
        repo.deleteExpiredBefore(expiresAt.minusSeconds(86_400))
        return id
    }

    override fun find(id: UUID): LoginChallengeRecord? =
        repo.findByIdOrNull(id)?.let { LoginChallengeRecord(it.id, UserId(it.userId), it.rememberMe, it.attempts, it.expiresAt, it.usedAt) }

    override fun incrementAttempts(id: UUID) {
        repo.incrementAttempts(id)
    }

    override fun markUsed(id: UUID, now: Instant) {
        repo.markUsed(id, now)
    }
}

// ───────── AUTH-006 ─────────
@Component
class SocialAccountJpaAdapter(
    private val links: UserSocialJpaRepository,
    private val signups: SocialSignupJpaRepository,
) : SocialAccountPort {

    override fun findUser(provider: SocialProvider, providerUserId: String): UserId? =
        links.findByIdOrNull(UserSocialKey(provider.name, providerUserId))?.let { UserId(it.userId) }

    override fun link(provider: SocialProvider, providerUserId: String, userId: UserId, email: String?, viaSignup: Boolean, now: Instant) {
        links.save(UserSocialEntity(provider.name, providerUserId, userId.value, email?.take(255), viaSignup, now))
    }

    override fun isSignupLink(userId: UserId, provider: SocialProvider): Boolean = links.countSignupLink(userId.value, provider.name) > 0

    override fun unlink(userId: UserId, provider: SocialProvider) {
        links.unlink(userId.value, provider.name)
    }

    override fun links(userId: UserId): List<SocialLinkView> =
        links.findByUserIdOrderByProvider(userId.value).map { SocialLinkView(SocialProvider.valueOf(it.provider), it.email, it.linkedAt) }

    override fun createSignup(provider: SocialProvider, profile: SocialProfile, expiresAt: Instant): UUID {
        val id = UUID.randomUUID()
        signups.save(
            SocialSignupEntity(
                id, provider.name, profile.providerUserId, profile.email?.take(255), profile.name?.take(50), profile.phone?.take(20), expiresAt,
            ),
        )
        signups.deleteExpiredBefore(expiresAt.minusSeconds(86_400))
        return id
    }

    override fun findSignup(id: UUID): SocialSignupRecord? =
        signups.findByIdOrNull(id)?.let {
            SocialSignupRecord(it.id, SocialProvider.valueOf(it.provider), it.providerUserId, it.email, it.name, it.phone, it.expiresAt, it.usedAt)
        }

    override fun useSignup(id: UUID, now: Instant): Boolean = signups.use(id, now) > 0
}

// ───────── AUTH-010 ─────────
@Component
class SessionPolicyJpaAdapter(private val institutions: InstitutionJpaRepository) : SessionPolicyPort {

    override fun idleMinutes(institutionId: InstitutionId): Int = institutions.findByIdOrNull(institutionId.value)?.sessionIdleMinutes ?: 30

    override fun setIdleMinutes(institutionId: InstitutionId, minutes: Int) {
        institutions.setSessionIdleMinutes(institutionId.value, minutes)
    }
}

// ───────── ONB-003 샘플 체험 기관 ─────────
@Component
class SampleDataJpaAdapter(
    private val institutions: InstitutionJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
    private val crypto: FieldCrypto,
) : SampleDataPort {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private data class ClassSpec(val name: String, val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime, val capacity: Int, val grade: String)

    private companion object {
        val CLASSES = listOf(
            ClassSpec("초등 영어 A", setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(15, 0), LocalTime.of(16, 30), 10, "초3"),
            ClassSpec("초등 수학 B", setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalTime.of(16, 0), LocalTime.of(17, 30), 10, "초5"),
            ClassSpec("중등 영어 내신", setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(18, 0), LocalTime.of(19, 30), 12, "중1"),
        )
        val NAMES = listOf(
            "김하준", "이서윤", "박도윤", "최지우", "정시우", "강하은", "조이준", "윤서아", "장예준",
            "임지호", "한수아", "오주원", "서지안", "신유나", "권도현", "황채원", "안시윤", "송민서",
        )
        val DESTINATIONS = listOf("집" to "HOME", "태권도장" to "ACADEMY", "피아노학원" to "ACADEMY", "셔틀 1호차" to "SHUTTLE")
    }

    override fun markSample(institutionId: InstitutionId) {
        institutions.markSample(institutionId.value)
    }

    override fun sampleOf(userId: UserId): Pair<UUID, String>? =
        institutions.findSamplesOf(userId.value, PageRequest.of(0, 1)).firstOrNull()?.let { (it[0] as UUID) to (it[1] as String) }

    override fun isSample(institutionId: UUID): Boolean = institutions.countSample(institutionId) > 0

    override fun seed(institutionId: InstitutionId, owner: UserId, today: LocalDate, now: Instant) {
        val inst = institutionId.value
        val rnd = Random(inst.mostSignificantBits)

        DESTINATIONS.forEachIndexed { i, (name, type) ->
            q.persist(DestinationEntity(Uuid7.next(), inst, name, type, i))
        }

        val classIds = CLASSES.map { c ->
            val id = Uuid7.next()
            q.persist(
                ClassroomEntity(
                    id, inst, c.name, c.capacity, c.days.sumOf { 1 shl (it.value - 1) }, c.start, c.end, mutableSetOf(owner.value),
                ),
            )
            id
        }

        // 반마다 6명
        val enrolled = mutableMapOf<UUID, MutableList<UUID>>()
        NAMES.forEachIndexed { idx, name ->
            val classIdx = idx / 6
            val spec = CLASSES[classIdx]
            val sid = Uuid7.next()
            val birthYear = when (spec.grade) { "초3" -> today.year - 9; "초5" -> today.year - 11; else -> today.year - 13 }
            val birth = LocalDate.of(birthYear, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            q.persist(StudentEntity(sid, inst, name, crypto.encrypt(birth.toString()), spec.grade, "ACTIVE", "체험용 샘플 원생입니다"))
            q.persist(EnrollmentEntity(null, sid, classIds[classIdx], today.minusDays(60), null))
            enrolled.getOrPut(classIds[classIdx]) { mutableListOf() }.add(sid)
        }

        // 지난 14일 출결 + 오늘 예정 (원생·반·날짜마다 한 번씩이라 겹치지 않는다)
        for (back in 14 downTo 0) {
            val date = today.minusDays(back.toLong())
            CLASSES.forEachIndexed { ci, spec ->
                if (date.dayOfWeek !in spec.days) return@forEachIndexed
                val classId = classIds[ci]
                enrolled[classId].orEmpty().forEach { sid ->
                    val roll = rnd.nextInt(100)
                    val start = date.atTime(spec.start).atZone(zone).toInstant()
                    val end = date.atTime(spec.end).atZone(zone).toInstant()
                    val (status, late, inAt, outAt) = when {
                        back == 0 -> Quad("SCHEDULED", false, null, null)
                        roll < 6 -> Quad("ABSENT", false, null, null)
                        roll < 14 -> Quad("OUT", true, start.plusSeconds(60L * (11 + rnd.nextInt(15))), end)
                        else -> Quad("OUT", false, start.minusSeconds(60L * rnd.nextInt(10)), end.plusSeconds(60L * rnd.nextInt(5)))
                    }
                    q.persist(AttendanceDayEntity(Uuid7.next(), inst, sid, classId, date, status, late, false, inAt, outAt, null, now))
                }
            }
        }
    }

    private data class Quad(val status: String, val late: Boolean, val inAt: Instant?, val outAt: Instant?)

    override fun archive(institutionId: UUID, now: Instant) {
        memberships.deleteByInstitution(institutionId)
        institutions.setServiceStatus(institutionId, "SUSPENDED", "체험 종료", now, sampleOnly = true)
    }
}
