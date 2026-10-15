package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Phase 3 P3-① 안전·운영 고도화 (V22)

/** ATT-010 미등원 초과 경고 (출결 1건당 1번) */
@Entity @Table(name = "attendance_missing_alert")
class AttendanceMissingAlertEntity(
    @Id var attendanceDayId: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var classroomId: UUID,
    var previousInstitutionName: String?,
    var previousCheckoutAt: Instant?,
    var notifiedParent: Boolean,
    var sentAt: Instant,
)

/** CLS-005 반별 교재 */
@Entity @Table(name = "classroom_textbook")
class ClassroomTextbookEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var classroomId: UUID,
    var name: String,
    var publisher: String?,
    var memo: String?,
    var archived: Boolean = false,
    var createdAt: Instant,
)

/** CLS-005 반별 진도 */
@Entity @Table(name = "classroom_progress")
class ClassroomProgressEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var classroomId: UUID,
    var date: LocalDate,
    var textbookId: UUID?,
    var content: String,
    var homework: String?,
    var authorId: UUID,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** EVT-009 행사 후기 (행사 × 작성자 1건) */
@Entity @Table(name = "event_review")
class EventReviewEntity(
    @Id var id: UUID,
    var eventId: UUID,
    var studentId: UUID,
    var userId: UUID,
    var body: String,
    var hidden: Boolean = false,
    var createdAt: Instant,
    var updatedAt: Instant,
)
