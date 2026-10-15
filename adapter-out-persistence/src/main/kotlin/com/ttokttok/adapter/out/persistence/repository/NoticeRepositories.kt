package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.NoticeEntity
import com.ttokttok.adapter.out.persistence.entity.NoticeRecipientEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface NoticeJpaRepository : JpaRepository<NoticeEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): NoticeEntity?

    @Query(
        value = "select * from notice where id = :id and institution_id = :institutionId for update",
        nativeQuery = true,
    )
    fun findForUpdate(@Param("id") id: UUID, @Param("institutionId") institutionId: UUID): NoticeEntity?

    @Query(
        value = """select * from notice where status = 'SCHEDULED' and scheduled_at <= :now
                   order by scheduled_at limit :limit for update skip locked""",
        nativeQuery = true,
    )
    fun lockDue(@Param("now") now: Instant, @Param("limit") limit: Int): List<NoticeEntity>

    /** 고정글 먼저, 그다음 발송(예약) 시각 최신순 */
    @Query(
        """select n from NoticeEntity n
           where n.institutionId = :institutionId
             and (:authorId is null or n.authorId = :authorId)
             and (:kind is null or n.kind = :kind)
             and (:status is null or n.status = :status)
           order by n.pinned desc, n.scheduledAt desc""",
    )
    fun search(
        @Param("institutionId") institutionId: UUID, @Param("authorId") authorId: UUID?,
        @Param("kind") kind: String?, @Param("status") status: String?, pageable: Pageable,
    ): Page<NoticeEntity>

    @Query(
        """select n from NoticeEntity n
           where n.institutionId = :institutionId and n.status = 'SENT'
             and n.sentAt >= :from and n.sentAt <= :to
             and (:authorId is null or n.authorId = :authorId)""",
    )
    fun findSentBetween(
        @Param("institutionId") institutionId: UUID, @Param("from") from: Instant, @Param("to") to: Instant,
        @Param("authorId") authorId: UUID?,
    ): List<NoticeEntity>
}

interface NoticeRecipientJpaRepository : JpaRepository<NoticeRecipientEntity, Long> {
    fun findByNoticeId(noticeId: UUID): List<NoticeRecipientEntity>
    fun findByNoticeIdIn(noticeIds: Collection<UUID>): List<NoticeRecipientEntity>
    fun findByNoticeIdInAndGuardianUserId(noticeIds: Collection<UUID>, guardianUserId: UUID): List<NoticeRecipientEntity>
    fun existsByNoticeIdAndStudentIdAndGuardianUserId(noticeId: UUID, studentId: UUID, guardianUserId: UUID): Boolean

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NoticeRecipientEntity r set r.readAt = :at where r.noticeId = :n and r.guardianUserId = :u and r.readAt is null")
    fun markRead(@Param("n") noticeId: UUID, @Param("u") userId: UUID, @Param("at") at: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NoticeRecipientEntity r set r.deliveredAt = coalesce(r.deliveredAt, :at) where r.noticeId = :n and r.guardianUserId in (:ids)")
    fun markDelivered(@Param("n") noticeId: UUID, @Param("ids") userIds: Collection<UUID>, @Param("at") at: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NoticeRecipientEntity r set r.resentCount = r.resentCount + 1 where r.noticeId = :n and r.studentId in (:ids)")
    fun incrementResent(@Param("n") noticeId: UUID, @Param("ids") studentIds: Collection<UUID>): Int
}
