package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.LoginChallengeEntity
import com.ttokttok.adapter.out.persistence.entity.SocialSignupEntity
import com.ttokttok.adapter.out.persistence.entity.UserOtpEntity
import com.ttokttok.adapter.out.persistence.entity.UserSocialEntity
import com.ttokttok.adapter.out.persistence.entity.UserSocialKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface UserOtpJpaRepository : JpaRepository<UserOtpEntity, UUID>

interface LoginChallengeJpaRepository : JpaRepository<LoginChallengeEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoginChallengeEntity c where c.expiresAt < :cut")
    fun deleteExpiredBefore(@Param("cut") cut: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LoginChallengeEntity c set c.attempts = c.attempts + 1 where c.id = :id")
    fun incrementAttempts(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LoginChallengeEntity c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    fun markUsed(@Param("id") id: UUID, @Param("now") now: Instant): Int
}

interface UserSocialJpaRepository : JpaRepository<UserSocialEntity, UserSocialKey> {
    fun findByUserIdOrderByProvider(userId: UUID): List<UserSocialEntity>

    @Query("select count(s) from UserSocialEntity s where s.userId = :u and s.provider = :p and s.viaSignup = true")
    fun countSignupLink(@Param("u") userId: UUID, @Param("p") provider: String): Long

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserSocialEntity s where s.userId = :u and s.provider = :p")
    fun unlink(@Param("u") userId: UUID, @Param("p") provider: String): Int
}

interface SocialSignupJpaRepository : JpaRepository<SocialSignupEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SocialSignupEntity s where s.expiresAt < :cut")
    fun deleteExpiredBefore(@Param("cut") cut: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SocialSignupEntity s set s.usedAt = :now where s.id = :id and s.usedAt is null")
    fun use(@Param("id") id: UUID, @Param("now") now: Instant): Int
}
