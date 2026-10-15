package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

// Phase 2 ⑥ ATT-009 셔틀버스 실시간 GPS 관제 (V19). 아이의 위치는 수집하지 않는다

@Entity @Table(name = "shuttle_route")
class ShuttleRouteEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var vehicleName: String?,
    var plateNo: String?,
    var driverName: String?,
    var driverPhone: String?,
    var active: Boolean = true,
    var createdAt: Instant,
    var updatedAt: Instant,
)

@Entity @Table(name = "shuttle_stop")
class ShuttleStopEntity(
    @Id var id: UUID,
    var routeId: UUID,
    var seq: Int,
    var name: String,
    var lat: Double,
    var lng: Double,
)

/** 노선별 탑승 원생과 타고 내리는 정류장 */
@Entity @Table(name = "shuttle_rider") @IdClass(ShuttleRiderKey::class)
class ShuttleRiderEntity(
    @Id var routeId: UUID,
    @Id var studentId: UUID,
    var stopId: UUID,
)

data class ShuttleRiderKey(var routeId: UUID? = null, var studentId: UUID? = null) : Serializable

@Entity @Table(name = "shuttle_trip")
class ShuttleTripEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var routeId: UUID,
    var driverId: UUID,
    var status: String = "RUNNING",
    var startedAt: Instant,
    var endedAt: Instant? = null,
    var lastLat: Double? = null,
    var lastLng: Double? = null,
    var lastSpeed: Double? = null,
    var lastAt: Instant? = null,
    var nextSeq: Int = 1,
)

/** 좌표 이력 (30일 뒤 정리) */
@Entity @Table(name = "shuttle_position")
class ShuttlePositionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var tripId: UUID,
    var lat: Double,
    var lng: Double,
    var speed: Double?,
    var heading: Double? = null,
    var recordedAt: Instant,
    var receivedAt: Instant,
)

/** 정류장별 "곧 도착" 푸시는 운행당 1번 */
@Entity @Table(name = "shuttle_stop_alert") @IdClass(ShuttleStopAlertKey::class)
class ShuttleStopAlertEntity(
    @Id var tripId: UUID,
    @Id var stopId: UUID,
    var sentAt: Instant,
)

data class ShuttleStopAlertKey(var tripId: UUID? = null, var stopId: UUID? = null) : Serializable
