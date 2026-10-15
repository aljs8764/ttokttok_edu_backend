package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.ApiUsageHourlyEntity
import com.ttokttok.adapter.out.persistence.entity.ApiUsageHourlyKey
import com.ttokttok.adapter.out.persistence.entity.UsageAlertEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ApiUsageHourlyJpaRepository : JpaRepository<ApiUsageHourlyEntity, ApiUsageHourlyKey> {
    @Query("select u from ApiUsageHourlyEntity u where u.institutionId = :i and u.hour >= :f and u.hour < :t order by u.hour")
    fun findRange(@Param("i") institutionId: UUID, @Param("f") from: Instant, @Param("t") to: Instant): List<ApiUsageHourlyEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ApiUsageHourlyEntity u where u.hour < :b")
    fun purgeBefore(@Param("b") before: Instant): Int
}

interface UsageAlertJpaRepository : JpaRepository<UsageAlertEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UsageAlertEntity a set a.acknowledgedAt = :now, a.acknowledgedBy = :by where a.id = :id and a.acknowledgedAt is null")
    fun acknowledge(@Param("id") id: UUID, @Param("by") by: UUID, @Param("now") now: Instant): Int
}
