package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AttendanceCounts
import com.ttokttok.application.port.`in`.BulkAttendanceUseCase
import com.ttokttok.application.port.`in`.ChangeAttendanceStatusUseCase
import com.ttokttok.application.port.`in`.DashboardQuery
import com.ttokttok.application.port.`in`.DashboardStudentItem
import com.ttokttok.application.port.`in`.ExportAttendanceUseCase
import com.ttokttok.application.port.`in`.GetDailyReportQuery
import com.ttokttok.application.port.`in`.GetMonthlyAttendanceQuery
import com.ttokttok.application.port.`in`.UpdateAbsenceReasonUseCase
import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceSource
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

internal fun parseMonth(raw: String): YearMonth = try {
    YearMonth.parse(raw)
} catch (e: DateTimeParseException) {
    throw InvalidInputException("INVALID_MONTH", "month는 YYYY-MM 형식입니다")
}

internal fun AttendanceCounts.toMap() = mapOf(
    "total" to total, "scheduled" to scheduled, "present" to present, "checkedOut" to checkedOut,
    "absent" to absent, "late" to late, "earlyLeave" to earlyLeave, "attendanceRate" to attendanceRate,
)

/** ATT-001~004 + 오프라인 일괄. 원터치 등·하원은 AttendanceController */
@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceManagementController(
    private val change: ChangeAttendanceStatusUseCase,
    private val absenceReason: UpdateAbsenceReasonUseCase,
    private val bulk: BulkAttendanceUseCase,
    private val dailyReport: GetDailyReportQuery,
    private val monthly: GetMonthlyAttendanceQuery,
    private val export: ExportAttendanceUseCase,
) {
    data class ChangeStatusRequest(
        val status: AttendanceStatus,
        @field:NotBlank @field:Size(max = 500) val reason: String,
        val isLate: Boolean? = null,
        val isEarlyLeave: Boolean? = null,
        /** 교사앱은 TEACHER_APP, 관리자 웹은 생략(ADMIN_WEB) */
        val source: AttendanceSource? = null,
    )

    data class AbsenceReasonRequest(@field:Size(max = 500) val reason: String?)

    data class BulkItem(
        val type: AttendanceEventType,
        val studentId: UUID,
        val classroomId: UUID,
        val destinationId: UUID? = null,
        val clientAt: Instant? = null,
        @field:Size(max = 100) val idempotencyKey: String? = null,
    )

    data class BulkRequest(@field:Valid @field:Size(min = 1, max = BulkAttendanceUseCase.MAX_ITEMS) val items: List<BulkItem>)

    data class ExportRequest(val classId: UUID, val month: String, @field:Size(max = 64) val password: String? = null)

    /** ATT-002 수동 변경 — 사유 필수 */
    @PatchMapping("/{dayId}/status")
    fun changeStatus(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable dayId: UUID, @Valid @RequestBody req: ChangeStatusRequest,
    ) = change.change(
        ChangeAttendanceStatusUseCase.Command(
            actor = jwt.userId(), institutionId = inst(institutionId), dayId = AttendanceDayId(dayId),
            status = req.status, reason = req.reason, isLate = req.isLate, isEarlyLeave = req.isEarlyLeave,
            source = when (req.source) {
                null, AttendanceSource.ADMIN_WEB -> AttendanceSource.ADMIN_WEB
                AttendanceSource.TEACHER_APP -> AttendanceSource.TEACHER_APP
                AttendanceSource.SYSTEM -> throw InvalidInputException("INVALID_SOURCE", "SYSTEM은 지정할 수 없습니다")
            },
        ),
    ).toResponse()

    /** ATT-003 결석 사유 등록·수정 (빈 값이면 삭제) */
    @PatchMapping("/{dayId}/absence-reason")
    fun updateAbsenceReason(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable dayId: UUID, @Valid @RequestBody req: AbsenceReasonRequest,
    ) = absenceReason.update(jwt.userId(), inst(institutionId), AttendanceDayId(dayId), req.reason).toResponse()

    /** ATT-005~006 오프라인 큐 일괄 전송 (최대 100건, 항목별 결과) */
    @PostMapping("/bulk")
    fun bulk(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @Valid @RequestBody req: BulkRequest,
    ) = bulk.submit(
        jwt.userId(), inst(institutionId),
        req.items.map {
            BulkAttendanceUseCase.Item(
                it.type, StudentId(it.studentId), ClassroomId(it.classroomId), it.destinationId?.let(::DestinationId), it.clientAt, it.idempotencyKey,
            )
        },
    ).map {
        mapOf("index" to it.index, "ok" to it.ok, "attendance" to it.attendance?.toResponse(), "errorCode" to it.errorCode, "message" to it.message)
    }

    /** ATT-001 데일리 리포트 — classId 없으면 볼 수 있는 전체 반 */
    @GetMapping("/report")
    fun report(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(required = false) classId: UUID?,
    ) = dailyReport.report(jwt.userId(), inst(institutionId), date, classId?.let(::ClassroomId)).let { r ->
        mapOf(
            "date" to r.date,
            "classes" to r.classes.map { c ->
                mapOf(
                    "classroomId" to c.classroomId.value, "classroomName" to c.classroomName,
                    "startTime" to c.startTime, "endTime" to c.endTime, "heldToday" to c.heldToday,
                    "counts" to c.counts.toMap(),
                    "rows" to c.rows.map { it.toResponse() },
                )
            },
        )
    }

    /** ATT-003 월간 출석부 (화면용 JSON) */
    @GetMapping("/monthly")
    fun monthly(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam month: String, @RequestParam classId: UUID,
    ) = monthly.get(jwt.userId(), inst(institutionId), ClassroomId(classId), parseMonth(month)).let { v ->
        mapOf(
            "classroomId" to v.classroomId.value, "classroomName" to v.classroomName, "month" to v.month.toString(),
            "classDays" to v.classDays,
            "rows" to v.rows.map { r ->
                mapOf(
                    "studentId" to r.studentId.value, "studentName" to r.studentName,
                    "present" to r.presentDays, "partial" to r.partialDays, "absent" to r.absentDays, "remarks" to r.remarks,
                    "cells" to r.cells.entries.sortedBy { it.key }.map { (date, cell) ->
                        mapOf(
                            "date" to date, "dayId" to cell.dayId.value, "mark" to cell.mark.symbol,
                            "isLate" to cell.isLate, "isEarlyLeave" to cell.isEarlyLeave, "absenceReason" to cell.absenceReason,
                        )
                    },
                )
            },
        )
    }

    /**
     * ATT-004 출석부 엑셀. 비밀번호를 URL에 싣지 않으려고 POST 본문으로 받는다.
     * 기관당 수십 명 규모라 동기 생성으로 충분 (스펙의 비동기 job은 대형 기관 대응 시 전환)
     */
    @PostMapping("/export")
    fun export(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @Valid @RequestBody req: ExportRequest,
    ) = export.export(jwt.userId(), inst(institutionId), ClassroomId(req.classId), parseMonth(req.month), req.password)
        .let { xlsx(it.bytes, it.filename) }
}

