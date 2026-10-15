package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 다기관 아이 (V9) — 가족이 관리하는 "아이" (기관 무관). 생년월일은 원생과 같은 키로 암호화 */
@Entity @Table(name = "child")
class ChildEntity(
    @Id var id: UUID,
    var name: String,
    var birthEnc: String?,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

/** 아이를 볼 수 있는 보호자 계정 */
@Entity @Table(name = "child_guardian") @IdClass(ChildGuardianKey::class)
class ChildGuardianEntity(
    @Id var childId: UUID,
    @Id var userId: UUID,
    var createdAt: Instant = Instant.now(),
)

data class ChildGuardianKey(var childId: UUID? = null, var userId: UUID? = null) : Serializable

/** SET-004 채널별 알림 옵션 (V15). 행이 없으면 모두 켜짐 */
@Entity @Table(name = "notification_setting") @IdClass(NotificationSettingKey::class)
class NotificationSettingEntity(
    @Id var institutionId: UUID,
    @Id var category: String,
    var pushEnabled: Boolean = true,
    var alimtalkEnabled: Boolean = true,
    var updatedAt: Instant,
)

data class NotificationSettingKey(var institutionId: UUID? = null, var category: String? = null) : Serializable

/** PAR-008 아이 단위 위치 정보 제공 동의. 행이 없으면 동의로 본다 */
@Entity @Table(name = "child_location_consent")
class ChildLocationConsentEntity(
    @Id var childId: UUID,
    var granted: Boolean,
    var updatedBy: UUID,
    var updatedAt: Instant,
)

/** NTC-003 알림장 템플릿 (V12) */
@Entity @Table(name = "notice_template")
class NoticeTemplateEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var kind: String,
    var name: String,
    var title: String,
    var body: String,
    var createdBy: UUID,
    var useCount: Int = 0,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** 감사 로그 (V3). 추가 전용 */
@Entity @Table(name = "audit_log")
class AuditLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var institutionId: UUID,
    var actorId: UUID,
    var action: String,
    var resource: String,
    var resourceId: String?,
    @JdbcTypeCode(SqlTypes.JSON) var diff: String,
    var at: Instant,
)

/** 일별 출결 집계 (V3, 23:50 배치) */
@Entity @Table(name = "daily_attendance_stat") @IdClass(DailyAttendanceStatKey::class)
class DailyAttendanceStatEntity(
    @Id var classroomId: UUID,
    @Id var date: LocalDate,
    var institutionId: UUID,
    var scheduled: Int,
    var present: Int,
    var late: Int,
    var earlyLeave: Int,
    var absent: Int,
    var updatedAt: Instant,
)

data class DailyAttendanceStatKey(var classroomId: UUID? = null, var date: LocalDate? = null) : Serializable
