package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.CommunityAnonEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityAnonKey
import com.ttokttok.adapter.out.persistence.entity.CommunityCommentEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityLikeEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityLikeKey
import com.ttokttok.adapter.out.persistence.entity.CommunityMemberEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityPostEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityReportEntity
import com.ttokttok.adapter.out.persistence.entity.CommunitySanctionEntity
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

interface CommunityPostJpaRepository : JpaRepository<CommunityPostEntity, UUID> {
    /** 같은 글의 익명 번호 발급을 직렬화하려고 글 행을 잠근다 (select … for update) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPostEntity p where p.id = :id")
    fun lockById(@Param("id") id: UUID): CommunityPostEntity?

    fun countByAuthorIdAndCreatedAtAfter(authorId: UUID, since: Instant): Long

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPostEntity p set p.deletedAt = :now, p.updatedAt = :now where p.id = :id and p.deletedAt is null")
    fun softDelete(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPostEntity p set p.commentCount = p.commentCount + :d where p.id = :id and p.commentCount + :d >= 0")
    fun addCommentCount(@Param("id") id: UUID, @Param("d") delta: Int): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPostEntity p set p.likeCount = case when p.likeCount + :d < 0 then 0 else p.likeCount + :d end where p.id = :id")
    fun addLikeCount(@Param("id") id: UUID, @Param("d") delta: Int): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPostEntity p set p.reportCount = p.reportCount + 1 where p.id = :id")
    fun incrementReportCount(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPostEntity p set p.hidden = :h where p.id = :id")
    fun setHidden(@Param("id") id: UUID, @Param("h") hidden: String?): Int
}

interface CommunityAnonJpaRepository : JpaRepository<CommunityAnonEntity, CommunityAnonKey> {
    @Query("select coalesce(max(a.anonNo), 0) from CommunityAnonEntity a where a.postId = :p")
    fun maxAnonNo(@Param("p") postId: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into community_anon (post_id, user_id, anon_no) values (:p, :u, :n) on conflict do nothing", nativeQuery = true)
    fun insertIfAbsent(@Param("p") postId: UUID, @Param("u") userId: UUID, @Param("n") anonNo: Int): Int
}

interface CommunityCommentJpaRepository : JpaRepository<CommunityCommentEntity, UUID> {
    fun findByPostId(postId: UUID): List<CommunityCommentEntity>
    fun countByAuthorIdAndCreatedAtAfter(authorId: UUID, since: Instant): Long

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityCommentEntity c set c.deletedAt = :now where c.id = :id and c.deletedAt is null")
    fun softDelete(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityCommentEntity c set c.likeCount = case when c.likeCount + :d < 0 then 0 else c.likeCount + :d end where c.id = :id")
    fun addLikeCount(@Param("id") id: UUID, @Param("d") delta: Int): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityCommentEntity c set c.reportCount = c.reportCount + 1 where c.id = :id")
    fun incrementReportCount(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityCommentEntity c set c.hidden = :h where c.id = :id")
    fun setHidden(@Param("id") id: UUID, @Param("h") hidden: String?): Int

    /** 이 글에서 내가 공감한 댓글 */
    @Query(
        """select l.targetId from CommunityLikeEntity l, CommunityCommentEntity c
            where c.id = l.targetId and l.targetType = 'COMMENT' and l.userId = :u and c.postId = :p""",
    )
    fun likedIn(@Param("p") postId: UUID, @Param("u") userId: UUID): List<UUID>
}

interface CommunityLikeJpaRepository : JpaRepository<CommunityLikeEntity, CommunityLikeKey> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CommunityLikeEntity l where l.targetType = :t and l.targetId = :id and l.userId = :u")
    fun remove(@Param("t") targetType: String, @Param("id") targetId: UUID, @Param("u") userId: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "insert into community_like (target_type, target_id, user_id, created_at) values (:t, :id, :u, :now) on conflict do nothing",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("t") targetType: String, @Param("id") targetId: UUID, @Param("u") userId: UUID, @Param("now") now: Instant): Int
}

interface CommunityReportJpaRepository : JpaRepository<CommunityReportEntity, UUID> {
    @Query("select r.reason, count(r) from CommunityReportEntity r where r.targetType = :t and r.targetId = :id group by r.reason")
    fun reasonCounts(@Param("t") targetType: String, @Param("id") targetId: UUID): List<Array<Any?>>

    @Query("select r.memo from CommunityReportEntity r where r.targetType = :t and r.targetId = :id and r.memo is not null order by r.createdAt")
    fun memos(@Param("t") targetType: String, @Param("id") targetId: UUID, pageable: Pageable): List<String>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update CommunityReportEntity r set r.status = 'RESOLVED', r.resolution = :res, r.resolvedBy = :u, r.resolvedAt = :now
            where r.targetType = :t and r.targetId = :id and r.status = 'OPEN'""",
    )
    fun resolve(@Param("t") targetType: String, @Param("id") targetId: UUID, @Param("u") actor: UUID, @Param("res") resolution: String, @Param("now") now: Instant): Int
}

interface CommunitySanctionJpaRepository : JpaRepository<CommunitySanctionEntity, UUID> {
    @Query("select count(s) from CommunitySanctionEntity s where s.userId = :u and (s.untilAt is null or s.untilAt > :now)")
    fun countActive(@Param("u") userId: UUID, @Param("now") now: Instant): Long
}

interface CommunityMemberJpaRepository : JpaRepository<CommunityMemberEntity, UUID>
