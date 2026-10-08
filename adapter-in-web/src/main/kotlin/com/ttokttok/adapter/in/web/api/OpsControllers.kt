package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.CalendarEvent
import com.ttokttok.application.port.`in`.CounselNoteUseCase
import com.ttokttok.application.port.`in`.CounselNoteView
import com.ttokttok.application.port.`in`.EventCalendarUseCase
import com.ttokttok.application.port.`in`.EventTemplateUseCase
import com.ttokttok.application.port.`in`.EventTemplateView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.HealthView
import com.ttokttok.application.port.`in`.ParentCounselUseCase
import com.ttokttok.application.port.`in`.ParentSlotView
import com.ttokttok.application.port.`in`.ParentTimetableUseCase
import com.ttokttok.application.port.`in`.SlotView
import com.ttokttok.application.port.`in`.StaffAttendanceUseCase
import com.ttokttok.application.port.`in`.StaffAttendanceView
import com.ttokttok.application.port.`in`.StudentHealthUseCase
import com.ttokttok.application.port.`in`.StudentTagUseCase
import com.ttokttok.application.port.`in`.TagView
import com.ttokttok.application.port.`in`.TemplateTarget
import com.ttokttok.application.port.`in`.TimetableUseCase
import com.ttokttok.application.port.`in`.WaitlistEntryView
import com.ttokttok.application.port.`in`.WaitlistStatus
import com.ttokttok.application.port.`in`.WaitlistUseCase
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

// ───────── 응답 변환 ─────────
private fun HealthView.toResponse() = mapOf(
    "studentId" to studentId, "allergies" to allergies, "conditions" to conditions, "notes" to notes,
    "updatedBy" to updatedByName, "updatedAt" to updatedAt,
)

internal fun CounselNoteView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "studentId" to studentId, "studentName" to studentName,
    "author" to mapOf("id" to authorId, "name" to authorName), "occurredOn" to occurredOn, "body" to body, "parentVisible" to parentVisible, "createdAt" to createdAt,
)

private fun TagView.toResponse() = mapOf("id" to id, "name" to name, "color" to color, "studentCount" to studentCount)

private fun SlotView.toResponse() = mapOf(
    "id" to id, "classroomId" to classroomId, "classroomName" to classroomName, "weekday" to weekday,
    "startTime" to startTime.toString(), "endTime" to endTime.toString(), "subject" to subject, "room" to room,
    "teacher" to teacherId?.let { mapOf("id" to it, "name" to teacherName) },
)

private fun WaitlistEntryView.toResponse() = mapOf(
    "id" to id, "classroomId" to classroomId, "classroomName" to classroomName, "studentName" to studentName, "guardianName" to guardianName,
    "phone" to phone, "memo" to memo, "status" to status.name, "contactedAt" to contactedAt, "createdAt" to createdAt, "position" to position,
)

private fun StaffAttendanceView.toResponse() = mapOf(
    "id" to id, "userId" to userId, "userName" to userName, "workDate" to workDate, "checkIn" to checkIn, "checkOut" to checkOut, "workedMinutes" to workedMinutes,
)

private fun EventTemplateView.toResponse() = mapOf(
    "id" to id, "name" to name, "title" to title, "body" to body, "location" to location, "startTime" to startTime.toString(), "durationMinutes" to durationMinutes,
    "targets" to targets.map { mapOf("scope" to it.scope, "id" to it.id) }, "rsvpEnabled" to rsvpEnabled,
    "rsvpDeadlineDaysBefore" to rsvpDeadlineDaysBefore, "reminderHoursBefore" to reminderHoursBefore, "createdBy" to createdByName, "updatedAt" to updatedAt,
)

private fun CalendarEvent.toResponse() = mapOf(
    "id" to id, "title" to title, "startsAt" to startsAt, "endsAt" to endsAt, "location" to location, "status" to status, "rsvpEnabled" to rsvpEnabled,
)

// ───────── STU-009 건강 정보 ─────────
@RestController
class StudentHealthController(private val health: StudentHealthUseCase) {
    data class SaveRequest(
        @field:Size(max = 500) val allergies: String? = null,
        @field:Size(max = 500) val conditions: String? = null,
        @field:Size(max = 500) val notes: String? = null,
    )

    @GetMapping("/api/v1/students/{id}/health")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        health.get(jwt.userId(), inst(institutionId), id).toResponse()

    @PutMapping("/api/v1/students/{id}/health")
    fun save(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: SaveRequest) =
        health.save(jwt.userId(), inst(institutionId), id, StudentHealthUseCase.Save(req.allergies, req.conditions, req.notes)).toResponse()

    /** 교사 앱 경고 배지: 반의 알레르기 있는 원생 */
    @GetMapping("/api/v1/classes/{id}/health-alerts")
    fun alerts(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        health.alerts(jwt.userId(), inst(institutionId), id).map { mapOf("studentId" to it.studentId, "studentName" to it.studentName, "allergies" to it.allergies) }
}

// ───────── STU-010 상담일지 ─────────
@RestController
class CounselNoteController(private val notes: CounselNoteUseCase) {
    data class NoteRequest(
        @field:NotNull val occurredOn: LocalDate,
        @field:NotBlank @field:Size(max = 3000) val body: String,
        val parentVisible: Boolean = false,
    ) {
        fun toCommand() = CounselNoteUseCase.Command(occurredOn, body, parentVisible)
    }

