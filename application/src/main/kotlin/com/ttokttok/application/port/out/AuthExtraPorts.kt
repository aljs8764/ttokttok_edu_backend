package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.SocialLinkView
import com.ttokttok.application.port.`in`.SocialProvider
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ───────── AUTH-007 ─────────
data class OtpRecord(val secret: String, val enabled: Boolean, val lastStep: Long, val recoveryHashes: List<String>, val enabledAt: Instant?)

interface OtpPort {
    fun find(userId: UserId): OtpRecord?
    /** 새 비밀키로 교체하고 끈 상태로 둔다 */
    fun saveSecret(userId: UserId, secret: String, now: Instant)
    fun enable(userId: UserId, recoveryHashes: List<String>, step: Long, now: Instant)
    fun markStep(userId: UserId, step: Long)
    fun setRecovery(userId: UserId, recoveryHashes: List<String>)
    fun delete(userId: UserId)
    /** 복구 코드 해시 (HMAC) */
    fun hashRecovery(code: String): String
}

data class LoginChallengeRecord(val id: UUID, val userId: UserId, val rememberMe: Boolean, val attempts: Int, val expiresAt: Instant, val usedAt: Instant?)

interface LoginChallengePort {
    fun create(userId: UserId, rememberMe: Boolean, expiresAt: Instant): UUID
    fun find(id: UUID): LoginChallengeRecord?
    fun incrementAttempts(id: UUID)
    fun markUsed(id: UUID, now: Instant)
}

// ───────── AUTH-006 ─────────
data class SocialProfile(val providerUserId: String, val email: String?, val name: String?, val phone: String?)

/** 카카오·네이버 사용자 정보 API (외부) */
interface SocialProfilePort {
    fun enabled(provider: SocialProvider): Boolean
    /** 토큰이 유효하지 않으면 UnauthenticatedException */
    fun fetch(provider: SocialProvider, accessToken: String): SocialProfile
}

data class SocialSignupRecord(val id: UUID, val provider: SocialProvider, val providerUserId: String, val email: String?, val name: String?, val phone: String?, val expiresAt: Instant, val usedAt: Instant?)

interface SocialAccountPort {
    fun findUser(provider: SocialProvider, providerUserId: String): UserId?
    fun link(provider: SocialProvider, providerUserId: String, userId: UserId, email: String?, viaSignup: Boolean, now: Instant)
    /** 소셜 가입으로 만든 연결인가 */
    fun isSignupLink(userId: UserId, provider: SocialProvider): Boolean
    fun unlink(userId: UserId, provider: SocialProvider)
    fun links(userId: UserId): List<SocialLinkView>
    fun createSignup(provider: SocialProvider, profile: SocialProfile, expiresAt: Instant): UUID
    fun findSignup(id: UUID): SocialSignupRecord?
    fun useSignup(id: UUID, now: Instant): Boolean
}

// ───────── AUTH-010 ─────────
interface SessionPolicyPort {
    fun idleMinutes(institutionId: InstitutionId): Int
    fun setIdleMinutes(institutionId: InstitutionId, minutes: Int)
}

// ───────── ONB-003 ─────────
interface SampleDataPort {
    fun markSample(institutionId: InstitutionId)
    /** 사용자가 소속된 체험 기관 (정지되지 않은 것) */
    fun sampleOf(userId: UserId): Pair<UUID, String>?
    fun isSample(institutionId: UUID): Boolean
    /** 예시 반·목적지·원생·지난 2주 출결·오늘 예정 출결 */
    fun seed(institutionId: InstitutionId, owner: UserId, today: LocalDate, now: Instant)
    /** 소속을 모두 지우고 기관을 정지한다 */
    fun archive(institutionId: UUID, now: Instant)
}
