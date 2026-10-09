package com.ttokttok.adapter.out.persistence

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

private fun ResultSet.aid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.ats(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun atime(i: Instant?) = i?.let { Timestamp.from(it) }
private fun ap(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

// ───────── AUTH-007 ─────────
@Component
class OtpJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : OtpPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun find(userId: UserId): OtpRecord? =
        named.query(
            "select secret_enc, enabled, last_step, recovery_hashes, enabled_at from user_otp where user_id = :u",
            ap("u" to userId.value),
            RowMapper { rs, _ ->
                OtpRecord(
                    crypto.decrypt(rs.getString("secret_enc")), rs.getBoolean("enabled"), rs.getLong("last_step"),
                    rs.getString("recovery_hashes").split(',').filter { it.isNotBlank() }, rs.ats("enabled_at"),
                )
            },
        ).firstOrNull()

    override fun saveSecret(userId: UserId, secret: String, now: Instant) {
        named.update(
            """insert into user_otp (user_id, secret_enc, enabled, last_step, recovery_hashes, enabled_at, updated_at)
               values (:u, :s, false, 0, '', null, :now)
               on conflict (user_id) do update set secret_enc = :s, enabled = false, last_step = 0, recovery_hashes = '', enabled_at = null, updated_at = :now""",
            ap("u" to userId.value, "s" to crypto.encrypt(secret), "now" to atime(now)),
        )
    }

    override fun enable(userId: UserId, recoveryHashes: List<String>, step: Long, now: Instant) {
        named.update(
            "update user_otp set enabled = true, last_step = :step, recovery_hashes = :r, enabled_at = :now, updated_at = :now where user_id = :u",
            ap("u" to userId.value, "step" to step, "r" to recoveryHashes.joinToString(","), "now" to atime(now)),
        )
    }

    override fun markStep(userId: UserId, step: Long) {
        named.update("update user_otp set last_step = greatest(last_step, :step) where user_id = :u", ap("u" to userId.value, "step" to step))
    }

    override fun setRecovery(userId: UserId, recoveryHashes: List<String>) {
        named.update("update user_otp set recovery_hashes = :r where user_id = :u", ap("u" to userId.value, "r" to recoveryHashes.joinToString(",")))
    }

    override fun delete(userId: UserId) {
        named.update("delete from user_otp where user_id = :u", ap("u" to userId.value))
    }

    override fun hashRecovery(code: String): String = crypto.hash("otp-recovery:$code")
}

@Component
class LoginChallengeJdbcAdapter(jdbc: JdbcTemplate) : LoginChallengePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun create(userId: UserId, rememberMe: Boolean, expiresAt: Instant): UUID {
        val id = UUID.randomUUID()   // 추측할 수 없어야 하므로 시간 순 UUID 를 쓰지 않는다
        named.update(
            "insert into login_challenge (id, user_id, remember_me, expires_at) values (:id, :u, :rm, :exp)",
            ap("id" to id, "u" to userId.value, "rm" to rememberMe, "exp" to atime(expiresAt)),
        )
        // 오래된 챌린지 정리
        named.update("delete from login_challenge where expires_at < :cut", ap("cut" to atime(expiresAt.minusSeconds(86_400))))
        return id
    }

    override fun find(id: UUID): LoginChallengeRecord? =
        named.query(
            "select id, user_id, remember_me, attempts, expires_at, used_at from login_challenge where id = :id",
            ap("id" to id),
            RowMapper { rs, _ ->
                LoginChallengeRecord(rs.aid("id"), UserId(rs.aid("user_id")), rs.getBoolean("remember_me"), rs.getInt("attempts"), rs.ats("expires_at")!!, rs.ats("used_at"))
            },
        ).firstOrNull()

    override fun incrementAttempts(id: UUID) {
        named.update("update login_challenge set attempts = attempts + 1 where id = :id", ap("id" to id))
    }

    override fun markUsed(id: UUID, now: Instant) {
        named.update("update login_challenge set used_at = :now where id = :id and used_at is null", ap("id" to id, "now" to atime(now)))
    }
}

