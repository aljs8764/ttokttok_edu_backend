package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.MyShuttleQuery
import com.ttokttok.application.port.`in`.MyShuttleTrip
import com.ttokttok.application.port.`in`.MyShuttleView
import com.ttokttok.application.port.`in`.ShuttleRiderView
import com.ttokttok.application.port.`in`.ShuttleRouteUseCase
import com.ttokttok.application.port.`in`.ShuttleRouteView
import com.ttokttok.application.port.`in`.ShuttleStopView
import com.ttokttok.application.port.`in`.ShuttleTripUseCase
import com.ttokttok.application.port.`in`.StopEta
import com.ttokttok.application.port.`in`.TripHistoryRow
import com.ttokttok.application.port.`in`.TripLiveView
import com.ttokttok.application.port.`in`.TripPoint
import com.ttokttok.application.port.`in`.TripStatus
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.ShuttleRoutePort
import com.ttokttok.application.port.out.ShuttleRouteRow
import com.ttokttok.application.port.out.ShuttleStopRow
import com.ttokttok.application.port.out.ShuttleTripPort
import com.ttokttok.application.port.out.ShuttleTripRow
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ETA 계산. 도로 지도 API 없이 직선거리 × 도로 보정계수로 근사한다.
 * - 속도: 기기가 준 속도(3~16 m/s)와 시내 평균 25km/h 를 반씩 섞는다. 정차 중이면 평균 속도만
 * - 중간 정류장마다 30초 정차
 * - 정류장 80m 안에 들어오면 지난 것으로 본다
 */
object ShuttleEta {
    const val PASS_RADIUS_M = 80.0
    const val ARRIVING_RADIUS_M = 500.0
    const val ARRIVING_MINUTES = 3
    private const val ROAD_FACTOR = 1.3
    private const val DEFAULT_SPEED = 25_000.0 / 3600   // m/s
    private const val DWELL_SECONDS = 30.0
    val STALE_AFTER: Duration = Duration.ofMinutes(2)

    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun speed(reported: Double?): Double =
        if (reported != null && reported >= 3.0) (min(reported, 16.0) + DEFAULT_SPEED) / 2 else DEFAULT_SPEED

    /** 지금 위치에서 nextSeq 이후 정류장을 지났는지 판단해 새 nextSeq 를 돌려준다 */
    fun advance(nextSeq: Int, lat: Double, lng: Double, stops: List<ShuttleStopRow>): Int {
        var next = nextSeq
        stops.filter { it.seq >= nextSeq }.forEach { s ->
            if (distance(lat, lng, s.lat, s.lng) <= PASS_RADIUS_M) next = max(next, s.seq + 1)
        }
        return next
    }

    fun etas(trip: ShuttleTripRow, stops: List<ShuttleStopRow>): List<StopEta> {
        val sorted = stops.sortedBy { it.seq }
        val lat = trip.lastLat
        val lng = trip.lastLng
        if (trip.status == TripStatus.ENDED || lat == null || lng == null) {
            return sorted.map { StopEta(it.id, it.seq, it.name, it.lat, it.lng, trip.status == TripStatus.ENDED || it.seq < trip.nextSeq, null) }
        }
        val v = speed(trip.lastSpeed)
        var seconds = 0.0
        var prevLat: Double = lat
        var prevLng: Double = lng
        var first = true
        return sorted.map { s ->
            if (s.seq < trip.nextSeq) {
                StopEta(s.id, s.seq, s.name, s.lat, s.lng, true, null)
            } else {
                if (!first) seconds += DWELL_SECONDS
                seconds += distance(prevLat, prevLng, s.lat, s.lng) * ROAD_FACTOR / v
                prevLat = s.lat
                prevLng = s.lng
                first = false
                StopEta(s.id, s.seq, s.name, s.lat, s.lng, false, max(0, ceil(seconds / 60).toInt()))
            }
        }
    }

    fun stale(trip: ShuttleTripRow, now: Instant): Boolean =
        trip.status == TripStatus.RUNNING && (trip.lastAt ?: trip.startedAt).isBefore(now.minus(STALE_AFTER))
}

