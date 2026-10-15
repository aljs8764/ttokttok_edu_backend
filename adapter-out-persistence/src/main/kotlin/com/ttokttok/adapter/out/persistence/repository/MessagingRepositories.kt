package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.ConversationEntity
import com.ttokttok.adapter.out.persistence.entity.MessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ConversationJpaRepository : JpaRepository<ConversationEntity, UUID> {
    @Query("select coalesce(sum(c.parentUnread), 0) from ConversationEntity c where c.guardianUserId = :g")
    fun parentUnreadTotal(@Param("g") guardianUserId: UUID): Long

    /** 보호자가 보냄: 교직원 안읽음 +1, 보호자 쪽은 방금 봤으므로 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update ConversationEntity c set c.lastMessageAt = :now, c.lastMessagePreview = :preview,
                  c.parentUnread = 0, c.staffUnread = c.staffUnread + 1
            where c.id = :id""",
    )
    fun onParentMessage(@Param("id") id: UUID, @Param("preview") preview: String, @Param("now") now: Instant): Int

    /** 교직원이 보냄: 보호자 안읽음 +1, 교직원 쪽은 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update ConversationEntity c set c.lastMessageAt = :now, c.lastMessagePreview = :preview,
                  c.staffUnread = 0, c.parentUnread = c.parentUnread + 1
            where c.id = :id""",
    )
    fun onStaffMessage(@Param("id") id: UUID, @Param("preview") preview: String, @Param("now") now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConversationEntity c set c.parentUnread = 0 where c.id = :id and c.parentUnread <> 0")
    fun markReadByParent(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConversationEntity c set c.staffUnread = 0 where c.id = :id and c.staffUnread <> 0")
    fun markReadByStaff(@Param("id") id: UUID): Int

    @Query(
        """select count(c) from ConversationEntity c
            where c.id = :id and exists (
                select 1 from EnrollmentEntity e, ClassroomTeacherEntity ct
                 where ct.classroomId = e.classroomId and e.studentId = c.studentId and e.toDate is null and ct.userId = :teacher)""",
    )
    fun countTaughtBy(@Param("id") id: UUID, @Param("teacher") teacherId: UUID): Long
}

interface MessageJpaRepository : JpaRepository<MessageEntity, UUID> {
    @Query("select m, u.name from MessageEntity m join UserEntity u on u.id = m.senderId where m.id = :id")
    fun findView(@Param("id") id: UUID): List<Array<Any?>>
}