// ───────── AUTH-006 ─────────
@Component
class SocialAccountJdbcAdapter(jdbc: JdbcTemplate) : SocialAccountPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun findUser(provider: SocialProvider, providerUserId: String): UserId? =
        named.query(
            "select user_id from user_social where provider = :p and provider_user_id = :pid",
            ap("p" to provider.name, "pid" to providerUserId), RowMapper { rs, _ -> UserId(rs.aid("user_id")) },
        ).firstOrNull()

    override fun link(provider: SocialProvider, providerUserId: String, userId: UserId, email: String?, viaSignup: Boolean, now: Instant) {
        named.update(
            """insert into user_social (provider, provider_user_id, user_id, email, via_signup, linked_at)
               values (:p, :pid, :u, :email, :signup, :now)""",
            ap("p" to provider.name, "pid" to providerUserId, "u" to userId.value, "email" to email?.take(255), "signup" to viaSignup, "now" to atime(now)),
        )
    }

    override fun isSignupLink(userId: UserId, provider: SocialProvider): Boolean =
        named.queryForObject(
            "select exists(select 1 from user_social where user_id = :u and provider = :p and via_signup)",
            ap("u" to userId.value, "p" to provider.name), Boolean::class.java,
        ) ?: false

    override fun unlink(userId: UserId, provider: SocialProvider) {
        named.update("delete from user_social where user_id = :u and provider = :p", ap("u" to userId.value, "p" to provider.name))
    }

    override fun links(userId: UserId): List<SocialLinkView> =
        named.query(
            "select provider, email, linked_at from user_social where user_id = :u order by provider",
            ap("u" to userId.value),
            RowMapper { rs, _ -> SocialLinkView(SocialProvider.valueOf(rs.getString("provider")), rs.getString("email"), rs.ats("linked_at")!!) },
        )

    override fun createSignup(provider: SocialProvider, profile: SocialProfile, expiresAt: Instant): UUID {
        val id = UUID.randomUUID()
        named.update(
            """insert into social_signup (id, provider, provider_user_id, email, name, phone, expires_at)
               values (:id, :p, :pid, :email, :name, :phone, :exp)""",
            ap("id" to id, "p" to provider.name, "pid" to profile.providerUserId, "email" to profile.email?.take(255),
                "name" to profile.name?.take(50), "phone" to profile.phone?.take(20), "exp" to atime(expiresAt)),
        )
        named.update("delete from social_signup where expires_at < :cut", ap("cut" to atime(expiresAt.minusSeconds(86_400))))
        return id
    }

    override fun findSignup(id: UUID): SocialSignupRecord? =
        named.query(
            "select id, provider, provider_user_id, email, name, phone, expires_at, used_at from social_signup where id = :id",
            ap("id" to id),
            RowMapper { rs, _ ->
                SocialSignupRecord(
                    rs.aid("id"), SocialProvider.valueOf(rs.getString("provider")), rs.getString("provider_user_id"), rs.getString("email"),
                    rs.getString("name"), rs.getString("phone"), rs.ats("expires_at")!!, rs.ats("used_at"),
                )
            },
        ).firstOrNull()

    override fun useSignup(id: UUID, now: Instant): Boolean =
        named.update("update social_signup set used_at = :now where id = :id and used_at is null", ap("id" to id, "now" to atime(now))) > 0
}

// ───────── AUTH-010 ─────────
@Component
class SessionPolicyJdbcAdapter(jdbc: JdbcTemplate) : SessionPolicyPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun idleMinutes(institutionId: InstitutionId): Int =
        named.query("select session_idle_minutes from institution where id = :i", ap("i" to institutionId.value), RowMapper { rs, _ -> rs.getInt(1) })
            .firstOrNull() ?: 30

    override fun setIdleMinutes(institutionId: InstitutionId, minutes: Int) {
        named.update("update institution set session_idle_minutes = :m where id = :i", ap("i" to institutionId.value, "m" to minutes))
    }
}

