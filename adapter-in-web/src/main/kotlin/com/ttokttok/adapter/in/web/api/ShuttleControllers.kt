package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.MyShuttleQuery
import com.ttokttok.application.port.`in`.MyShuttleView
import com.ttokttok.application.port.`in`.ShuttleRiderView
import com.ttokttok.application.port.`in`.ShuttleRouteUseCase
import com.ttokttok.application.port.`in`.ShuttleRouteView
import com.ttokttok.application.port.`in`.ShuttleTripUseCase
import com.ttokttok.application.port.`in`.TripLiveView
import com.ttokttok.application.port.`in`.TripPoint
import com.ttokttok.application.port.out.ClockPort
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ───────── 응답 변환 ─────────
private fun ShuttleRouteView.toResponse() = mapOf(
    "id" to id, "name" to name, "vehicleName" to vehicleName, "plateNo" to plateNo, "driverName" to driverName, "driverPhone" to driverPhone,
    "active" to active, "riderCount" to riderCount, "runningTripId" to runningTripId,
    "stops" to stops.map { mapOf("id" to it.id, "seq" to it.seq, "name" to it.name, "lat" to it.lat, "lng" to it.lng, "riderCount" to it.riderCount) },
)

private fun ShuttleRiderView.toResponse() = mapOf(
    "studentId" to studentId, "studentName" to studentName, "classroomName" to classroomName, "stopId" to stopId, "stopName" to stopName,
)

private fun TripLiveView.toResponse() = mapOf(
    "id" to id, "routeId" to routeId, "routeName" to routeName, "vehicleName" to vehicleName, "plateNo" to plateNo, "driverName" to driverName,
    "status" to status.name, "startedAt" to startedAt, "endedAt" to endedAt,
    "position" to if (lat != null && lng != null) mapOf("lat" to lat, "lng" to lng, "speedKmh" to speedKmh, "at" to lastAt) else null,
    "stale" to stale,
    "stops" to stops.map { mapOf("id" to it.stopId, "seq" to it.seq, "name" to it.name, "lat" to it.lat, "lng" to it.lng, "passed" to it.passed, "etaMinutes" to it.etaMinutes) },
)

private fun MyShuttleView.toResponse() = mapOf(
    "studentId" to studentId, "studentName" to studentName, "institutionName" to institutionName,
    "route" to mapOf("id" to routeId, "name" to routeName, "vehicleName" to vehicleName, "plateNo" to plateNo, "driverPhone" to driverPhone),
    "stop" to mapOf("name" to stopName, "lat" to stopLat, "lng" to stopLng),
    "trip" to trip?.let { t ->
        mapOf(
            "id" to t.id, "position" to if (t.lat != null && t.lng != null) mapOf("lat" to t.lat, "lng" to t.lng, "at" to t.lastAt) else null,
            "stale" to t.stale, "passed" to t.passed, "etaMinutes" to t.etaMinutes, "stopsBefore" to t.stopsBefore,
        )
    },
)

// ───────── ATT-009 노선 관리 ─────────
@RestController
class ShuttleRouteController(private val routes: ShuttleRouteUseCase) {
    data class RouteRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:Size(max = 50) val vehicleName: String?,
        @field:Size(max = 20) val plateNo: String?,
        @field:Size(max = 50) val driverName: String?,
        @field:Size(max = 20) val driverPhone: String?,
        val active: Boolean?,
    ) {
        fun toCommand() = ShuttleRouteUseCase.SaveRoute(name, vehicleName, plateNo, driverName, driverPhone, active ?: true)
    }
    data class StopRequest(val id: UUID?, @field:NotBlank val name: String, @field:NotNull val lat: Double, @field:NotNull val lng: Double)
    data class StopsRequest(@field:Valid val stops: List<StopRequest>)
    data class RiderRequest(@field:NotNull val studentId: UUID, @field:NotNull val stopId: UUID)
    data class RidersRequest(@field:Valid val riders: List<RiderRequest>)

    @GetMapping("/api/v1/shuttle/routes")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(defaultValue = "false") all: Boolean) =
        routes.list(jwt.userId(), inst(institutionId), all).map { it.toResponse() }

    @PostMapping("/api/v1/shuttle/routes")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: RouteRequest) =
        routes.create(jwt.userId(), inst(institutionId), req.toCommand()).toResponse()

    @PutMapping("/api/v1/shuttle/routes/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: RouteRequest) =
        routes.update(jwt.userId(), inst(institutionId), id, req.toCommand()).toResponse()

    @PutMapping("/api/v1/shuttle/routes/{id}/stops")
    fun stops(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: StopsRequest) =
        routes.replaceStops(jwt.userId(), inst(institutionId), id, req.stops.map { ShuttleRouteUseCase.StopInput(it.id, it.name, it.lat, it.lng) }).toResponse()

    @GetMapping("/api/v1/shuttle/routes/{id}/riders")
    fun riders(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        routes.riders(jwt.userId(), inst(institutionId), id).map { it.toResponse() }

    @PutMapping("/api/v1/shuttle/routes/{id}/riders")
    fun setRiders(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: RidersRequest) =
        routes.replaceRiders(jwt.userId(), inst(institutionId), id, req.riders.map { ShuttleRouteUseCase.RiderInput(it.studentId, it.stopId) }).map { it.toResponse() }
}

// ───────── ATT-009 운행·관제 ─────────
@RestController
class ShuttleTripController(private val trips: ShuttleTripUseCase, private val clock: ClockPort) {
    data class PointRequest(@field:NotNull val lat: Double, @field:NotNull val lng: Double, @field:NotNull val recordedAt: Instant)
    data class ReportRequest(@field:Valid @field:Size(max = 500) val points: List<PointRequest>, val speed: Double?)

    @PostMapping("/api/v1/shuttle/routes/{id}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        trips.start(jwt.userId(), inst(institutionId), id).toResponse()

    @PostMapping("/api/v1/shuttle/trips/{id}/positions")
    fun report(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: ReportRequest) =
        trips.report(jwt.userId(), inst(institutionId), id, req.points.map { TripPoint(it.lat, it.lng, it.recordedAt) }, req.speed).toResponse()

    @PostMapping("/api/v1/shuttle/trips/{id}/end")
    fun end(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        trips.end(jwt.userId(), inst(institutionId), id).toResponse()

    @GetMapping("/api/v1/shuttle/live")
    fun live(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        trips.live(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @GetMapping("/api/v1/shuttle/trips/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        trips.get(jwt.userId(), inst(institutionId), id).toResponse()

    @GetMapping("/api/v1/shuttle/trips/{id}/path")
    fun path(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        trips.path(jwt.userId(), inst(institutionId), id).map { mapOf("lat" to it.lat, "lng" to it.lng, "at" to it.recordedAt) }

    @GetMapping("/api/v1/shuttle/trips")
    fun history(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) date: LocalDate?) =
        trips.history(jwt.userId(), inst(institutionId), date ?: clock.today()).map {
            mapOf("id" to it.id, "routeName" to it.routeName, "driverName" to it.driverName, "status" to it.status.name,
                "startedAt" to it.startedAt, "endedAt" to it.endedAt, "pointCount" to it.pointCount)
        }
}

// ───────── 학부모 앱 ─────────
@RestController
class MeShuttleController(private val shuttles: MyShuttleQuery) {
    @GetMapping("/api/v1/me/shuttle")
    fun mine(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?) =
        shuttles.myShuttles(jwt.userId(), childId).map { it.toResponse() }
}
