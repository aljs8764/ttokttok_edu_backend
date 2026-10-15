package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.LoginAttemptEntity
import com.ttokttok.adapter.out.persistence.entity.RefreshTokenEntity
import com.ttokttok.adapter.out.persistence.entity.StaffInvitationEntity
import com.ttokttok.adapter.out.persistence.entity.StoredFileEntity
import com.ttokttok.adapter.out.persistence.repository.LoginAttemptJpaRepository
import com.ttokttok.adapter.out.persistence.repository.RefreshTokenJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StaffInvitationJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StoredFileJpaRepository
import com.ttokttok.adapter.out.persistence.repository.TermsAgreementJpaRepository
import com.ttokttok.adapter.out.persistence.repository.TermsJpaRepository
import com.ttokttok.application.port.out.LoginAttemptPort
import com.ttokttok.application.port.out.RefreshTokenPort
import com.ttokttok.application.port.out.RefreshTokenRecord
import com.ttokttok.application.port.out.StaffInvitationPort
import com.ttokttok.application.port.out.StoredFilePort
import com.ttokttok.application.port.out.TermsPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.file.FileStatus
import com.ttokttok.domain.file.StoredFile
import com.ttokttok.domain.staff.StaffInvitation
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.Terms
import com.ttokttok.domain.terms.TermsAgreement
import com.ttokttok.domain.terms.TermsId
import com.ttokttok.domain.terms.TermsType
import com.ttokttok.domain.user.LoginAttempt
import com.ttokttok.domain.user.Role
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class StoredFileJpaAdapter(private val repo: StoredFileJpaRepository) : StoredFilePort {

    private fun StoredFileEntity.toDomain() = StoredFile(
        id = FileId(id), institutionId = InstitutionId(institutionId),
        uploaderId = UserId(uploaderId), purpose = FilePurpose.valueOf(purpose),
        originalName = originalName, mime = mime, size = size,
        storageKey = storageKey, status = FileStatus.valueOf(status),
        createdAt = createdAt,
    )

    /** 처음엔 넣고, 이후엔 상태만 바뀐다 (나머지 값은 같음) */
    override fun save(file: StoredFile): StoredFile {
        repo.save(
            StoredFileEntity(
                file.id.value, file.institutionId.value, file.uploaderId.value, file.purpose.name, file.originalName,
                file.mime, file.size, file.storageKey, file.status.name, file.createdAt,
            ),
        )
        return file
    }

    override fun find(id: FileId): StoredFile? = repo.findByIdOrNull(id.value)?.toDomain()

    override fun findAllByIds(ids: Collection<FileId>): List<StoredFile> =
        ids.map { it.value }.distinct().chunked(1000).flatMap { repo.findAllById(it) }.map { it.toDomain() }

    override fun findPendingBefore(before: Instant, limit: Int): List<StoredFile> = repo.lockPendingBefore(before, limit).map { it.toDomain() }

    override fun delete(id: FileId) {
        repo.deletePending(id.value)
    }
}

@Component
class LoginAttemptJpaAdapter(private val repo: LoginAttemptJpaRepository) : LoginAttemptPort {
    override fun find(userId: UserId): LoginAttempt? =
        repo.findByIdOrNull(userId.value)?.let { LoginAttempt(UserId(it.userId), it.failedCount, it.lockedUntil) }

    override fun save(attempt: LoginAttempt) {
        repo.save(LoginAttemptEntity(attempt.userId.value, attempt.failedCount, attempt.lockedUntil, Instant.now()))
    }
}

@Component
class RefreshTokenJpaAdapter(private val repo: RefreshTokenJpaRepository) : RefreshTokenPort {

    private fun RefreshTokenEntity.toRecord() = RefreshTokenRecord(
        jti = jti, familyId = familyId, userId = UserId(userId),
        rememberMe = rememberMe, expiresAt = expiresAt,
        revokedAt = revokedAt, replacedBy = replacedBy,
    )

    override fun save(record: RefreshTokenRecord) {
        repo.save(RefreshTokenEntity(record.jti, record.familyId, record.userId.value, record.rememberMe, record.expiresAt))
    }

    override fun find(jti: UUID): RefreshTokenRecord? = repo.findByIdOrNull(jti)?.toRecord()

    override fun revoke(jti: UUID, at: Instant, replacedBy: UUID?) {
        val t = repo.findByIdOrNull(jti) ?: return
        t.revokedAt = t.revokedAt ?: at
        t.replacedBy = replacedBy
        repo.save(t)
    }

    override fun revokeFamily(familyId: UUID, at: Instant) {
        repo.revokeFamily(familyId, at)
    }

    override fun revokeAllForUser(userId: UserId, at: Instant) {
        repo.revokeAllForUser(userId.value, at)
    }
}

@Component
class StaffInvitationJpaAdapter(private val repo: StaffInvitationJpaRepository) : StaffInvitationPort {

    private fun StaffInvitationEntity.toDomain() = StaffInvitation(
        id = StaffInvitationId(id), institutionId = InstitutionId(institutionId),
        email = email, name = name, role = Role.valueOf(role),
        token = token, invitedBy = UserId(invitedBy), expiresAt = expiresAt,
        acceptedAt = acceptedAt, revokedAt = revokedAt, createdAt = createdAt,
    )

    /** 처음엔 넣고, 이후엔 수락·취소 시각만 바뀐다 */
    override fun save(invitation: StaffInvitation): StaffInvitation {
        repo.save(
            StaffInvitationEntity(
                invitation.id.value, invitation.institutionId.value, invitation.email, invitation.name, invitation.role.name,
                invitation.token, invitation.invitedBy.value, invitation.expiresAt,
                invitation.acceptedAt, invitation.revokedAt, invitation.createdAt,
            ),
        )
        return invitation
    }

    override fun find(id: StaffInvitationId, institutionId: InstitutionId): StaffInvitation? =
        repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()

    override fun findByToken(token: String): StaffInvitation? = repo.findByToken(token)?.toDomain()

    override fun findByInstitution(institutionId: InstitutionId): List<StaffInvitation> =
        repo.findByInstitutionIdOrderByCreatedAtDesc(institutionId.value).map { it.toDomain() }
}

@Component
class TermsJpaAdapter(
    private val terms: TermsJpaRepository,
    private val agreements: TermsAgreementJpaRepository,
    private val q: JpaQueries,
) : TermsPort {
    override fun findAll(): List<Terms> = terms.findAll().map {
        Terms(
            id = TermsId(it.id), type = TermsType.valueOf(it.type), version = it.version,
            title = it.title, body = it.body, required = it.required,
            effectiveAt = it.effectiveAt,
        )
    }

    override fun findAgreedIds(userId: UserId): Set<TermsId> = agreements.findByUserId(userId.value).map { TermsId(it.termsId) }.toSet()

    override fun saveAgreements(agreements: List<TermsAgreement>) {
        q.insertValues(
            "insert into terms_agreement (user_id, terms_id, agreed_at, ip)",
            agreements.map { listOf(it.userId.value, it.termsId.value, it.agreedAt, typed(it.ip)) },
            "on conflict do nothing",
        )
    }
}
