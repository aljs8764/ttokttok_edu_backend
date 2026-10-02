package com.ttokttok.domain.staff

import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.user.Role
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@JvmInline value class StaffInvitationId(val value: UUID) { companion object { fun new() = StaffInvitationId(Uuid7.next()) } }

/** 교직원 이메일 초대 (STF-002). 링크 7일 유효, 수락 시 계정 생성(또는 기존 계정 연결) + 소속 부여 */
data class StaffInvitation(
    val id: StaffInvitationId,
    val institutionId: InstitutionId,
    val email: String,
    val name: String,
    val role: Role,
    val token: String,
    val invitedBy: UserId,
    val expiresAt: Instant,
    val acceptedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
) {
    fun isUsable(now: Instant) = acceptedAt == null && revokedAt == null && now.isBefore(expiresAt)

    fun accept(now: Instant): StaffInvitation {
        if (acceptedAt != null) throw ConflictException("ALREADY_ACCEPTED", "이미 수락한 초대입니다")
        if (revokedAt != null || !now.isBefore(expiresAt)) throw ConflictException("INVITATION_EXPIRED", "만료되었거나 취소된 초대입니다")
        return copy(acceptedAt = now)
    }

    fun revoke(now: Instant): StaffInvitation {
        if (acceptedAt != null) throw ConflictException("ALREADY_ACCEPTED", "이미 수락한 초대는 취소할 수 없습니다")
        return copy(revokedAt = now)
    }

    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val random = SecureRandom()

        fun issue(institutionId: InstitutionId, email: String, name: String, role: Role, invitedBy: UserId, now: Instant): StaffInvitation {
            val e = email.trim().lowercase()
            if (!EMAIL.matches(e)) throw InvalidInputException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다")
            if (name.isBlank() || name.length > 50) throw InvalidInputException("INVALID_NAME", "이름은 1~50자입니다")
            if (role !in setOf(Role.ADMIN, Role.TEACHER)) throw InvalidInputException("INVALID_ROLE", "실장(ADMIN) 또는 교사(TEACHER)만 초대할 수 있습니다")
            val bytes = ByteArray(24).also(random::nextBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return StaffInvitation(StaffInvitationId.new(), institutionId, e, name.trim(), role, token, invitedBy, now.plus(VALIDITY), createdAt = now)
        }
    }
}