// ───────── ONB-003 샘플 체험 기관 ─────────
@Component
class SampleDataJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : SampleDataPort {
    private val named = NamedParameterJdbcTemplate(jdbc)
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
        named.update("update institution set is_sample = true where id = :i", ap("i" to institutionId.value))
    }

    override fun sampleOf(userId: UserId): Pair<UUID, String>? =
        named.query(
            """select i.id, i.name from institution i join membership m on m.institution_id = i.id
                where m.user_id = :u and i.is_sample and i.service_status = 'ACTIVE' order by i.created_at desc limit 1""",
            ap("u" to userId.value), RowMapper { rs, _ -> rs.aid("id") to rs.getString("name") },
        ).firstOrNull()

    override fun isSample(institutionId: UUID): Boolean =
        named.queryForObject("select exists(select 1 from institution where id = :i and is_sample)", ap("i" to institutionId), Boolean::class.java) ?: false

    override fun seed(institutionId: InstitutionId, owner: UserId, today: LocalDate, now: Instant) {
        val inst = institutionId.value
        val rnd = Random(inst.mostSignificantBits)

        DESTINATIONS.forEachIndexed { i, (name, type) ->
            named.update(
                "insert into destination (id, institution_id, name, type, sort_order) values (:id, :i, :n, :t, :o)",
                ap("id" to Uuid7.next(), "i" to inst, "n" to name, "t" to type, "o" to i),
            )
        }

        val classIds = CLASSES.map { c ->
            val id = Uuid7.next()
            named.update(
                """insert into classroom (id, institution_id, name, capacity, days_mask, start_time, end_time, created_at, updated_at)
                   values (:id, :i, :n, :cap, :mask, :st, :et, :now, :now)""",
                ap("id" to id, "i" to inst, "n" to c.name, "cap" to c.capacity, "mask" to c.days.sumOf { 1 shl (it.value - 1) },
                    "st" to Time.valueOf(c.start), "et" to Time.valueOf(c.end), "now" to atime(now)),
            )
            named.update("insert into classroom_teacher (classroom_id, user_id) values (:c, :u)", ap("c" to id, "u" to owner.value))
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
            named.update(
                """insert into student (id, institution_id, name, birth_enc, grade, status, memo, created_at, updated_at)
                   values (:id, :i, :n, :b, :g, 'ACTIVE', '체험용 샘플 원생입니다', :now, :now)""",
                ap("id" to sid, "i" to inst, "n" to name, "b" to crypto.encrypt(birth.toString()), "g" to spec.grade, "now" to atime(now)),
            )
            named.update(
                "insert into enrollment (student_id, classroom_id, from_date) values (:s, :c, :d)",
                ap("s" to sid, "c" to classIds[classIdx], "d" to java.sql.Date.valueOf(today.minusDays(60))),
            )
            enrolled.getOrPut(classIds[classIdx]) { mutableListOf() }.add(sid)
        }

        // 지난 14일 출결 + 오늘 예정
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
                    named.update(
                        """insert into attendance_day (id, institution_id, student_id, classroom_id, date, status, is_late, check_in_at, check_out_at, updated_at)
                           values (:id, :i, :s, :c, :d, :st, :late, :in, :out, :now)
                           on conflict (student_id, classroom_id, date) do nothing""",
                        ap("id" to Uuid7.next(), "i" to inst, "s" to sid, "c" to classId, "d" to java.sql.Date.valueOf(date), "st" to status,
                            "late" to late, "in" to atime(inAt), "out" to atime(outAt), "now" to atime(now)),
                    )
                }
            }
        }
    }

    private data class Quad(val status: String, val late: Boolean, val inAt: Instant?, val outAt: Instant?)

    override fun archive(institutionId: UUID, now: Instant) {
        named.update("delete from membership where institution_id = :i", ap("i" to institutionId))
        named.update(
            "update institution set service_status = 'SUSPENDED', status_reason = '체험 종료', status_changed_at = :now, updated_at = :now where id = :i and is_sample",
            ap("i" to institutionId, "now" to atime(now)),
        )
    }
}
