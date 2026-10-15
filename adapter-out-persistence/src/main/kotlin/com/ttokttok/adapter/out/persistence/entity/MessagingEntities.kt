package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * NTC-007·008 대화 (V10). (아이, 보호자 계정, 알림장) 한 쌍이 하나의 대화.
 * parent_unread = 보호자가 안 읽은 교직원 메시지, staff_unread = 교직원이 안 읽은 보호자 메시지
 */
@Entity @Table(name = "conversation")
class ConversationEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var guardianUserId: UUID,
    var noticeId: UUID?,
    var lastMessageAt: Instant,
    var lastMessagePreview: String = "",
    var parentUnread: Int = 0,
    var staffUnread: Int = 0,
    var createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "message")
class MessageEntity(
    @Id var id: UUID,
    var conversationId: UUID,
    var senderId: UUID,
    var senderSide: String,
    var body: String,
    var createdAt: Instant,
)
