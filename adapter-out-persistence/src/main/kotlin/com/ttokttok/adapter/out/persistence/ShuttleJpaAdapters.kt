package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ShuttlePositionEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleRiderEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleRouteEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleStopEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleTripEntity
import com.ttokttok.adapter.out.persistence.repository.EnrollmentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttlePositionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttleRiderJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttleRouteJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttleStopAlertJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttleStopJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ShuttleTripJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.currentClassroomNameOf
import com.ttokttok.application.port.`in`.ShuttleRiderView
import com.ttokttok.application.port.`in`.ShuttleRouteUseCase
import com.ttokttok.application.port.`in`.TripHistoryRow
import com.ttokttok.application.port.`in`.TripPoint
import com.ttokttok.application.port.`in`.TripStatus
import com.ttokttok.application.port.out.RiderAssignment
import com.ttokttok.application.port.out.ShuttleRoutePort
import com.ttokttok.application.port.out.ShuttleRouteRow
import com.ttokttok.application.port.out.ShuttleStopRow
import com.ttokttok.application.port.out.ShuttleTripPort
import com.ttokttok.application.port.out.ShuttleTripRow
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val NO_IDS = listOf(UUID(0, 0))

// ───────── 노선·정류장·탑승 ─────────
@Component
class ShuttleRouteJpaAdapter(
    private val routes: ShuttleRouteJpaRepository,
    private val stops: ShuttleStopJpaRepository,
    private val riders: ShuttleRiderJpaRepository,
    private val students: StudentJpaRepository,
    private val enrollments: EnrollmentJpaRepository,
    private val q: JpaQueries,
) : ShuttleRoutePort {

    private fun ShuttleRouteEntity.toRow() = ShuttleRouteRow(id, institutionId, name, vehicleName, plateNo, driverName, driverPhone, active)

    override fun list(institutionId: InstitutionId, includeInactive: Boolean): List<ShuttleRouteRow> =
        routes.findForInstitution(institutionId.value, includeInactive).map { it.toRow() }

    override fun find(id: UUID): ShuttleRouteRow? = routes.findByIdOrNull(id)?.toRow()

    override fun insert(institutionId: InstitutionId, command: ShuttleRouteUseCase.SaveRoute, now: Instant): UUID {
        val id = Uuid7.next()
        routes.save(
            ShuttleRouteEntity(
                id, institutionId.value, command.name, command.vehicleName, command.plateNo, command.driverName, command.driverPhone,
                command.active, now, now,
            ),
        )
        return id
    }

    override fun update(id: UUID, command: ShuttleRouteUseCase.SaveRoute, now: Instant) {
        val r = routes.findByIdOrNull(id) ?: return
        r.name = command.name
        r.vehicleName = command.vehicleName
        r.plateNo = command.plateNo
        r.driverName = command.driverName
        r.driverPhone = command.driverPhone
        r.active = command.active
        r.updatedAt = now
        routes.save(r)
    }

    override fun stops(routeIds: Collection<UUID>): List<ShuttleStopRow> {
        if (routeIds.isEmpty()) return emptyList()
        return stops.findWithRiderCount(routeIds).map { r ->
            val st = r[0] as ShuttleStopEntity
            ShuttleStopRow(st.id, st.routeId, st.seq, st.name, st.lat, st.lng, (r[1] as Number).toInt())
        }
    }

    override fun replaceStops(routeId: UUID, stops: List<ShuttleRouteUseCase.StopInput>) {
        val kept = stops.mapNotNull { it.id }
        this.stops.deleteOthers(routeId, kept.ifEmpty { NO_IDS })
        stops.forEachIndexed { i, s ->
            if (s.id == null) {
                q.persist(ShuttleStopEntity(Uuid7.next(), routeId, i + 1, s.name, s.lat, s.lng))
            } else {
                this.stops.updateStop(s.id!!, routeId, i + 1, s.name, s.lat, s.lng)
            }
        }
    }

    override fun riders(routeId: UUID): List<ShuttleRiderView> {
        val rows = q.jpqlRows(
            """select r.studentId, s.name, st.id, st.name from ShuttleRiderEntity r
                 join StudentEntity s on s.id = r.studentId
                 join ShuttleStopEntity st on st.id = r.stopId
                where r.routeId = :r
                order by st.seq, s.name""",
            params("r" to routeId),
        )
        val classNames = enrollments.currentClassroomNameOf(rows.map { it[0] as UUID })
        return rows.map { ShuttleRiderView(it[0] as UUID, it[1] as String, classNames[it[0] as UUID], it[2] as UUID, it[3] as String) }
    }

    override fun replaceRiders(routeId: UUID, riders: List<ShuttleRouteUseCase.RiderInput>) {
        this.riders.deleteByRoute(routeId)
        riders.forEach { q.persist(ShuttleRiderEntity(routeId, it.studentId, it.stopId)) }
    }

    override fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID> =
        if (ids.isEmpty()) emptySet() else students.findActiveIds(institutionId.value, ids).toSet()

    override fun assignmentsFor(studentIds: Collection<UUID>): List<RiderAssignment> {
        if (studentIds.isEmpty()) return emptyList()
        return q.jpqlRows(
            """select r.studentId, s.name, i.name, r.routeId, r.stopId
                 from ShuttleRiderEntity r
                 join StudentEntity s on s.id = r.studentId
                 join InstitutionEntity i on i.id = s.institutionId
                where r.studentId in (:ids) and s.status <> 'WITHDRAWN'
                order by s.name""",
            params("ids" to studentIds),
        ).map { RiderAssignment(it[0] as UUID, it[1] as String, it[2] as String, it[3] as UUID, it[4] as UUID) }
    }

    override fun guardiansAtStop(stopId: UUID): List<UserId> = riders.guardiansAtStop(stopId).map(::UserId)

    override fun guardiansOnRoute(routeId: UUID): List<UserId> = riders.guardiansOnRoute(routeId).map(::UserId)
}

