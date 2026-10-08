package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 통계/분석 (STAT-001~007, Phase 2). 원장·실장 전용 읽기 API.
 * 비율은 모두 0.0~1.0 (대시보드 noticeReadRate 의 % 와 다름), 분모가 0이면 null.
 */
interface StatsQuery {
    /** STAT-001 기간별 KPI — 일별 등원율 추이 + 알림장 열람률 추이 */
    fun kpi(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): KpiReport
    /** STAT-002 혼잡도 — 요일×시간대 등·하원 건수 */
    fun congestion(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): CongestionReport
    /** STAT-003 반별 성과 비교 */
    fun classrooms(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): ClassroomComparison
    /** STAT-004 반별 상세 — 기간별 등원/결석/지각 추이 */
    fun classroomTrend(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, from: LocalDate?, to: LocalDate?): ClassroomTrend
    /** STAT-005 재원 현황 — 상태 비율, 학년별 분포 */
    fun enrollmentStatus(actor: UserId, institutionId: InstitutionId): EnrollmentStatusReport
    /** STAT-006 원생 증감 추이 — 월별 신규/퇴원, 퇴원 사유 */
    fun enrollmentTrend(actor: UserId, institutionId: InstitutionId, from: YearMonth?, to: YearMonth?): EnrollmentTrendReport
}

enum class StatsSection { KPI, CLASSROOMS, ENROLLMENT }

/** STAT-007 맞춤 리포트 (엑셀) */
interface ExportStatsReportUseCase {
    fun export(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?, sections: Set<StatsSection>): ExportedFile
}

data class DailyKpi(
    val date: LocalDate,
    val scheduled: Int, val present: Int, val late: Int, val earlyLeave: Int, val absent: Int,
    /** present / scheduled */
    val attendanceRate: Double?,
)

data class DailyReadRate(val date: LocalDate, val notices: Int, val readRate: Double?)

data class KpiSummary(
    val scheduled: Int, val present: Int, val late: Int, val absent: Int,
    val attendanceRate: Double?, val lateRate: Double?, val absentRate: Double?,
    val noticeCount: Int, val noticeReadRate: Double?,
)

data class KpiReport(
    val from: LocalDate, val to: LocalDate,
    val summary: KpiSummary, val days: List<DailyKpi>, val noticeReads: List<DailyReadRate>,
)

data class CongestionCell(val weekday: Int, val hour: Int, val checkIns: Int, val checkOuts: Int)

data class CongestionReport(
    val from: LocalDate, val to: LocalDate,
    /** 1=월 … 7=일. 값이 있는 칸만 */
    val cells: List<CongestionCell>,
    /** 요일별로 기간에 몇 번 있었는지 — 화면에서 하루 평균을 내는 데 쓴다 */
    val weekdayCounts: Map<Int, Int>,
    val peaks: List<CongestionCell>,
)

data class ClassroomStat(
    val classroomId: UUID, val name: String, val students: Int,
    val scheduled: Int, val present: Int, val late: Int, val absent: Int,
    val attendanceRate: Double?, val noticeReadRate: Double?,
)

data class ClassroomComparison(val from: LocalDate, val to: LocalDate, val classrooms: List<ClassroomStat>)

data class ClassroomTrend(val classroomId: UUID, val name: String, val from: LocalDate, val to: LocalDate, val days: List<DailyKpi>)

data class GradeCount(val grade: String, val count: Int)

data class EnrollmentStatusReport(val active: Int, val paused: Int, val withdrawn: Int, val byGrade: List<GradeCount>)

data class MonthlyEnrollment(val month: YearMonth, val joined: Int, val withdrawn: Int, val net: Int)

data class ReasonCount(val reason: String, val count: Int)

data class EnrollmentTrendReport(val from: YearMonth, val to: YearMonth, val months: List<MonthlyEnrollment>, val reasons: List<ReasonCount>)

/** 대시보드 위젯 (DASH-004 미열람 알림 위젯, DASH-006 혼잡도 미니 위젯) */
interface DashboardWidgetsQuery {
    /** 최근 7일 발송 알림장 중 열람률이 [threshold] 미만인 건 — 낮은 순. 교사는 본인이 보낸 건만 */
    fun lowReadNotices(actor: UserId, institutionId: InstitutionId, limit: Int): LowReadNotices
    /** 오늘 시간대별 등·하원 건수 */
    fun congestionToday(actor: UserId, institutionId: InstitutionId): CongestionToday
}

data class LowReadNotice(
    val noticeId: UUID, val title: String, val sentAt: java.time.Instant,
    val targetStudents: Int, val readStudents: Int, val readRate: Double,
)

data class LowReadNotices(val threshold: Double, val notices: List<LowReadNotice>)

data class HourCount(val hour: Int, val checkIns: Int, val checkOuts: Int)

data class CongestionToday(val date: LocalDate, val hours: List<HourCount>, val peakHour: Int?)
