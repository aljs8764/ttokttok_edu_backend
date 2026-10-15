package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
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

/** 생성 시점 대상 학생 스냅샷 (미응답 = 대상 − 응답) */
@Entity @Table(name = "school_event_target") @IdClass(EventStudentKey::class)
class SchoolEventTargetEntity(
    @Id var eventId: UUID,
    @Id var studentId: UUID,
)

/** 학생 단위 RSVP 응답 (마감 전까지 덮어쓰기) */
@Entity @Table(name = "event_response") @IdClass(EventStudentKey::class)
class EventResponseEntity(
    @Id var eventId: UUID,
    @Id var studentId: UUID,
    var answer: String,
    var reason: String?,
    var respondedBy: UUID,
    var respondedAt: Instant,
)

data class EventStudentKey(var eventId: UUID? = null, var studentId: UUID? = null) : Serializable
