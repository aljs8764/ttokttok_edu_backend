package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.LoginAttemptEntity
import com.ttokttok.adapter.out.persistence.entity.RefreshTokenEntity
import com.ttokttok.adapter.out.persistence.entity.StaffInvitationEntity
import com.ttokttok.adapter.out.persistence.entity.StoredFileEntity
import com.ttokttok.adapter.out.persistence.entity.TermsAgreementEntity
import com.ttokttok.adapter.out.persistence.entity.TermsAgreementKey
import com.ttokttok.adapter.out.persistence.entity.TermsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface StoredFileJpaRepository : JpaRepository<StoredFileEntity, UUID> {
    /** 미확정 업로드 정리 대상 (여러 워커가 겹치지 않게 skip locked) */
    @Query(
        value = "select * from stored_file where status = 'PENDING' and created_at < :before order by created_at limit :limit for update skip locked",
        nativeQuery = true,
    )
    fun lockPendingBefore(@Param("before") before: Instant, @Param("limit") limit: Int): List<StoredFileEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StoredFileEntity f where f.id = :id and f.status = 'PENDING'")
    fun deletePending(@Param("id") id: UUID): Int
}

interface LoginAttemptJpaRepository : JpaRepository<LoginAttemptEntity, UUID>

interface RefreshTokenJpaRepository : JpaRepository<RefreshTokenEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revokedAt = :at where t.familyId = :f and t.revokedAt is null")
    fun revokeFamily(@Param("f") familyId: UUID, @Param("at") at: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revokedAt = :at where t.userId = :u and t.revokedAt is null")
    fun revokeAllForUser(@Param("u") userId: UUID, @Param("at") at: Instant): Int
}

interface StaffInvitationJpaRepository : JpaRepository<StaffInvitationEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): StaffInvitationEntity?
    fun findByToken(token: String): StaffInvitationEntity?
    fun findByInstitutionIdOrderByCreatedAtDesc(institutionId: UUID): List<StaffInvitationEntity>
}

interface TermsJpaRepository : JpaRepository<TermsEntity, UUID>

interface TermsAgreementJpaRepository : JpaRepository<TermsAgreementEntity, TermsAgreementKey> {
    fun findByUserId(userId: UUID): List<TermsAgreementEntity>
}