@Service
class ShuttleRouteService(
    private val guard: AccessGuard,
    private val routes: ShuttleRoutePort,
    private val trips: ShuttleTripPort,
    private val clock: ClockPort,
) : ShuttleRouteUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, includeInactive: Boolean): List<ShuttleRouteView> {
        guard.requireStaff(actor, institutionId)
        val rows = routes.list(institutionId, includeInactive)
        val stops = routes.stops(rows.map { it.id }).groupBy { it.routeId }
        val running = trips.runningForRoutes(rows.map { it.id }).associateBy { it.routeId }
        return rows.map { view(it, stops[it.id].orEmpty(), running[it.id]?.id) }
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: ShuttleRouteUseCase.SaveRoute): ShuttleRouteView {
        guard.requireManager(actor, institutionId)
        val id = routes.insert(institutionId, validate(command), clock.now())
        return view(routes.find(id)!!, emptyList(), null)
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, routeId: UUID, command: ShuttleRouteUseCase.SaveRoute): ShuttleRouteView {
        guard.requireManager(actor, institutionId)
        owned(routeId, institutionId)
        routes.update(routeId, validate(command), clock.now())
        return reload(routeId)
    }

    @Transactional
    override fun replaceStops(actor: UserId, institutionId: InstitutionId, routeId: UUID, stops: List<ShuttleRouteUseCase.StopInput>): ShuttleRouteView {
        guard.requireManager(actor, institutionId)
        owned(routeId, institutionId)
        if (stops.size > 50) throw InvalidInputException("TOO_MANY_STOPS", "정류장은 50곳까지 등록할 수 있습니다")
        if (trips.runningForRoutes(listOf(routeId)).isNotEmpty()) throw ConflictException("TRIP_RUNNING", "운행 중에는 정류장을 바꿀 수 없습니다")
        val existing = routes.stops(listOf(routeId)).map { it.id }.toSet()
        val cleaned = stops.map {
            if (it.name.isBlank() || it.name.length > 50) throw InvalidInputException("INVALID_STOP", "정류장 이름은 1~50자입니다")
            if (it.lat !in -90.0..90.0 || it.lng !in -180.0..180.0) throw InvalidInputException("INVALID_STOP", "좌표가 올바르지 않습니다: ${it.name}")
            if (it.id != null && it.id !in existing) throw InvalidInputException("INVALID_STOP", "이 노선의 정류장이 아닙니다")
            it.copy(name = it.name.trim())
        }
        routes.replaceStops(routeId, cleaned)
        return reload(routeId)
    }

    @Transactional(readOnly = true)
    override fun riders(actor: UserId, institutionId: InstitutionId, routeId: UUID): List<ShuttleRiderView> {
        guard.requireStaff(actor, institutionId)
        owned(routeId, institutionId)
        return routes.riders(routeId)
    }

    @Transactional
    override fun replaceRiders(actor: UserId, institutionId: InstitutionId, routeId: UUID, riders: List<ShuttleRouteUseCase.RiderInput>): List<ShuttleRiderView> {
        guard.requireManager(actor, institutionId)
        owned(routeId, institutionId)
        val stopIds = routes.stops(listOf(routeId)).map { it.id }.toSet()
        val unique = riders.associateBy { it.studentId }.values.toList()
        unique.forEach { if (it.stopId !in stopIds) throw InvalidInputException("INVALID_STOP", "이 노선의 정류장이 아닙니다") }
        val valid = routes.activeStudentIds(institutionId, unique.map { it.studentId })
        if (valid.size != unique.size) throw InvalidInputException("INVALID_STUDENT", "기관에 재원 중인 원생만 배정할 수 있습니다")
        routes.replaceRiders(routeId, unique)
        return routes.riders(routeId)
    }

    private fun validate(c: ShuttleRouteUseCase.SaveRoute): ShuttleRouteUseCase.SaveRoute {
        val name = c.name.trim()
        if (name.isEmpty() || name.length > 50) throw InvalidInputException("INVALID_NAME", "노선 이름은 1~50자입니다")
        fun opt(v: String?, max: Int) = v?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > max) throw InvalidInputException("TOO_LONG", "입력이 너무 깁니다") }
        return c.copy(name = name, vehicleName = opt(c.vehicleName, 50), plateNo = opt(c.plateNo, 20), driverName = opt(c.driverName, 50), driverPhone = opt(c.driverPhone, 20))
    }

    private fun owned(routeId: UUID, institutionId: InstitutionId): ShuttleRouteRow =
        routes.find(routeId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("셔틀 노선")

    private fun reload(routeId: UUID): ShuttleRouteView =
        view(routes.find(routeId)!!, routes.stops(listOf(routeId)), trips.runningForRoutes(listOf(routeId)).firstOrNull()?.id)

    private fun view(r: ShuttleRouteRow, stops: List<ShuttleStopRow>, runningTripId: UUID?) = ShuttleRouteView(
        r.id, r.name, r.vehicleName, r.plateNo, r.driverName, r.driverPhone, r.active,
        stops.sortedBy { it.seq }.map { ShuttleStopView(it.id, it.seq, it.name, it.lat, it.lng, it.riderCount) },
        stops.sumOf { it.riderCount }, runningTripId,
    )
}

