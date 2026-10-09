package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Phase 2 ⑥ ATT-009 셔틀버스 실시간 GPS 관제

data class ShuttleStopView(val id: UUID, val seq: Int, val name: String, val lat: Double, val lng: Double, val riderCount: Int)

data class ShuttleRouteView(
    val id: UUID,
    val name: String,
    val vehicleName: String?,
    val plateNo: String?,
    val driverName: String?,
    val driverPhone: String?,
    val active: Boolean,
    val stops: List<ShuttleStopView>,
    val riderCount: Int,
    /** 지금 운행 중이면 그 운행 id */
    val runningTripId: UUID?,
)

data class ShuttleRiderView(val studentId: UUID, val studentName: String, val classroomName: String?, val stopId: UUID, val stopName: String)

enum class TripStatus { RUNNING, ENDED }

/** 정류장별 도착 예정 (지난 정류장은 passed=true, eta 없음) */
data class StopEta(val stopId: UUID, val seq: Int, val name: String, val lat: Double, val lng: Double, val passed: Boolean, val etaMinutes: Int?)

data class TripLiveView(
    val id: UUID,
    val routeId: UUID,
    val routeName: String,
    val vehicleName: String?,
    val plateNo: String?,
    val driverName: String,
    val status: TripStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val lat: Double?,
    val lng: Double?,
    val speedKmh: Double?,
    val lastAt: Instant?,
    /** 마지막 위치가 2분 넘게 안 들어옴 (통신 끊김·앱 종료 의심) */
    val stale: Boolean,
    val stops: List<StopEta>,
)

data class TripPoint(val lat: Double, val lng: Double, val recordedAt: Instant)

data class TripHistoryRow(val id: UUID, val routeName: String, val driverName: String, val status: TripStatus, val startedAt: Instant, val endedAt: Instant?, val pointCount: Int)

/** 관리자: 노선·정류장·탑승 원생 (ATT-009) */
interface ShuttleRouteUseCase {
    data class SaveRoute(val name: String, val vehicleName: String?, val plateNo: String?, val driverName: String?, val driverPhone: String?, val active: Boolean)
    data class StopInput(val id: UUID?, val name: String, val lat: Double, val lng: Double)
    data class RiderInput(val studentId: UUID, val stopId: UUID)

    /** 직원 누구나 (기사 앱에서도 노선을 고른다) */
    fun list(actor: UserId, institutionId: InstitutionId, includeInactive: Boolean): List<ShuttleRouteView>
    fun create(actor: UserId, institutionId: InstitutionId, command: SaveRoute): ShuttleRouteView
    fun update(actor: UserId, institutionId: InstitutionId, routeId: UUID, command: SaveRoute): ShuttleRouteView
    /** 순서대로 전체 교체. id 가 있으면 유지(탑승 배정 보존), 빠진 정류장은 지워지고 그 정류장 탑승 배정도 지워진다 */
    fun replaceStops(actor: UserId, institutionId: InstitutionId, routeId: UUID, stops: List<StopInput>): ShuttleRouteView
    fun riders(actor: UserId, institutionId: InstitutionId, routeId: UUID): List<ShuttleRiderView>
    fun replaceRiders(actor: UserId, institutionId: InstitutionId, routeId: UUID, riders: List<RiderInput>): List<ShuttleRiderView>
}

/** 기사(교직원) 운행 + 관리자 관제 */
interface ShuttleTripUseCase {
    fun start(actor: UserId, institutionId: InstitutionId, routeId: UUID): TripLiveView
    /** 기사 앱이 모아 둔 좌표를 보낸다 (오프라인 동안 쌓인 것 포함). 운행을 시작한 사람만 */
    fun report(actor: UserId, institutionId: InstitutionId, tripId: UUID, points: List<TripPoint>, speed: Double?): TripLiveView
    fun end(actor: UserId, institutionId: InstitutionId, tripId: UUID): TripLiveView
    fun live(actor: UserId, institutionId: InstitutionId): List<TripLiveView>
    fun get(actor: UserId, institutionId: InstitutionId, tripId: UUID): TripLiveView
    fun path(actor: UserId, institutionId: InstitutionId, tripId: UUID): List<TripPoint>
    fun history(actor: UserId, institutionId: InstitutionId, date: LocalDate): List<TripHistoryRow>

    /** 스케줄러: 오래 위치가 안 들어온 운행 자동 종료, 오래된 좌표 삭제 */
    fun closeAbandoned(now: Instant): Int
    fun purgePositions(before: Instant): Int
}

/** 학부모: 내 아이가 타는 셔틀 */
data class MyShuttleView(
    val studentId: UUID,
    val studentName: String,
    val institutionName: String,
    val routeId: UUID,
    val routeName: String,
    val vehicleName: String?,
    val plateNo: String?,
    val driverPhone: String?,
    val stopName: String,
    val stopLat: Double,
    val stopLng: Double,
    /** 운행 중이 아니면 null */
    val trip: MyShuttleTrip?,
)

data class MyShuttleTrip(
    val id: UUID,
    val lat: Double?,
    val lng: Double?,
    val lastAt: Instant?,
    val stale: Boolean,
    /** 우리 정류장을 이미 지났으면 true */
    val passed: Boolean,
    val etaMinutes: Int?,
    /** 우리 정류장 전까지 남은 정류장 수 */
    val stopsBefore: Int,
)

interface MyShuttleQuery {
    fun myShuttles(parent: UserId, childId: UUID?): List<MyShuttleView>
}
