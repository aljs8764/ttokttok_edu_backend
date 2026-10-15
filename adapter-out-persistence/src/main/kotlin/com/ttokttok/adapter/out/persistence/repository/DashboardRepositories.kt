package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.StaffMemoAckEntity
import com.ttokttok.adapter.out.persistence.entity.StaffMemoEntity
import com.ttokttok.adapter.out.persistence.entity.StaffMemoUserKey
import com.ttokttok.adapter.out.persistence.entity.UserDashboardLayoutEntity
import com.ttokttok.adapter.out.persistence.entity.UserDashboardLayoutKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface StaffMemoJpaRepository : JpaRepository<StaffMemoEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StaffMemoEntity m set m.canceledAt = :now where m.id = :id and m.canceledAt is null")
    fun cancel(@Param("id") id: UUID, @Param("now") now: Instant): Int
}

interface StaffMemoAckJpaRepository : JpaRepository<StaffMemoAckEntity, StaffMemoUserKey>

interface UserDashboardLayoutJpaRepository : JpaRepository<UserDashboardLayoutEntity, UserDashboardLayoutKey>
