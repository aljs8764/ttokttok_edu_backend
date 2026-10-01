package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.NoticeEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
