package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// Phase 2 ③ 원생·학급·일정 (V16)

/** STU-009 건강/알레르기 특이사항 (FieldCrypto 암호문) */
@Entity @Table(name = "student_health")
class StudentHealthEntity(
    @Id var studentId: UUID,
    var allergiesEnc: String?,
    var conditionsEnc: String?,
    var notesEnc: String?,
    var updatedBy: UUID,
    var updatedAt: Instant,
)

/** STU-010 상담일지/메모 */
@Entity @Table(name = "student_counsel_note")
class StudentCounselNoteEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var authorId: UUID,
    var occurredOn: LocalDate,
    var body: String,
    var parentVisible: Boolean,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** STU-011 태그 */
@Entity @Table(name = "student_tag")
class StudentTagEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var color: String?,
    var createdAt: Instant,
)

@Entity @Table(name = "student_tag_map") @IdClass(StudentTagMapKey::class)
class StudentTagMapEntity(
    @Id var studentId: UUID,
    @Id var tagId: UUID,
)

data class StudentTagMapKey(var studentId: UUID? = null, var tagId: UUID? = null) : Serializable

/** CLS-003 주간 시간표 (weekday 1=월 … 7=일, smallint) */
@Entity @Table(name = "classroom_slot")
class ClassroomSlotEntity(
    @Id var id: UUID,
    var classroomId: UUID,
    var weekday: Short,
    var startTime: LocalTime,
    var endTime: LocalTime,
    var subject: String?,
    var room: String?,
    var teacherId: UUID?,
)

/** CLS-004 대기자 명단 (연락처 암호문) */
@Entity @Table(name = "classroom_waitlist")
class ClassroomWaitlistEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var classroomId: UUID,
    var studentName: String,
    var guardianName: String?,
    var phoneEnc: String,
    @JdbcTypeCode(SqlTypes.CHAR) var phoneLast4: String,
    var memo: String?,
    var status: String = "WAITING",
    var contactedAt: Instant? = null,
    var createdBy: UUID,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** STF-004 교사 출퇴근 (하루 1건) */
@Entity @Table(name = "staff_attendance")
class StaffAttendanceEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var userId: UUID,
    var workDate: LocalDate,
    var checkIn: Instant,
    var checkOut: Instant? = null,
)

/** EVT-002 반복 행사 템플릿 */
@Entity @Table(name = "event_template")
class EventTemplateEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var title: String,
    var body: String?,
    var location: String?,
    var startTime: LocalTime,
    var durationMinutes: Int,
    @JdbcTypeCode(SqlTypes.JSON) var targets: String,
    var rsvpEnabled: Boolean,
    var rsvpDeadlineDaysBefore: Int?,
    var reminderHoursBefore: Int?,
    var createdBy: UUID,
    var createdAt: Instant,
    var updatedAt: Instant,
)
