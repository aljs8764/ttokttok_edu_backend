package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** ATT-007 결석·지각·조퇴 사전 신청 (V11) */
@Entity @Table(name = "absence_request")
class AbsenceRequestEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var guardianUserId: UUID,
    var date: LocalDate,
    var kind: String,
    var expectedTime: LocalTime?,
    var reason: String,
    var status: String,
    var acknowledgedBy: UUID? = null,
    var acknowledgedAt: Instant? = null,
    var createdAt: Instant,
)
