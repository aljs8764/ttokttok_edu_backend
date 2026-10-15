package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

// S11–12 설정·파일·약관·세션 (V6), 로그인 실패 잠금 (V7)

/** 공통 파일 (S3 직접 업로드) */
@Entity @Table(name = "stored_file")
class StoredFileEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var uploaderId: UUID,
    var purpose: String,
    var originalName: String,
    var mime: String,
    var size: Long,
    var storageKey: String,
    var status: String,
    var createdAt: Instant,
)

/** refresh 토큰 회전·폐기. 재사용 감지 시 같은 family 전체 폐기 */
@Entity @Table(name = "refresh_token")
class RefreshTokenEntity(
    @Id var jti: UUID,
    var familyId: UUID,
    var userId: UUID,
    var rememberMe: Boolean,
    var expiresAt: Instant,
    var revokedAt: Instant? = null,
    var replacedBy: UUID? = null,
    var createdAt: Instant = Instant.now(),
)

/** STF-002 교직원 이메일 초대 */
@Entity @Table(name = "staff_invitation")
class StaffInvitationEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var email: String,
    var name: String,
    var role: String,
    var token: String,
    var invitedBy: UUID,
    var expiresAt: Instant,
    var acceptedAt: Instant?,
    var revokedAt: Instant?,
    var createdAt: Instant,
)

/** SET-005 약관 버전 */
@Entity @Table(name = "terms")
class TermsEntity(
    @Id var id: UUID,
    var type: String,
    var version: Int,
    var title: String,
    var body: String,
    var required: Boolean,
    var effectiveAt: Instant,
)

@Entity @Table(name = "terms_agreement") @IdClass(TermsAgreementKey::class)
class TermsAgreementEntity(
    @Id var userId: UUID,
    @Id var termsId: UUID,
    var agreedAt: Instant,
    var ip: String?,
)

data class TermsAgreementKey(var userId: UUID? = null, var termsId: UUID? = null) : Serializable

/** 로그인 실패 잠금 */
@Entity @Table(name = "login_attempt")
class LoginAttemptEntity(
    @Id var userId: UUID,
    var failedCount: Int,
    var lockedUntil: Instant?,
    var updatedAt: Instant,
)
