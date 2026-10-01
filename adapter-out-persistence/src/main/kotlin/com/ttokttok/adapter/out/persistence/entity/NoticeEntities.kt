package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
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
)
