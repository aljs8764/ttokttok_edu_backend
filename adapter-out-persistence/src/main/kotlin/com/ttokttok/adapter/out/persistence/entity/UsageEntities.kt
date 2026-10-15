package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

// Phase 3 P3-③ SAAS-006 사용량·이상 탐지 (V24)

/** 기관별 시간 단위 API 사용량 (기관을 모르는 요청은 institution_id = 0…0) */
@Entity @Table(name = "api_usage_hourly") @IdClass(ApiUsageHourlyKey::class)
class ApiUsageHourlyEntity(
    @Id var institutionId: UUID,
    @Id var hour: Instant,
    var requests: Int = 0,
    var errors: Int = 0,
    var denied: Int = 0,
    var throttled: Int = 0,
    var activeUsers: Int = 0,
    var loginSuccess: Int = 0,
    var loginFailed: Int = 0,
)

data class ApiUsageHourlyKey(var institutionId: UUID? = null, var hour: Instant? = null) : Serializable

@Entity @Table(name = "usage_alert")
class UsageAlertEntity(
    @Id var id: UUID,
    var institutionId: UUID?,
    var kind: String,
    var hour: Instant,
    var value: Double,
    var baseline: Double?,
    var message: String,
    var createdAt: Instant,
    var acknowledgedAt: Instant? = null,
    var acknowledgedBy: UUID? = null,
)
