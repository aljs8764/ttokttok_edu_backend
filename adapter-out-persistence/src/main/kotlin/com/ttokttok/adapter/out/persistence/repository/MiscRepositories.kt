package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.AuditLogEntity
import com.ttokttok.adapter.out.persistence.entity.ChildEntity
import com.ttokttok.adapter.out.persistence.entity.ChildGuardianEntity
import com.ttokttok.adapter.out.persistence.entity.ChildGuardianKey
import com.ttokttok.adapter.out.persistence.entity.ChildLocationConsentEntity
import com.ttokttok.adapter.out.persistence.entity.DailyAttendanceStatEntity
import com.ttokttok.adapter.out.persistence.entity.DailyAttendanceStatKey
import com.ttokttok.adapter.out.persistence.entity.NoticeTemplateEntity
import com.ttokttok.adapter.out.persistence.entity.NotificationSettingEntity
import com.ttokttok.adapter.out.persistence.entity.NotificationSettingKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface ChildJpaRepository : JpaRepository<ChildEntity, UUID>

interface ChildGuardianJpaRepository : JpaRepository<ChildGuardianEntity, ChildGuardianKey> {
    fun findByChildId(childId: UUID): List<ChildGuardianEntity>
    fun findByUserId(userId: UUID): List<ChildGuardianEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChildGuardianEntity g where g.childId = :c")
    fun deleteByChild(@Param("c") childId: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into child_guardian (child_id, user_id) values (:c, :u) on conflict do nothing", nativeQuery = true)
    fun insertIfAbsent(@Param("c") childId: UUID, @Param("u") userId: UUID): Int
}

interface NotificationSettingJpaRepository : JpaRepository<NotificationSettingEntity, NotificationSettingKey> {
    fun findByInstitutionId(institutionId: UUID): List<NotificationSettingEntity>
}

interface ChildLocationConsentJpaRepository : JpaRepository<ChildLocationConsentEntity, UUID>

interface NoticeTemplateJpaRepository : JpaRepository<NoticeTemplateEntity, UUID> {
    fun countByInstitutionId(institutionId: UUID): Long

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NoticeTemplateEntity t set t.useCount = t.useCount + 1 where t.id = :id")
    fun incrementUse(@Param("id") id: UUID): Int
}

interface AuditLogJpaRepository : JpaRepository<AuditLogEntity, Long>

interface DailyAttendanceStatJpaRepository : JpaRepository<DailyAttendanceStatEntity, DailyAttendanceStatKey>
