package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.StoredFile
import com.ttokttok.domain.staff.StaffInvitation
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.Terms
import com.ttokttok.domain.terms.TermsAgreement
import com.ttokttok.domain.terms.TermsId
import java.time.Instant
import java.util.UUID

interface StoredFilePort {
    fun save(file: StoredFile): StoredFile
    fun find(id: FileId): StoredFile?
    fun findAllByIds(ids: Collection<FileId>): List<StoredFile>
    /** 정리 배치: 오래된 미확정(PENDING) 업로드 */
    fun findPendingBefore(before: Instant, limit: Int): List<StoredFile>
    fun delete(id: FileId)
}

/** S3 (구현: adapter-out-storage). 서버는 바이트를 다루지 않고 서명 URL만 발급한다 */
interface ObjectStoragePort {
    /** 클라이언트가 PUT 으로 직접 올린다. Content-Type·Content-Length 가 서명에 묶인다 */
    fun presignUpload(key: String, mime: String, size: Long): PresignedUrl
    /** 5분 만료 다운로드 URL (스펙 8장) */
    fun presignDownload(key: String, filename: String, mime: String): PresignedUrl
    /** 업로드 확인: 객체가 있으면 크기, 없으면 null */
    fun sizeOf(key: String): Long?
    /** 없는 키여도 오류 없이 넘어간다 */
    fun delete(key: String)
}

data class PresignedUrl(val url: String, val method: String, val headers: Map<String, String>, val expiresAt: Instant)

/** refresh 토큰 회전·폐기 (스펙 3장 — Redis 대신 DB, 트래픽 늘면 Redis 어댑터로 교체) */
interface RefreshTokenPort {
    fun save(record: RefreshTokenRecord)
    fun find(jti: UUID): RefreshTokenRecord?
    fun revoke(jti: UUID, at: Instant, replacedBy: UUID?)
    fun revokeFamily(familyId: UUID, at: Instant)
    fun revokeAllForUser(userId: UserId, at: Instant)
}

data class RefreshTokenRecord(
    val jti: UUID,
    val familyId: UUID,
    val userId: UserId,
    val rememberMe: Boolean,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val replacedBy: UUID? = null,
)

interface StaffInvitationPort {
    fun save(invitation: StaffInvitation): StaffInvitation
    fun find(id: StaffInvitationId, institutionId: InstitutionId): StaffInvitation?
    fun findByToken(token: String): StaffInvitation?
    fun findByInstitution(institutionId: InstitutionId): List<StaffInvitation>
}

interface TermsPort {
    fun findAll(): List<Terms>
    fun findAgreedIds(userId: UserId): Set<TermsId>
    fun saveAgreements(agreements: List<TermsAgreement>)
}

interface LoginAttemptPort {
    fun find(userId: UserId): com.ttokttok.domain.user.LoginAttempt?
    fun save(attempt: com.ttokttok.domain.user.LoginAttempt)
}
