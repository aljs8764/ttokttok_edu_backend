package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

// Phase 2 ⑦ 인증·온보딩

// ───────── AUTH-007 2단계 인증 (TOTP) ─────────
data class OtpStatus(val enabled: Boolean, val enabledAt: Instant?, val recoveryCodesLeft: Int)
data class OtpSetup(val secret: String, val otpauthUri: String)

interface OtpUseCase {
    fun status(actor: UserId): OtpStatus
    /** 새 비밀키를 만든다 (아직 꺼진 상태). 인증 앱에 등록한 뒤 enable 로 켠다 */
    fun setup(actor: UserId): OtpSetup
    /** 인증 앱 코드로 확인하고 켠다. 복구 코드 8개를 한 번만 돌려준다 */
    fun enable(actor: UserId, code: String): List<String>
    fun disable(actor: UserId, password: String, code: String)
    fun regenerateRecoveryCodes(actor: UserId, code: String): List<String>
}

/** 로그인 2단계 — 비밀번호(또는 소셜)를 통과한 뒤 OTP 를 받는다 */
interface LoginChallengeUseCase {
    data class Challenge(val id: UUID, val expiresAt: Instant)
    data class Passed(val user: AuthenticatedUser, val rememberMe: Boolean)

    /** OTP 를 켠 계정이면 챌린지를 만들고, 아니면 null */
    fun challengeIfRequired(userId: UserId, rememberMe: Boolean): Challenge?
    /** 6자리 OTP 또는 복구 코드 */
    fun verify(challengeId: UUID, code: String): Passed
}

// ───────── AUTH-006 소셜 로그인 ─────────
enum class SocialProvider { KAKAO, NAVER }

sealed interface SocialLoginResult {
    data class LoggedIn(val user: AuthenticatedUser) : SocialLoginResult
    /** 연결된 계정이 없음 — 학부모 앱이면 가입으로, 교사 앱이면 기존 계정 로그인 후 연결 안내 */
    data class SignupRequired(val signupToken: UUID, val name: String?, val email: String?, val phone: String?) : SocialLoginResult
}

data class SocialLinkView(val provider: SocialProvider, val email: String?, val linkedAt: Instant)

interface SocialLoginUseCase {
    /** 앱이 카카오·네이버 SDK 로 받은 access token 을 서버가 직접 확인한다 */
    fun login(provider: SocialProvider, accessToken: String): SocialLoginResult
    /** 학부모 가입 (휴대폰 번호로 등록된 자녀 자동 연결) */
    fun signupParent(signupToken: UUID, name: String, phone: String): AuthenticatedUser
    fun links(actor: UserId): List<SocialLinkView>
    fun link(actor: UserId, provider: SocialProvider, accessToken: String): List<SocialLinkView>
    fun unlink(actor: UserId, provider: SocialProvider): List<SocialLinkView>
    fun enabledProviders(): List<SocialProvider>
}

// ───────── AUTH-010 세션 정책 ─────────
interface SessionPolicyUseCase {
    /** 직원 누구나 (웹이 자동 로그아웃 타이머를 맞춘다) */
    fun idleMinutes(actor: UserId, institutionId: InstitutionId): Int
    /** 원장·실장. 0 = 끔, 5~480 */
    fun setIdleMinutes(actor: UserId, institutionId: InstitutionId, minutes: Int): Int
}

// ───────── ONB-003 샘플 체험 기관 ─────────
data class SampleInstitutionView(val institutionId: UUID, val name: String)

interface SampleInstitutionUseCase {
    /** 내가 원장인 체험용 기관을 만들고 예시 반·원생·출결 기록을 채운다 (계정당 1곳) */
    fun create(actor: UserId): SampleInstitutionView
    fun mine(actor: UserId): SampleInstitutionView?
    /** 체험 종료 — 내 소속을 지우고 기관을 정지 상태로 둔다 */
    fun remove(actor: UserId, institutionId: UUID)
}
