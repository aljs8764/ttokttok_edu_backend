package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// JPA 엔티티는 어댑터 내부에만 존재한다. 도메인 모델과의 변환은 Mappers.kt.

@Entity @Table(name = "institution")
class InstitutionEntity(
    @Id var id: UUID,
    var name: String,
    var ownerName: String,
    var lateThresholdMinutes: Int,
    var earlyLeaveThresholdMinutes: Int,
)

@Entity @Table(name = "app_user")
class UserEntity(
    @Id var id: UUID,
    var name: String,
    var email: String?,
    var phoneEnc: String?,
    var phoneHash: String?,
    var passwordHash: String,
    var mustChangePassword: Boolean,
)

@Entity @Table(name = "membership")
class MembershipEntity(
    @Id var id: UUID,
    var userId: UUID,
    var institutionId: UUID,
    var role: String,
    var title: String?,
)

@Entity @Table(name = "classroom")
class ClassroomEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var capacity: Int,
    var daysMask: Int,
    var startTime: LocalTime,
    var endTime: LocalTime,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "classroom_teacher", joinColumns = [JoinColumn(name = "classroom_id")])
    @Column(name = "user_id")
    var teacherIds: MutableSet<UUID> = mutableSetOf(),
    var deletedAt: Instant? = null,
)

@Entity @Table(name = "student")
class StudentEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var birthEnc: String,
    var grade: String?,
    var status: String,
)

@Entity @Table(name = "enrollment")
class EnrollmentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var studentId: UUID,
    var classroomId: UUID,
    var fromDate: LocalDate,
    var toDate: LocalDate?,
)

@Entity @Table(name = "guardian")
class GuardianEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var phoneEnc: String,
    var phoneHash: String,
    var phoneLast4: String,
    var relation: String?,
    var isPrimary: Boolean,
    var userId: UUID?,
    var linkStatus: String,
)

@Entity @Table(name = "destination")
class DestinationEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var type: String,
    var sortOrder: Int,
)

@Entity @Table(name = "attendance_day")
class AttendanceDayEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var classroomId: UUID,
    var date: LocalDate,
    var status: String,
    var isLate: Boolean,
    var isEarlyLeave: Boolean,
    var checkInAt: Instant?,
    var checkOutAt: Instant?,
    var nextDestinationId: UUID?,
    var updatedAt: Instant = Instant.now(),
)

@Entity @Table(name = "attendance_event")
class AttendanceEventEntity(
    @Id var id: UUID,
    var attendanceDayId: UUID,
    var institutionId: UUID,
    var studentId: UUID,
    var type: String,
    var fromStatus: String,
    var toStatus: String,
    var actorId: UUID,
    var source: String,
    var reason: String?,
    var destinationId: UUID?,
    var occurredAt: Instant,
    var idempotencyKey: String?,
    @Column(insertable = false, updatable = false) var serverAt: Instant? = null,
)

@Entity @Table(name = "outbox")
class OutboxEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var eventType: String,
    @JdbcTypeCode(SqlTypes.JSON) var payload: String,
    var status: String = "PENDING",
    var attempts: Int = 0,
    var nextAttemptAt: Instant,
    var lastError: String? = null,
    var processedAt: Instant? = null,
)

@Entity @Table(name = "device_token")
class DeviceTokenEntity(
    @Id var token: String,
    var userId: UUID,
    var flavor: String,
    var platform: String,
    var lastSeenAt: Instant,
)

@Entity @Table(name = "notification_log")
class NotificationLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var institutionId: UUID,
    var channel: String,
    var templateCode: String,
    var recipientCount: Int,
    var status: String,
    var error: String?,
    var createdAt: Instant,
)
