package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ConsultMode
import com.ttokttok.application.port.`in`.ConsultSlotView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.ParentConsultUseCase
import com.ttokttok.application.port.`in`.StaffConsultUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.domain.common.InvalidInputException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

private fun ConsultSlotView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName,
    "teacher" to mapOf("id" to teacherId, "name" to teacherName),
    "startsAt" to startsAt, "endsAt" to endsAt, "mode" to mode.name, "modeLabel" to mode.label, "location" to location,
    "status" to status.name, "student" to studentId?.let { mapOf("id" to it, "name" to studentName) },
    "bookedBy" to bookedById?.let { mapOf("id" to it, "name" to bookedByName) }, "topic" to topic, "bookedAt" to bookedAt, "cancelReason" to cancelReason,
)

private fun parseMode(raw: String?): ConsultMode =
    raw?.let { r -> ConsultMode.entries.firstOrNull { it.name == r } ?: throw InvalidInputException("INVALID_MODE", "상담 방식이 올바르지 않습니다") } ?: ConsultMode.IN_PERSON

// ───────── NTC-008 상담 예약: 교직원 ─────────
@RestController
class StaffConsultController(private val consults: StaffConsultUseCase, private val clock: ClockPort) {
    data class OpenRequest(
        @field:NotNull val date: LocalDate,
        @field:NotBlank val startTime: String,
        @field:NotNull val minutes: Int,
        val count: Int?,
        val breakMinutes: Int?,
        val mode: String?,
        @field:Size(max = 100) val location: String?,
        val teacherId: UUID?,
    )
    data class CancelRequest(@field:Size(max = 200) val reason: String?)

    @PostMapping("/api/v1/consult-slots")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: OpenRequest) =
        consults.open(
            jwt.userId(), inst(institutionId),
            StaffConsultUseCase.Open(req.date, req.startTime, req.minutes, req.count ?: 1, req.breakMinutes ?: 0, parseMode(req.mode), req.location, req.teacherId),
        ).map { it.toResponse() }

    @GetMapping("/api/v1/consult-slots")
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?, @RequestParam(required = false) teacherId: UUID?,
    ): List<Map<String, Any?>> {
        val f = from ?: clock.today()
        return consults.list(jwt.userId(), inst(institutionId), f, to ?: f.plusDays(13), teacherId).map { it.toResponse() }
    }

    @PostMapping("/api/v1/consult-slots/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @RequestBody(required = false) req: CancelRequest?) =
        consults.cancel(jwt.userId(), inst(institutionId), id, req?.reason).toResponse()

    @PostMapping("/api/v1/consult-slots/{id}/done")
    fun done(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        consults.complete(jwt.userId(), inst(institutionId), id).toResponse()

    @DeleteMapping("/api/v1/consult-slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        consults.delete(jwt.userId(), inst(institutionId), id)
}

// ───────── NTC-008 상담 예약: 학부모 앱 ─────────
@RestController
class MeConsultController(private val consults: ParentConsultUseCase, private val children: GetMyChildrenQuery) {
    data class BookRequest(@field:NotNull val studentId: UUID, @field:Size(max = 300) val topic: String?)

    private fun filter(jwt: Jwt, childId: UUID?) = children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet()

    @GetMapping("/api/v1/me/consult-slots")
    fun available(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?) =
        consults.available(jwt.userId(), filter(jwt, childId)).map { it.toResponse() }

    @GetMapping("/api/v1/me/consult-bookings")
    fun mine(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?) =
        consults.mine(jwt.userId(), filter(jwt, childId)).map { it.toResponse() }

    @PostMapping("/api/v1/me/consult-slots/{id}/book")
    fun book(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: BookRequest) =
        consults.book(jwt.userId(), id, req.studentId, req.topic).toResponse()

    @PostMapping("/api/v1/me/consult-bookings/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = consults.cancel(jwt.userId(), id).toResponse()
}
