package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.ShuttleRiderView
import com.ttokttok.application.port.`in`.ShuttleRouteUseCase
import com.ttokttok.application.port.`in`.TripHistoryRow
import com.ttokttok.application.port.`in`.TripPoint
import com.ttokttok.application.port.`in`.TripStatus
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

data class ShuttleRouteRow(
    val id: UUID,
    val institutionId: UUID,
    val name: String,
    val vehicleName: String?,
    val plateNo: String?,
    val driverName: String?,
    val driverPhone: String?,
    val active: Boolean,
)

data class ShuttleStopRow(val id: UUID, val routeId: UUID, val seq: Int, val name: String, val lat: Double, val lng: Double, val riderCount: Int)

data class ShuttleTripRow(
    val id: UUID,
    val institutionId: UUID,
    val routeId: UUID,
    val driverId: UUID,
    val driverName: String,
    val status: TripStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val lastLat: Double?,
    val lastLng: Double?,
    val lastSpeed: Double?,
    val lastAt: Instant?,
    val nextSeq: Int,
)

/** 학부모 쪽: 원생이 타는 노선·정류장 */
data class RiderAssignment(val studentId: UUID, val studentName: String, val institutionName: String, val routeId: UUID, val stopId: UUID)

interface ShuttleRoutePort {
    fun list(institutionId: InstitutionId, includeInactive: Boolean): List<ShuttleRouteRow>
    fun find(id: UUID): ShuttleRouteRow?
    fun insert(institutionId: InstitutionId, command: ShuttleRouteUseCase.SaveRoute, now: Instant): UUID
    fun update(id: UUID, command: ShuttleRouteUseCase.SaveRoute, now: Instant)

    fun stops(routeIds: Collection<UUID>): List<ShuttleStopRow>
    /** 순서 = 리스트 순서(1부터). id 가 null 이면 새로 만든다. 목록에 없는 기존 정류장은 지운다 */
    fun replaceStops(routeId: UUID, stops: List<ShuttleRouteUseCase.StopInput>)

    fun riders(routeId: UUID): List<ShuttleRiderView>
    fun replaceRiders(routeId: UUID, riders: List<ShuttleRouteUseCase.RiderInput>)
    /** 기관 소속이고 퇴원하지 않은 원생만 */
    fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID>
    fun assignmentsFor(studentIds: Collection<UUID>): List<RiderAssignment>
    /** 정류장에서 타는 원생의 연결된 보호자 계정 */
    fun guardiansAtStop(stopId: UUID): List<UserId>
    fun guardiansOnRoute(routeId: UUID): List<UserId>
}

interface ShuttleTripPort {
    fun insert(institutionId: InstitutionId, routeId: UUID, driver: UserId, now: Instant): UUID
    fun find(id: UUID): ShuttleTripRow?
    fun running(institutionId: InstitutionId): List<ShuttleTripRow>
    fun runningForRoutes(routeIds: Collection<UUID>): List<ShuttleTripRow>
    fun addPositions(tripId: UUID, points: List<TripPoint>, speed: Double?, now: Instant)
    fun updateLast(tripId: UUID, lat: Double, lng: Double, speed: Double?, at: Instant, nextSeq: Int)
    fun end(tripId: UUID, now: Instant)
    fun path(tripId: UUID, limit: Int): List<TripPoint>
    fun history(institutionId: InstitutionId, from: Instant, to: Instant): List<TripHistoryRow>
    /** 처음이면 true (이미 보냈으면 false) */
    fun markAlerted(tripId: UUID, stopId: UUID, now: Instant): Boolean
    fun closeIdle(lastBefore: Instant, now: Instant): Int
    fun purgePositions(before: Instant): Int
}
