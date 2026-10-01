package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "school_event")
class SchoolEventEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var authorId: UUID,
    var title: String,
    var body: String?,
    var location: String?,
    var startsAt: Instant,
    var endsAt: Instant?,
    @JdbcTypeCode(SqlTypes.JSON) var targets: String,
    var rsvpEnabled: Boolean,
    var rsvpDeadline: Instant?,
    var reminderHoursBefore: Int,
    var remindedAt: Instant?,
    var status: String,
    var createdAt: Instant,
    var updatedAt: Instant = Instant.now(),
)
