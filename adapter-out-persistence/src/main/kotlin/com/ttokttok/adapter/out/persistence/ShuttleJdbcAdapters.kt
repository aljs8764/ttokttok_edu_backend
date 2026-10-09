package com.ttokttok.adapter.out.persistence

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.sid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.sts(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.sdouble(col: String): Double? = getDouble(col).takeUnless { wasNull() }
private fun stime(i: Instant?) = i?.let { Timestamp.from(it) }
private fun sp(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }
private val NO_IDS = setOf(UUID(0, 0))

// ───────── 노선·정류장·탑승 ─────────
@Component
class ShuttleRouteJdbcAdapter(jdbc: JdbcTemplate) : ShuttleRoutePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = "select id, institution_id, name, vehicle_name, plate_no, driver_name, driver_phone, active from shuttle_route"
        val MAPPER = RowMapper<ShuttleRouteRow> { rs, _ ->
            ShuttleRouteRow(
                rs.sid("id"), rs.sid("institution_id"), rs.getString("name"), rs.getString("vehicle_name"), rs.getString("plate_no"),
                rs.getString("driver_name"), rs.getString("driver_phone"), rs.getBoolean("active"),
            )
        }
        val STOP_MAPPER = RowMapper<ShuttleStopRow> { rs, _ ->
            ShuttleStopRow(rs.sid("id"), rs.sid("route_id"), rs.getInt("seq"), rs.getString("name"), rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("rider_count"))
        }
    }

    override fun list(institutionId: InstitutionId, includeInactive: Boolean): List<ShuttleRouteRow> =
        named.query("$SELECT where institution_id = :i and (:all or active) order by active desc, name", sp("i" to institutionId.value, "all" to includeInactive), MAPPER)

    override fun find(id: UUID): ShuttleRouteRow? = named.query("$SELECT where id = :id", sp("id" to id), MAPPER).firstOrNull()

    override fun insert(institutionId: InstitutionId, command: ShuttleRouteUseCase.SaveRoute, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into shuttle_route (id, institution_id, name, vehicle_name, plate_no, driver_name, driver_phone, active, created_at, updated_at)
               values (:id, :i, :name, :vehicle, :plate, :driver, :phone, :active, :now, :now)""",
            sp("id" to id, "i" to institutionId.value, "name" to command.name, "vehicle" to command.vehicleName, "plate" to command.plateNo,
                "driver" to command.driverName, "phone" to command.driverPhone, "active" to command.active, "now" to stime(now)),
        )
        return id
    }

    override fun update(id: UUID, command: ShuttleRouteUseCase.SaveRoute, now: Instant) {
        named.update(
            """update shuttle_route set name = :name, vehicle_name = :vehicle, plate_no = :plate, driver_name = :driver, driver_phone = :phone,
                      active = :active, updated_at = :now where id = :id""",
            sp("id" to id, "name" to command.name, "vehicle" to command.vehicleName, "plate" to command.plateNo,
                "driver" to command.driverName, "phone" to command.driverPhone, "active" to command.active, "now" to stime(now)),
        )
    }

    override fun stops(routeIds: Collection<UUID>): List<ShuttleStopRow> {
        if (routeIds.isEmpty()) return emptyList()
        return named.query(
            """select st.id, st.route_id, st.seq, st.name, st.lat, st.lng,
                      (select count(*) from shuttle_rider r where r.stop_id = st.id) as rider_count
                 from shuttle_stop st where st.route_id in (:ids) order by st.route_id, st.seq""",
            sp("ids" to routeIds), STOP_MAPPER,
        )
    }

    override fun replaceStops(routeId: UUID, stops: List<ShuttleRouteUseCase.StopInput>) {
        val kept = stops.mapNotNull { it.id }
        named.update(
            "delete from shuttle_stop where route_id = :r and id not in (:ids)",
            sp("r" to routeId, "ids" to kept.ifEmpty { NO_IDS.toList() }),
        )
        stops.forEachIndexed { i, s ->
            if (s.id == null) {
                named.update(
                    "insert into shuttle_stop (id, route_id, seq, name, lat, lng) values (:id, :r, :seq, :name, :lat, :lng)",
                    sp("id" to Uuid7.next(), "r" to routeId, "seq" to i + 1, "name" to s.name, "lat" to s.lat, "lng" to s.lng),
                )
            } else {
                named.update(
                    "update shuttle_stop set seq = :seq, name = :name, lat = :lat, lng = :lng where id = :id and route_id = :r",
                    sp("id" to s.id, "r" to routeId, "seq" to i + 1, "name" to s.name, "lat" to s.lat, "lng" to s.lng),
                )
            }
        }
    }

    override fun riders(routeId: UUID): List<ShuttleRiderView> =
        named.query(
            """select r.student_id, s.name as student_name, st.id as stop_id, st.name as stop_name,
                      (select c.name from enrollment e join classroom c on c.id = e.classroom_id
                        where e.student_id = s.id and e.to_date is null order by e.from_date desc limit 1) as classroom_name
                 from shuttle_rider r
                 join student s on s.id = r.student_id
                 join shuttle_stop st on st.id = r.stop_id
                where r.route_id = :r
                order by st.seq, s.name""",
            sp("r" to routeId),
            RowMapper { rs, _ -> ShuttleRiderView(rs.sid("student_id"), rs.getString("student_name"), rs.getString("classroom_name"), rs.sid("stop_id"), rs.getString("stop_name")) },
        )

    override fun replaceRiders(routeId: UUID, riders: List<ShuttleRouteUseCase.RiderInput>) {
        named.update("delete from shuttle_rider where route_id = :r", sp("r" to routeId))
        if (riders.isEmpty()) return
        named.batchUpdate(
            "insert into shuttle_rider (route_id, student_id, stop_id) values (:r, :s, :st)",
            riders.map { sp("r" to routeId, "s" to it.studentId, "st" to it.stopId) }.toTypedArray(),
        )
    }

    override fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return named.query(
            "select id from student where institution_id = :inst and status <> 'WITHDRAWN' and id in (:ids)",
            sp("inst" to institutionId.value, "ids" to ids), RowMapper { rs, _ -> rs.sid("id") },
        ).toSet()
    }

    override fun assignmentsFor(studentIds: Collection<UUID>): List<RiderAssignment> {
        if (studentIds.isEmpty()) return emptyList()
        return named.query(
            """select r.student_id, s.name as student_name, i.name as institution_name, r.route_id, r.stop_id
                 from shuttle_rider r
                 join student s on s.id = r.student_id
                 join institution i on i.id = s.institution_id
                where r.student_id in (:ids) and s.status <> 'WITHDRAWN'
                order by s.name""",
            sp("ids" to studentIds),
            RowMapper { rs, _ -> RiderAssignment(rs.sid("student_id"), rs.getString("student_name"), rs.getString("institution_name"), rs.sid("route_id"), rs.sid("stop_id")) },
        )
    }

    override fun guardiansAtStop(stopId: UUID): List<UserId> =
        named.query(
            """select distinct g.user_id from shuttle_rider r join guardian g on g.student_id = r.student_id
                where r.stop_id = :s and g.user_id is not null and g.link_status = 'LINKED'""",
            sp("s" to stopId), RowMapper { rs, _ -> UserId(rs.sid("user_id")) },
        )

    override fun guardiansOnRoute(routeId: UUID): List<UserId> =
        named.query(
            """select distinct g.user_id from shuttle_rider r join guardian g on g.student_id = r.student_id
                where r.route_id = :r and g.user_id is not null and g.link_status = 'LINKED'""",
            sp("r" to routeId), RowMapper { rs, _ -> UserId(rs.sid("user_id")) },
        )
}

// ───────── 운행·위치 ─────────
@Component
class ShuttleTripJdbcAdapter(jdbc: JdbcTemplate) : ShuttleTripPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select t.id, t.institution_id, t.route_id, t.driver_id, u.name as driver_name, t.status, t.started_at, t.ended_at,
                   t.last_lat, t.last_lng, t.last_speed, t.last_at, t.next_seq
              from shuttle_trip t join app_user u on u.id = t.driver_id
        """
        val MAPPER = RowMapper<ShuttleTripRow> { rs, _ ->
            ShuttleTripRow(
                rs.sid("id"), rs.sid("institution_id"), rs.sid("route_id"), rs.sid("driver_id"), rs.getString("driver_name"),
                TripStatus.valueOf(rs.getString("status")), rs.sts("started_at")!!, rs.sts("ended_at"),
                rs.sdouble("last_lat"), rs.sdouble("last_lng"), rs.sdouble("last_speed"), rs.sts("last_at"), rs.getInt("next_seq"),
            )
        }
        val POINT_MAPPER = RowMapper<TripPoint> { rs, _ -> TripPoint(rs.getDouble("lat"), rs.getDouble("lng"), rs.sts("recorded_at")!!) }
    }

    override fun insert(institutionId: InstitutionId, routeId: UUID, driver: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            "insert into shuttle_trip (id, institution_id, route_id, driver_id, status, started_at) values (:id, :i, :r, :d, 'RUNNING', :now)",
            sp("id" to id, "i" to institutionId.value, "r" to routeId, "d" to driver.value, "now" to stime(now)),
        )
        return id
    }

    override fun find(id: UUID): ShuttleTripRow? = named.query("$SELECT where t.id = :id", sp("id" to id), MAPPER).firstOrNull()

    override fun running(institutionId: InstitutionId): List<ShuttleTripRow> =
        named.query("$SELECT where t.institution_id = :i and t.status = 'RUNNING' order by t.started_at", sp("i" to institutionId.value), MAPPER)

    override fun runningForRoutes(routeIds: Collection<UUID>): List<ShuttleTripRow> {
        if (routeIds.isEmpty()) return emptyList()
        return named.query("$SELECT where t.route_id in (:ids) and t.status = 'RUNNING'", sp("ids" to routeIds), MAPPER)
    }

    override fun addPositions(tripId: UUID, points: List<TripPoint>, speed: Double?, now: Instant) {
        if (points.isEmpty()) return
        named.batchUpdate(
            "insert into shuttle_position (trip_id, lat, lng, speed, recorded_at, received_at) values (:t, :lat, :lng, :speed, :at, :now)",
            points.mapIndexed { i, p ->
                // 기기 속도는 묶음의 마지막 좌표에만 붙인다
                sp("t" to tripId, "lat" to p.lat, "lng" to p.lng, "speed" to if (i == points.lastIndex) speed else null, "at" to stime(p.recordedAt), "now" to stime(now))
            }.toTypedArray(),
        )
    }

    override fun updateLast(tripId: UUID, lat: Double, lng: Double, speed: Double?, at: Instant, nextSeq: Int) {
        named.update(
            "update shuttle_trip set last_lat = :lat, last_lng = :lng, last_speed = :speed, last_at = :at, next_seq = :next where id = :id",
            sp("id" to tripId, "lat" to lat, "lng" to lng, "speed" to speed, "at" to stime(at), "next" to nextSeq),
        )
    }

    override fun end(tripId: UUID, now: Instant) {
        named.update("update shuttle_trip set status = 'ENDED', ended_at = :now where id = :id and status = 'RUNNING'", sp("id" to tripId, "now" to stime(now)))
    }

    override fun path(tripId: UUID, limit: Int): List<TripPoint> =
        named.query(
            "select lat, lng, recorded_at from shuttle_position where trip_id = :t order by recorded_at limit :l",
            sp("t" to tripId, "l" to limit), POINT_MAPPER,
        )

    override fun history(institutionId: InstitutionId, from: Instant, to: Instant): List<TripHistoryRow> =
        named.query(
            """select t.id, r.name as route_name, u.name as driver_name, t.status, t.started_at, t.ended_at,
                      (select count(*) from shuttle_position p where p.trip_id = t.id) as point_count
                 from shuttle_trip t
                 join shuttle_route r on r.id = t.route_id
                 join app_user u on u.id = t.driver_id
                where t.institution_id = :i and t.started_at >= :from and t.started_at < :to
                order by t.started_at desc""",
            sp("i" to institutionId.value, "from" to stime(from), "to" to stime(to)),
            RowMapper { rs, _ ->
                TripHistoryRow(
                    rs.sid("id"), rs.getString("route_name"), rs.getString("driver_name"), TripStatus.valueOf(rs.getString("status")),
                    rs.sts("started_at")!!, rs.sts("ended_at"), rs.getInt("point_count"),
                )
            },
        )

    override fun markAlerted(tripId: UUID, stopId: UUID, now: Instant): Boolean =
        named.update(
            "insert into shuttle_stop_alert (trip_id, stop_id, sent_at) values (:t, :s, :now) on conflict do nothing",
            sp("t" to tripId, "s" to stopId, "now" to stime(now)),
        ) > 0

    override fun closeIdle(lastBefore: Instant, now: Instant): Int =
        named.update(
            "update shuttle_trip set status = 'ENDED', ended_at = :now where status = 'RUNNING' and coalesce(last_at, started_at) < :before",
            sp("before" to stime(lastBefore), "now" to stime(now)),
        )

    override fun purgePositions(before: Instant): Int =
        named.update("delete from shuttle_position where received_at < :before", sp("before" to stime(before)))
}