/** DASH-001·002·005 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(private val dashboard: DashboardQuery) {

    @GetMapping("/today")
    fun today(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        dashboard.today(jwt.userId(), inst(institutionId)).let { d ->
            mapOf(
                "date" to d.date, "asOf" to d.asOf,
                "kpi" to d.counts.toMap() + mapOf(
                    "excusedAbsent" to d.excusedAbsent, "notArrived" to d.notArrivedCount, "noticeReadRate" to d.noticeReadRate,
                ),
                "widgets" to mapOf(
                    "notArrived" to d.notArrived.map { it.toMap() },
                    "late" to d.late.map { it.toMap() },
                    "absent" to d.absent.map { it.toMap() },
                ),
            )
        }

    /** 실시간 타임라인 — 최신 20건. 이후 갱신은 STOMP /topic/inst.{id} 수신 시 재조회 */
    @GetMapping("/timeline")
    fun timeline(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = dashboard.timeline(jwt.userId(), inst(institutionId), limit).map {
        mapOf(
            "eventId" to it.eventId.value, "studentId" to it.studentId.value, "studentName" to it.studentName,
            "classroomId" to it.classroomId?.value, "classroomName" to it.classroomName,
            "type" to it.type.name, "fromStatus" to it.fromStatus.name, "toStatus" to it.toStatus.name, "isLate" to it.isLate,
            "destinationName" to it.destinationName, "reason" to it.reason, "actorName" to it.actorName,
            "source" to it.source.name, "occurredAt" to it.occurredAt,
        )
    }

    private fun DashboardStudentItem.toMap() = mapOf(
        "dayId" to dayId.value, "studentId" to studentId.value, "studentName" to studentName,
        "classroomId" to classroomId.value, "classroomName" to classroomName, "status" to status.name,
        "classStartTime" to classStartTime, "checkInAt" to checkInAt, "minutesLate" to minutesLate, "absenceReason" to absenceReason,
    )
}
