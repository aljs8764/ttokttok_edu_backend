package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "notice")
class NoticeEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var authorId: UUID,
    var kind: String,
    var title: String,
    var body: String,
    var pinned: Boolean,
    @JdbcTypeCode(SqlTypes.JSON) var targets: String,
    var status: String,
    var scheduledAt: Instant,
    var sentAt: Instant?,
    var lastResentAt: Instant?,
    var createdAt: Instant,
    var updatedAt: Instant = Instant.now(),
    @JdbcTypeCode(SqlTypes.JSON) var attachments: String = "[]",
)

/** 발송 시점 수신자 스냅샷 (학생 × 연결된 보호자 계정). 앱 미연결 학생은 guardian_user_id = null 한 행 */
@Entity @Table(name = "notice_recipient")
class NoticeRecipientEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var noticeId: UUID,
    var studentId: UUID,
    var guardianUserId: UUID?,
    var deliveredAt: Instant?,
    var readAt: Instant?,
    var resentCount: Int = 0,
)