    @GetMapping("/api/v1/students/{id}/counsel-notes")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        notes.list(jwt.userId(), inst(institutionId), id).map { it.toResponse() }

    @PostMapping("/api/v1/students/{id}/counsel-notes")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: NoteRequest) =
        notes.create(jwt.userId(), inst(institutionId), id, req.toCommand()).toResponse()

    @PutMapping("/api/v1/counsel-notes/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: NoteRequest) =
        notes.update(jwt.userId(), inst(institutionId), id, req.toCommand()).toResponse()

    @DeleteMapping("/api/v1/counsel-notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        notes.delete(jwt.userId(), inst(institutionId), id)
    }
}

@RestController
@RequestMapping("/api/v1/me/counsel-notes")
class MeCounselController(private val notes: ParentCounselUseCase, private val children: GetMyChildrenQuery) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?) =
        notes.list(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet()).map { it.toResponse() }
}

// ───────── STU-011 태그 ─────────
@RestController
class StudentTagController(private val tags: StudentTagUseCase) {
    data class CreateRequest(@field:NotBlank @field:Size(max = 30) val name: String, val color: String? = null)
    data class SetTagsRequest(@field:Size(max = 20) val tagIds: List<UUID> = emptyList())

    @GetMapping("/api/v1/student-tags")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        tags.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @PostMapping("/api/v1/student-tags")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateRequest) =
        tags.create(jwt.userId(), inst(institutionId), req.name, req.color).toResponse()

    @DeleteMapping("/api/v1/student-tags/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        tags.delete(jwt.userId(), inst(institutionId), id)
    }

    @GetMapping("/api/v1/student-tags/{id}/students")
    fun students(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        tags.students(jwt.userId(), inst(institutionId), id).map { mapOf("studentId" to it.studentId, "name" to it.name) }

    @GetMapping("/api/v1/students/{id}/tags")
    fun studentTags(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        tags.studentTags(jwt.userId(), inst(institutionId), id).map { it.toResponse() }

    @PutMapping("/api/v1/students/{id}/tags")
    fun setStudentTags(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: SetTagsRequest) =
        tags.setStudentTags(jwt.userId(), inst(institutionId), id, req.tagIds).map { it.toResponse() }
}

// ───────── CLS-003 시간표 ─────────
@RestController
class TimetableController(private val timetable: TimetableUseCase) {
    data class SlotRequest(
        val weekday: Int,
        @field:NotNull val startTime: LocalTime,
        @field:NotNull val endTime: LocalTime,
        @field:Size(max = 50) val subject: String? = null,
        @field:Size(max = 30) val room: String? = null,
        val teacherId: UUID? = null,
    )
    data class ReplaceRequest(@field:Size(max = 35) val slots: List<SlotRequest> = emptyList())

    @GetMapping("/api/v1/timetable")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        timetable.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @PutMapping("/api/v1/classes/{id}/timetable")
    fun replace(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: ReplaceRequest) =
        timetable.replace(
            jwt.userId(), inst(institutionId), id,
            req.slots.map { TimetableUseCase.SlotInput(it.weekday, it.startTime, it.endTime, it.subject, it.room, it.teacherId) },
        ).map { it.toResponse() }
}

@RestController
@RequestMapping("/api/v1/me/timetable")
class MeTimetableController(private val timetable: ParentTimetableUseCase, private val children: GetMyChildrenQuery) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?) =
        timetable.list(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet()).map { p: ParentSlotView ->
            p.slot.toResponse() + mapOf("studentId" to p.studentId, "studentName" to p.studentName, "institutionName" to p.institutionName)
        }
}

