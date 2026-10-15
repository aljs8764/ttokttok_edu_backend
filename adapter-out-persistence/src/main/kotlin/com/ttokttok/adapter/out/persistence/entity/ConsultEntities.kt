package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** NTC-008 상담 예약 슬롯 (V21) */
@Entity @Table(name = "consult_slot")
class ConsultSlotEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var teacherId: UUID,
    var startsAt: Instant,
    var endsAt: Instant,
    var mode: String,
    var location: String?,
    var status: String = "OPEN",
    var studentId: UUID? = null,
    var bookedBy: UUID? = null,
    var topic: String? = null,
    var bookedAt: Instant? = null,
    var cancelReason: String? = null,
    var createdBy: UUID,
    var createdAt: Instant,
    var updatedAt: Instant,
)
