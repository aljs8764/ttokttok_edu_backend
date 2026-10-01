package com.ttokttok.application.port.`in`

import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceSource
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.attendance.MonthlyRow
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.AttendanceEventId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** 출결 수동 변경 (ATT-002). 사유 필수, 이벤트 로그 + 감사 로그 */
interface ChangeAttendanceStatusUseCase {
    fun change(command: Command): AttendanceView
    data class Command(
        val actor: UserId,
        val institutionId: InstitutionId,
        val dayId: AttendanceDayId,
        val status: AttendanceStatus,
        val reason: String,
        val isLate: Boolean? = null,
        val isEarlyLeave: Boolean? = null,
        val source: AttendanceSource = AttendanceSource.ADMIN_WEB,
    )
}

/** 결석 사유 등록·수정 (ATT-003) */
interface UpdateAbsenceReasonUseCase {
    fun update(actor: UserId, institutionId: InstitutionId, dayId: AttendanceDayId, reason: String?): AttendanceView
}

/** 교사앱 오프라인 큐 일괄 전송 (ATT-005~006). 항목별로 독립 처리 — 하나가 실패해도 나머지는 반영 */
interface BulkAttendanceUseCase {
    fun submit(actor: UserId, institutionId: InstitutionId, items: List<Item>): List<Result>

    data class Item(
        val type: AttendanceEventType,
        val studentId: StudentId,
        val classroomId: ClassroomId,
        val destinationId: DestinationId?,
        val clientAt: Instant?,
        val idempotencyKey: String?,
    )

    data class Result(val index: Int, val ok: Boolean, val attendance: AttendanceView?, val errorCode: String?, val message: String?)

    companion object { const val MAX_ITEMS = 100 }
}

/** 데일리 리포트 (ATT-001): 날짜별 반 단위 요약 + 원생 행 */
interface GetDailyReportQuery {
    fun report(actor: UserId, institutionId: InstitutionId, date: LocalDate, classroomId: ClassroomId?): DailyReport
}

data class DailyReport(val date: LocalDate, val classes: List<ClassDailySummary>)

data class ClassDailySummary(
    val classroomId: ClassroomId,
    val classroomName: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val heldToday: Boolean,
    val counts: AttendanceCounts,
    val rows: List<AttendanceView>,
)

data class AttendanceCounts(
    val total: Int,
    val scheduled: Int,
    val present: Int,
    val checkedOut: Int,
    val absent: Int,
    val late: Int,
    val earlyLeave: Int,
    val attendanceRate: Double?,
)

/** 월간 출석부 (ATT-003) */
interface GetMonthlyAttendanceQuery {
    fun get(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, month: YearMonth): MonthlyAttendanceView
}

data class MonthlyAttendanceView(
    val classroomId: ClassroomId,
    val classroomName: String,
    val month: YearMonth,
    val classDays: List<LocalDate>,
    val rows: List<MonthlyRow>,
)

/** 교육청 보고용 출석부 엑셀 (ATT-004). 다운로드 이력은 감사 로그로 남긴다 */
interface ExportAttendanceUseCase {
    fun export(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, month: YearMonth, password: String?): ExportedFile
}

data class ExportedFile(val filename: String, val bytes: ByteArray)

/** 대시보드 (DASH-001 투데이 KPI, DASH-002 실시간 타임라인, DASH-005 결석/지각 위젯) */
interface DashboardQuery {
    fun today(actor: UserId, institutionId: InstitutionId): DashboardToday
    fun timeline(actor: UserId, institutionId: InstitutionId, limit: Int): List<DashboardTimelineEntry>
}

data class DashboardToday(
    val date: LocalDate,
    val asOf: Instant,
    val counts: AttendanceCounts,
    val excusedAbsent: Int,
    val notArrivedCount: Int,
    /** 알림장(NTC) 스프린트에서 채움. 그 전까지 null */
    val noticeReadRate: Double?,
    val notArrived: List<DashboardStudentItem>,
    val late: List<DashboardStudentItem>,
    val absent: List<DashboardStudentItem>,
)

data class DashboardStudentItem(
    val dayId: AttendanceDayId,
    val studentId: StudentId,
    val studentName: String,
    val classroomId: ClassroomId,
    val classroomName: String,
    val status: AttendanceStatus,
    val classStartTime: LocalTime,
    val checkInAt: Instant?,
    /** 지각: 수업 시작 대비 몇 분 늦었는지 / 미등원: 시작 후 몇 분 지났는지 */
    val minutesLate: Long?,
    val absenceReason: String?,
)

data class DashboardTimelineEntry(
    val eventId: AttendanceEventId,
    val studentId: StudentId,
    val studentName: String,
    val classroomId: ClassroomId?,
    val classroomName: String?,
    val type: AttendanceEventType,
    val fromStatus: AttendanceStatus,
    val toStatus: AttendanceStatus,
    val isLate: Boolean,
    val destinationName: String?,
    val reason: String?,
    val actorName: String,
    val source: AttendanceSource,
    val occurredAt: Instant,
)
