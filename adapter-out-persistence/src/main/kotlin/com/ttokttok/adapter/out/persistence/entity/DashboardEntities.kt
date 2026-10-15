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
import java.util.UUID

// Phase 3 P3-⑥ 역할별 대시보드 (V25)

/** DASH-015·023 교직원 공지·업무 지시 (to_all = 기관의 모든 교직원, 아니면 staff_memo_target 에 지정한 사람만) */
@Entity @Table(name = "staff_memo")
class StaffMemoEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var authorId: UUID,
    var title: String,
    var body: String?,
    var dueDate: LocalDate?,
    var toAll: Boolean,
    var canceledAt: Instant? = null,
    var createdAt: Instant,
)

@Entity @Table(name = "staff_memo_target") @IdClass(StaffMemoUserKey::class)
class StaffMemoTargetEntity(
    @Id var memoId: UUID,
    @Id var userId: UUID,
)

/** 교직원별 확인·완료 (행이 없으면 안 읽음) */
@Entity @Table(name = "staff_memo_ack") @IdClass(StaffMemoUserKey::class)
class StaffMemoAckEntity(
    @Id var memoId: UUID,
    @Id var userId: UUID,
    var readAt: Instant,
    var doneAt: Instant? = null,
)

data class StaffMemoUserKey(var memoId: UUID? = null, var userId: UUID? = null) : Serializable

/** DASH-017 위젯 배치 (사용자·대시보드 종류별) */
@Entity @Table(name = "user_dashboard_layout") @IdClass(UserDashboardLayoutKey::class)
class UserDashboardLayoutEntity(
    @Id var userId: UUID,
    @Id var kind: String,
    @JdbcTypeCode(SqlTypes.JSON) var widgets: String,
    var updatedAt: Instant,
)

data class UserDashboardLayoutKey(var userId: UUID? = null, var kind: String? = null) : Serializable
