package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.CheckinQrEntity
import com.ttokttok.adapter.out.persistence.entity.InstitutionGeofenceEntity
import com.ttokttok.adapter.out.persistence.entity.QrScanLogEntity
import com.ttokttok.adapter.out.persistence.entity.StudentDeviceEntity
import com.ttokttok.adapter.out.persistence.entity.StudentLinkCodeEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface CheckinQrJpaRepository : JpaRepository<CheckinQrEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): CheckinQrEntity?
    fun findByTokenAndActiveTrue(token: String): CheckinQrEntity?
    fun findByInstitutionIdAndActiveTrueOrderByCreatedAt(institutionId: UUID): List<CheckinQrEntity>
}

interface InstitutionGeofenceJpaRepository : JpaRepository<InstitutionGeofenceEntity, UUID>

interface StudentDeviceJpaRepository : JpaRepository<StudentDeviceEntity, UUID> {
    fun findByTokenHashAndRevokedAtIsNull(tokenHash: String): StudentDeviceEntity?
    fun findByChildIdAndRevokedAtIsNull(childId: UUID): List<StudentDeviceEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StudentDeviceEntity d set d.childId = :to where d.childId = :from")
    fun reassignChild(@Param("from") from: UUID, @Param("to") to: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StudentDeviceEntity d set d.lastSeenAt = :at where d.id = :id")
    fun touch(@Param("id") id: UUID, @Param("at") at: Instant): Int
}

interface StudentLinkCodeJpaRepository : JpaRepository<StudentLinkCodeEntity, String> {
    /** 같은 코드를 두 기기가 동시에 쓰지 못하게 행 잠금 (select … for update) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StudentLinkCodeEntity c where c.code = :code")
    fun lockByCode(@Param("code") code: String): StudentLinkCodeEntity?

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StudentLinkCodeEntity c where c.expiresAt < :cut")
    fun deleteExpiredBefore(@Param("cut") cut: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StudentLinkCodeEntity c where c.childId = :c")
    fun deleteByChild(@Param("c") childId: UUID): Int
}

interface QrScanLogJpaRepository : JpaRepository<QrScanLogEntity, Long> {
    @Query("select l from QrScanLogEntity l where l.institutionId = :i and l.at >= :since order by l.at desc")
    fun findRecent(@Param("i") institutionId: UUID, @Param("since") since: Instant, pageable: Pageable): List<QrScanLogEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update QrScanLogEntity l set l.childId = :to where l.childId = :from")
    fun reassignChild(@Param("from") from: UUID, @Param("to") to: UUID): Int
}