@Service
class ShuttleTripService(
    private val guard: AccessGuard,
    private val routes: ShuttleRoutePort,
    private val trips: ShuttleTripPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : ShuttleTripUseCase {

    @Transactional
    override fun start(actor: UserId, institutionId: InstitutionId, routeId: UUID): TripLiveView {
        guard.requireStaff(actor, institutionId)
        val route = routes.find(routeId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("셔틀 노선")
        if (!route.active) throw ConflictException("ROUTE_INACTIVE", "사용하지 않는 노선입니다")
        if (routes.stops(listOf(routeId)).isEmpty()) throw InvalidInputException("NO_STOPS", "정류장을 먼저 등록해 주세요")
        if (trips.runningForRoutes(listOf(routeId)).isNotEmpty()) throw ConflictException("TRIP_RUNNING", "이 노선은 이미 운행 중입니다")
        val now = clock.now()
        val id = trips.insert(institutionId, routeId, actor, now)
        val guardians = routes.guardiansOnRoute(routeId)
        if (guardians.isNotEmpty()) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "SHUTTLE_DEPARTED", "[${route.name}] 셔틀이 출발했어요", "앱에서 셔틀 위치와 도착 예정 시간을 확인할 수 있어요",
                    mapOf("type" to "shuttle", "tripId" to id.toString()), guardians, now,
                ),
            )
        }
        return live(trips.find(id)!!, now)
    }

    @Transactional
    override fun report(actor: UserId, institutionId: InstitutionId, tripId: UUID, points: List<TripPoint>, speed: Double?): TripLiveView {
        guard.requireStaff(actor, institutionId)
        val trip = owned(tripId, institutionId)
        if (trip.driverId != actor.value) throw ForbiddenException("운행을 시작한 기사만 위치를 보낼 수 있습니다")
        if (trip.status != TripStatus.RUNNING) throw ConflictException("TRIP_ENDED", "이미 끝난 운행입니다")
        if (points.size > 500) throw InvalidInputException("TOO_MANY_POINTS", "한 번에 500개까지 보낼 수 있습니다")
        val now = clock.now()
        val valid = points
            .filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && !(it.lat == 0.0 && it.lng == 0.0) }
            .filter { !it.recordedAt.isAfter(now.plusSeconds(60)) && !it.recordedAt.isBefore(trip.startedAt.minusSeconds(60)) }
            .sortedBy { it.recordedAt }
        if (valid.isEmpty()) return live(trip, now)
        val reportedSpeed = speed?.takeIf { it in 0.0..60.0 }
        trips.addPositions(tripId, valid, reportedSpeed, now)

        val stops = routes.stops(listOf(trip.routeId))
        var next = trip.nextSeq
        valid.forEach { p -> next = ShuttleEta.advance(next, p.lat, p.lng, stops) }
        val last = valid.last()
        // 늦게 도착한 옛 좌표 묶음이 최신 위치를 덮지 않게
        if (trip.lastAt == null || last.recordedAt.isAfter(trip.lastAt)) {
            trips.updateLast(tripId, last.lat, last.lng, reportedSpeed, last.recordedAt, next)
        } else if (next != trip.nextSeq) {
            trips.updateLast(tripId, trip.lastLat!!, trip.lastLng!!, trip.lastSpeed, trip.lastAt, next)
        }
        val updated = trips.find(tripId)!!
        if (updated.lastAt != null && updated.lastAt.isAfter(now.minus(ShuttleEta.STALE_AFTER))) notifyArriving(updated, stops, now)
        return live(updated, now)
    }

    @Transactional
    override fun end(actor: UserId, institutionId: InstitutionId, tripId: UUID): TripLiveView {
        val m = guard.requireStaff(actor, institutionId)
        val trip = owned(tripId, institutionId)
        if (trip.driverId != actor.value && m.role !in setOf(Role.OWNER, Role.ADMIN)) throw ForbiddenException("운행을 시작한 기사나 원장·실장만 끝낼 수 있습니다")
        val now = clock.now()
        if (trip.status == TripStatus.RUNNING) trips.end(tripId, now)
        return live(trips.find(tripId)!!, now)
    }

    @Transactional(readOnly = true)
    override fun live(actor: UserId, institutionId: InstitutionId): List<TripLiveView> {
        guard.requireStaff(actor, institutionId)
        val now = clock.now()
        return trips.running(institutionId).map { live(it, now) }
    }

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, tripId: UUID): TripLiveView {
        guard.requireStaff(actor, institutionId)
        return live(owned(tripId, institutionId), clock.now())
    }

    @Transactional(readOnly = true)
    override fun path(actor: UserId, institutionId: InstitutionId, tripId: UUID): List<TripPoint> {
        guard.requireStaff(actor, institutionId)
        owned(tripId, institutionId)
        return trips.path(tripId, 5000)
    }

    @Transactional(readOnly = true)
    override fun history(actor: UserId, institutionId: InstitutionId, date: LocalDate): List<TripHistoryRow> {
        guard.requireManager(actor, institutionId)
        val zone = clock.zone()
        return trips.history(institutionId, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
    }

    @Transactional
    override fun closeAbandoned(now: Instant): Int = trips.closeIdle(now.minus(Duration.ofHours(3)), now)

    @Transactional
    override fun purgePositions(before: Instant): Int = trips.purgePositions(before)

    private fun owned(tripId: UUID, institutionId: InstitutionId): ShuttleTripRow =
        trips.find(tripId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("셔틀 운행")

    private fun notifyArriving(trip: ShuttleTripRow, stops: List<ShuttleStopRow>, now: Instant) {
        val route = routes.find(trip.routeId) ?: return
        val etas = ShuttleEta.etas(trip, stops)
        etas.filter { !it.passed }.forEach { e ->
            val stop = stops.first { it.id == e.stopId }
            if (stop.riderCount == 0) return@forEach
            val near = ShuttleEta.distance(trip.lastLat!!, trip.lastLng!!, e.lat, e.lng) <= ShuttleEta.ARRIVING_RADIUS_M
            val soon = e.etaMinutes != null && e.etaMinutes <= ShuttleEta.ARRIVING_MINUTES
            if (!(near || soon)) return@forEach
            if (!trips.markAlerted(trip.id, e.stopId, now)) return@forEach
            val guardians = routes.guardiansAtStop(e.stopId)
            if (guardians.isEmpty()) return@forEach
            val minutes = e.etaMinutes?.takeIf { it > 0 }?.let { "약 ${it}분 뒤" } ?: "곧"
            outbox.publish(
                ParentPushRequested(
                    InstitutionId(trip.institutionId), "SHUTTLE_ARRIVING", "[${route.name}] 셔틀이 곧 도착해요",
                    "${e.name} 정류장에 $minutes 도착 예정이에요",
                    mapOf("type" to "shuttle", "tripId" to trip.id.toString()), guardians, now,
                ),
            )
        }
    }

    private fun live(t: ShuttleTripRow, now: Instant): TripLiveView {
        val route = routes.find(t.routeId)!!
        val stops = routes.stops(listOf(t.routeId))
        return TripLiveView(
            t.id, t.routeId, route.name, route.vehicleName, route.plateNo, t.driverName, t.status, t.startedAt, t.endedAt,
            t.lastLat, t.lastLng, t.lastSpeed?.let { it * 3.6 }, t.lastAt, ShuttleEta.stale(t, now), ShuttleEta.etas(t, stops),
        )
    }
}

@Service
class MyShuttleService(
    private val children: GetMyChildrenQuery,
    private val routes: ShuttleRoutePort,
    private val trips: ShuttleTripPort,
    private val clock: ClockPort,
) : MyShuttleQuery {

    @Transactional(readOnly = true)
    override fun myShuttles(parent: UserId, childId: UUID?): List<MyShuttleView> {
        val studentIds = children.resolveFilter(parent, childId)?.map { it.value }
            ?: children.children(parent).flatMap { c -> c.enrollments.map { it.studentId.value } }
        if (studentIds.isEmpty()) return emptyList()
        val assignments = routes.assignmentsFor(studentIds)
        if (assignments.isEmpty()) return emptyList()
        val routeIds = assignments.map { it.routeId }.distinct()
        val routeRows = routeIds.mapNotNull { routes.find(it) }.filter { it.active }.associateBy { it.id }
        val stops = routes.stops(routeRows.keys).groupBy { it.routeId }
        val running = trips.runningForRoutes(routeRows.keys).associateBy { it.routeId }
        val now = clock.now()
        return assignments.mapNotNull { a ->
            val route = routeRows[a.routeId] ?: return@mapNotNull null
            val routeStops = stops[a.routeId].orEmpty()
            val stop = routeStops.firstOrNull { it.id == a.stopId } ?: return@mapNotNull null
            val trip = running[a.routeId]?.let { t ->
                val eta = ShuttleEta.etas(t, routeStops).first { it.stopId == stop.id }
                val before = routeStops.count { it.seq >= t.nextSeq && it.seq < stop.seq }
                MyShuttleTrip(t.id, t.lastLat, t.lastLng, t.lastAt, ShuttleEta.stale(t, now), eta.passed, eta.etaMinutes, before)
            }
            MyShuttleView(
                a.studentId, a.studentName, a.institutionName, route.id, route.name, route.vehicleName, route.plateNo, route.driverPhone,
                stop.name, stop.lat, stop.lng, trip,
            )
        }
    }
}
