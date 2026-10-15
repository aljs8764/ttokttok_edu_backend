package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** AUTH-007 TOTP (V20). secret 은 FieldCrypto 암호문, 복구 코드는 HMAC 해시(쉼표 구분) */
@Entity @Table(name = "user_otp")
class UserOtpEntity(
    @Id var userId: UUID,
    var secretEnc: String,
    var enabled: Boolean = false,
    var lastStep: Long = 0,
    var recoveryHashes: String = "",
    var enabledAt: Instant? = null,
    var updatedAt: Instant,
)

/** 비밀번호(또는 소셜) 확인 뒤 OTP 를 기다리는 로그인 */
@Entity @Table(name = "login_challenge")
class LoginChallengeEntity(
    @Id var id: UUID,
    var userId: UUID,
    var rememberMe: Boolean,
    var attempts: Int = 0,
    var expiresAt: Instant,
    var usedAt: Instant? = null,
)

/** AUTH-006 소셜 계정 연결 */
@Entity @Table(name = "user_social") @IdClass(UserSocialKey::class)
class UserSocialEntity(
    @Id var provider: String,
    @Id var providerUserId: String,
    var userId: UUID,
    var email: String?,
    var viaSignup: Boolean,
    var linkedAt: Instant,
)

data class UserSocialKey(var provider: String? = null, var providerUserId: String? = null) : Serializable

/** 연결된 계정이 없는 소셜 로그인 → 학부모 가입 대기 */
@Entity @Table(name = "social_signup")
class SocialSignupEntity(
    @Id var id: UUID,
    var provider: String,
    var providerUserId: String,
    var email: String?,
    var name: String?,
    var phone: String?,
    var expiresAt: Instant,
    var usedAt: Instant? = null,
)