// ───────── 운행·위치 ─────────
@Component
class ShuttleTripJpaAdapter(
    private val trips: ShuttleTripJpaRepository,
    private val positions: ShuttlePositionJpaRepository,
    private val alerts: ShuttleStopAlertJpaRepository,
    private val q: JpaQueries,
) : ShuttleTripPort {

    private companion object {
        const val SELECT = "select t, u.name from ShuttleTripEntity t join UserEntity u on u.id = t.driverId"

        fun row(r: Array<Any?>): ShuttleTripRow {
            val t = r[0] as ShuttleTripEntity
            return ShuttleTripRow(
                t.id, t.institutionId, t.routeId, t.driverId, r[1] as String,
                TripStatus.valueOf(t.status), t.startedAt, t.endedAt,
                t.lastLat, t.lastLng, t.lastSpeed, t.lastAt, t.nextSeq,
            )
        }
    }

    override fun insert(institutionId: InstitutionId, routeId: UUID, driver: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        // 노선당 운행 중 1건(부분 유니크 인덱스) 위반을 바로 알 수 있게 즉시 flush
        trips.saveAndFlush(ShuttleTripEntity(id, institutionId.value, routeId, driver.value, startedAt = now))
        return id
    }

    override fun find(id: UUID): ShuttleTripRow? = q.jpqlRows("$SELECT where t.id = :id", params("id" to id)).firstOrNull()?.let(::row)

    override fun running(institutionId: InstitutionId): List<ShuttleTripRow> =
        q.jpqlRows("$SELECT where t.institutionId = :i and t.status = 'RUNNING' order by t.startedAt", params("i" to institutionId.value)).map(::row)

    override fun runningForRoutes(routeIds: Collection<UUID>): List<ShuttleTripRow> {
        if (routeIds.isEmpty()) return emptyList()
        return q.jpqlRows("$SELECT where t.routeId in (:ids) and t.status = 'RUNNING'", params("ids" to routeIds)).map(::row)
    }

    override fun addPositions(tripId: UUID, points: List<TripPoint>, speed: Double?, now: Instant) {
        points.forEachIndexed { i, p ->
            // 기기 속도는 묶음의 마지막 좌표에만 붙인다
            q.persist(ShuttlePositionEntity(null, tripId, p.lat, p.lng, if (i == points.lastIndex) speed else null, recordedAt = p.recordedAt, receivedAt = now))
        }
    }

    override fun updateLast(tripId: UUID, lat: Double, lng: Double, speed: Double?, at: Instant, nextSeq: Int) {
        val t = trips.findByIdOrNull(tripId) ?: return
        t.lastLat = lat
        t.lastLng = lng
        t.lastSpeed = speed
        t.lastAt = at
        t.nextSeq = nextSeq
        trips.save(t)
    }

    override fun end(tripId: UUID, now: Instant) {
        trips.end(tripId, now)
    }

    override fun path(tripId: UUID, limit: Int): List<TripPoint> =
        positions.findByTripIdOrderByRecordedAt(tripId, PageRequest.of(0, limit)).map { TripPoint(it.lat, it.lng, it.recordedAt) }

    override fun history(institutionId: InstitutionId, from: Instant, to: Instant): List<TripHistoryRow> =
        q.jpqlRows(
            """select t, r.name, u.name, (select count(p) from ShuttlePositionEntity p where p.tripId = t.id)
                 from ShuttleTripEntity t
                 join ShuttleRouteEntity r on r.id = t.routeId
                 join UserEntity u on u.id = t.driverId
                where t.institutionId = :i and t.startedAt >= :from and t.startedAt < :to
                order by t.startedAt desc""",
            params("i" to institutionId.value, "from" to from, "to" to to),
        ).map { r ->
            val t = r[0] as ShuttleTripEntity
            TripHistoryRow(t.id, r[1] as String, r[2] as String, TripStatus.valueOf(t.status), t.startedAt, t.endedAt, (r[3] as Number).toInt())
        }

    override fun markAlerted(tripId: UUID, stopId: UUID, now: Instant): Boolean = alerts.insertIfAbsent(tripId, stopId, now) > 0

    override fun closeIdle(lastBefore: Instant, now: Instant): Int = trips.closeIdle(lastBefore, now)

    override fun purgePositions(before: Instant): Int = positions.purgeBefore(before)
}