// ───────── CLS-004 대기자 ─────────
@RestController
@RequestMapping("/api/v1/waitlist")
class WaitlistController(private val waitlist: WaitlistUseCase) {
    data class AddRequest(
        @field:NotNull val classroomId: UUID,
        @field:NotBlank @field:Size(max = 50) val studentName: String,
        @field:Size(max = 50) val guardianName: String? = null,
        @field:NotBlank val phone: String,
        @field:Size(max = 300) val memo: String? = null,
    )
    data class StatusRequest(@field:NotNull val status: WaitlistStatus)

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) classroomId: UUID?, @RequestParam(required = false) status: WaitlistStatus?,
    ) = waitlist.list(jwt.userId(), inst(institutionId), classroomId, status).map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: AddRequest) =
        waitlist.add(jwt.userId(), inst(institutionId), WaitlistUseCase.Add(req.classroomId, req.studentName, req.guardianName, req.phone, req.memo)).toResponse()

    @PatchMapping("/{id}/status")
    fun setStatus(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: StatusRequest) =
        waitlist.setStatus(jwt.userId(), inst(institutionId), id, req.status).toResponse()

    @PostMapping("/{id}/contacted")
    fun contacted(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        waitlist.markContacted(jwt.userId(), inst(institutionId), id).toResponse()

    @GetMapping("/vacancies")
    fun vacancies(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        waitlist.vacancies(jwt.userId(), inst(institutionId)).map {
            mapOf("classroomId" to it.classroomId, "classroomName" to it.classroomName, "capacity" to it.capacity, "enrolled" to it.enrolled, "waiting" to it.waiting, "vacancy" to it.vacancy)
        }
}

// ───────── STF-004 교사 출퇴근 ─────────
@RestController
@RequestMapping("/api/v1/staff-attendance")
class StaffAttendanceController(private val attendance: StaffAttendanceUseCase) {
    @PostMapping("/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    fun checkIn(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        attendance.checkIn(jwt.userId(), inst(institutionId)).toResponse()

    @PostMapping("/check-out")
    fun checkOut(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        attendance.checkOut(jwt.userId(), inst(institutionId)).toResponse()

    /** 오늘 내 기록 (없으면 null) */
    @GetMapping("/today")
    fun today(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        mapOf("record" to attendance.today(jwt.userId(), inst(institutionId))?.toResponse())

    /** month=YYYY-MM (기본: 이번 달). 원장·실장은 전 직원, 교사는 본인만 */
    @GetMapping("/monthly")
    fun monthly(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) month: String?) =
        attendance.monthly(jwt.userId(), inst(institutionId), parseMonth(month)).map {
            mapOf("userId" to it.userId, "userName" to it.userName, "days" to it.days, "totalMinutes" to it.totalMinutes, "records" to it.records.map { r -> r.toResponse() })
        }
}

private fun parseMonth(month: String?): YearMonth =
    if (month == null) YearMonth.now(java.time.ZoneId.of("Asia/Seoul"))
    else runCatching { YearMonth.parse(month) }.getOrElse { throw InvalidInputException("INVALID_MONTH", "month 는 YYYY-MM 형식입니다") }

// ───────── EVT-002 반복 행사 템플릿 · EVT-006 달력 ─────────
@RestController
class EventTemplateController(private val templates: EventTemplateUseCase, private val calendar: EventCalendarUseCase) {
    data class TargetRequest(@field:NotBlank val scope: String, val id: UUID? = null)
    data class TemplateRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 5000) val body: String? = null,
        @field:Size(max = 200) val location: String? = null,
        @field:NotNull val startTime: LocalTime,
        val durationMinutes: Int = 60,
        @field:Size(min = 1, max = 200) val targets: List<TargetRequest>,
        val rsvpEnabled: Boolean = false,
        val rsvpDeadlineDaysBefore: Int? = null,
        val reminderHoursBefore: Int? = null,
    ) {
        fun toCommand() = EventTemplateUseCase.Command(
            name, title, body, location, startTime, durationMinutes, targets.map { TemplateTarget(it.scope, it.id) }, rsvpEnabled, rsvpDeadlineDaysBefore, reminderHoursBefore,
        )
    }

    /** 날짜를 직접 주거나(dates), 기간 + 요일(1=월…7=일)로 펼친다 */
    data class GenerateRequest(
        @field:Size(max = 31) val dates: List<LocalDate>? = null,
        val from: LocalDate? = null,
        val until: LocalDate? = null,
        val weekdays: List<Int>? = null,
    )

    @GetMapping("/api/v1/event-templates")
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        templates.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    @PostMapping("/api/v1/event-templates")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: TemplateRequest) =
        templates.create(jwt.userId(), inst(institutionId), req.toCommand()).toResponse()

    @PutMapping("/api/v1/event-templates/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: TemplateRequest) =
        templates.update(jwt.userId(), inst(institutionId), id, req.toCommand()).toResponse()

    @DeleteMapping("/api/v1/event-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        templates.delete(jwt.userId(), inst(institutionId), id)
    }

    @PostMapping("/api/v1/event-templates/{id}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    fun generate(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @RequestBody req: GenerateRequest): Map<String, Any> {
        val dates = req.dates ?: expand(req)
        return mapOf("eventIds" to templates.generate(jwt.userId(), inst(institutionId), id, dates))
    }

    private fun expand(req: GenerateRequest): List<LocalDate> {
        val from = req.from ?: throw InvalidInputException("NO_DATES", "dates 또는 from·until·weekdays 가 필요합니다")
        val until = req.until ?: throw InvalidInputException("NO_DATES", "until 이 필요합니다")
        val weekdays = req.weekdays?.toSet() ?: throw InvalidInputException("NO_DATES", "weekdays 가 필요합니다")
        if (until.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, until) > 366) throw InvalidInputException("INVALID_RANGE", "기간은 1년 이내여야 합니다")
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(until) }.filter { it.dayOfWeek.value in weekdays }.toList()
    }

    @GetMapping("/api/v1/events/calendar")
    fun calendar(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) month: String?) =
        calendar.month(jwt.userId(), inst(institutionId), parseMonth(month)).map { it.toResponse() }
}
