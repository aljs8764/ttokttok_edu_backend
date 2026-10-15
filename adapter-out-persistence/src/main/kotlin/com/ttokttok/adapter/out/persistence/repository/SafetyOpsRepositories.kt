package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.AttendanceMissingAlertEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomProgressEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomTextbookEntity
import com.ttokttok.adapter.out.persistence.entity.EventReviewEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AttendanceMissingAlertJpaRepository : JpaRepository<AttendanceMissingAlertEntity, UUID>

interface ClassroomTextbookJpaRepository : JpaRepository<ClassroomTextbookEntity, UUID> {
    fun findByClassroomIdOrderByArchivedAscCreatedAtAsc(classroomId: UUID): List<ClassroomTextbookEntity>
    fun findByClassroomIdAndArchivedFalseOrderByCreatedAt(classroomId: UUID): List<ClassroomTextbookEntity>
}

interface ClassroomProgressJpaRepository : JpaRepository<ClassroomProgressEntity, UUID>

interface EventReviewJpaRepository : JpaRepository<EventReviewEntity, UUID> {
    fun findByEventIdAndUserId(eventId: UUID, userId: UUID): EventReviewEntity?

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from EventReviewEntity r where r.eventId = :e and r.userId = :u")
    fun deleteByEventAndUser(@Param("e") eventId: UUID, @Param("u") userId: UUID): Int
}
