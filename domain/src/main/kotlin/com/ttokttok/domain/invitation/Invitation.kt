package com.ttokttok.domain.invitation

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline value class InvitationId(val value: UUID) { companion object { fun new() = InvitationId(Uuid7.next()) } }
@JvmInline value class JoinRequestId(val value: UUID) { companion object { fun new() = JoinRequestId(Uuid7.next()) } }

/**
 * STU-005 학부모 초대장. 학부모 번호로 '정보 입력 링크'를 보낸다.
 * 형제가 여럿일 수 있어 만료 전까지 여러 번 제출 가능.
 */
data class Invitation(
    val id: InvitationId,
    val institutionId: InstitutionId,
    val token: String,
    val phone: PhoneNumber,
    val invitedBy: UserId,
    val expiresAt: Instant,
) {
    fun isExpired(now: Instant) = !now.isBefore(expiresAt)

    fun ensureUsable(now: Instant) {
        if (isExpired(now)) throw ConflictException("INVITATION_EXPIRED", "초대 링크가 만료되었습니다. 기관에 재발송을 요청하세요")
    }

    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        fun issue(institutionId: InstitutionId, phone: PhoneNumber, invitedBy: UserId, now: Instant) = Invitation(
            InvitationId.new(), institutionId, newToken(), phone, invitedBy, now.plus(VALIDITY),
        )

        /** URL에 쓰는 추측 불가 토큰 (22자 ≈ 128bit) */
        fun newToken(): String = (1..22).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}

enum class JoinRequestStatus { PENDING, APPROVED, REJECTED }

/** STU-004 가입 대기자. 승인 시 원생·보호자·반 배정이 한 번에 생성된다. */
data class JoinRequest(
    val id: JoinRequestId,
    val institutionId: InstitutionId,
    val invitationId: InvitationId,
    val childName: String,
    val birthDate: LocalDate,
    val guardianName: String,
    val guardianPhone: PhoneNumber,
    val relation: String?,
    val submittedAt: Instant,
    val status: JoinRequestStatus = JoinRequestStatus.PENDING,
    val classroomId: ClassroomId? = null,
    val decidedBy: UserId? = null,
    val decidedAt: Instant? = null,
    val rejectReason: String? = null,
) {
    init {
        if (childName.isBlank() || guardianName.isBlank()) throw InvalidInputException("INVALID_NAME", "자녀·보호자 이름은 필수입니다")
    }

    fun approve(classroom: ClassroomId, by: UserId, at: Instant): JoinRequest {
        ensurePending()
        return copy(status = JoinRequestStatus.APPROVED, classroomId = classroom, decidedBy = by, decidedAt = at)
    }

    fun reject(reason: String?, by: UserId, at: Instant): JoinRequest {
        ensurePending()
        return copy(status = JoinRequestStatus.REJECTED, rejectReason = reason?.take(200), decidedBy = by, decidedAt = at)
    }

    private fun ensurePending() {
        if (status != JoinRequestStatus.PENDING) throw ConflictException("ALREADY_DECIDED", "이미 처리된 가입 요청입니다")
    }
}
