package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.SchoolEventEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SchoolEventJpaRepository : JpaRepository<SchoolEventEntity, UUID> {
    fun findByIdAndInstitutionId(id: UUID, institutionId: UUID): SchoolEventEntity?

    @Query(value = "select * from school_event where id = :id and institution_id = :institutionId for update", nativeQuery = true)
    fun findForUpdate(@Param("id") id: UUID, @Param("institutionId") institutionId: UUID): SchoolEventEntity?

    @Query(
        """select e from SchoolEventEntity e
           where e.institutionId = :institutionId and (:authorId is null or e.authorId = :authorId)
           order by e.startsAt desc""",
    )
    fun search(@Param("institutionId") institutionId: UUID, @Param("authorId") authorId: UUID?, pageable: Pageable): Page<SchoolEventEntity>

    @Query(
        """select e from SchoolEventEntity e
           where e.institutionId = :institutionId and (:authorId is null or e.authorId = :authorId)
             and e.startsAt >= :startsFrom
           order by e.startsAt asc""",
    )
    fun searchFrom(
        @Param("institutionId") institutionId: UUID, @Param("authorId") authorId: UUID?,
        @Param("startsFrom") startsFrom: Instant, pageable: Pageable,
    ): Page<SchoolEventEntity>

    @Query(
        value = """select * from school_event
                   where status = 'ACTIVE' and rsvp_enabled and reminded_at is null and rsvp_deadline > :now
                     and rsvp_deadline - make_interval(hours => reminder_hours_before) <= :now
                   order by rsvp_deadline limit :limit for update skip locked""",
        nativeQuery = true,
    )
    fun lockReminderCandidates(@Param("now") now: Instant, @Param("limit") limit: Int): List<SchoolEventEntity>
}
