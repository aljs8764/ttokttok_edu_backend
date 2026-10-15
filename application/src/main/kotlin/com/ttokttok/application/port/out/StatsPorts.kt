package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.ClassroomStat
import com.ttokttok.application.port.`in`.EnrollmentStatusReport
import com.ttokttok.application.port.`in`.EnrollmentTrendReport
import com.ttokttok.application.port.`in`.KpiReport
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.student.StudentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** 통계 원천 조회 (읽기 전용). 구현은 JPA 네이티브 집계 쿼리 */
interface StatsReadPort {
    /** 일별 출결 합계. 지난 날은 daily_attendance_stat, [today] 는 attendance_day 에서 바로 계산 */
    fun dailyAttendance(institutionId: InstitutionId, from: LocalDate, to: LocalDate, today: LocalDate, classroomId: ClassroomId?): List<DailyCounts>
    fun classroomAttendance(institutionId: InstitutionId, from: LocalDate, to: LocalDate, today: LocalDate): List<ClassroomCounts>
    /** 기간 내 발송된 알림장별 (대상 학생 수, 읽은 학생 수) */
    fun noticeReads(institutionId: InstitutionId, from: Instant, to: Instant): List<NoticeReadRow>
    /** 반별 (현재 소속 학생 기준) 알림장 (대상, 읽음) — (알림장, 학생) 쌍 단위 */
    fun classroomNoticeReads(institutionId: InstitutionId, from: Instant, to: Instant): Map<ClassroomId, ReadCounts>
    fun congestion(institutionId: InstitutionId, from: Instant, to: Instant): List<CongestionRow>
    fun studentStatusCounts(institutionId: InstitutionId): Map<StudentStatus, Int>
    fun activeStudentsByGrade(institutionId: InstitutionId): List<Pair<String, Int>>
    fun activeStudentsByClassroom(institutionId: InstitutionId): Map<ClassroomId, Int>
    fun joinedByMonth(institutionId: InstitutionId, from: Instant, to: Instant): Map<YearMonth, Int>
    fun withdrawals(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<WithdrawalRow>
}

data class DailyCounts(val date: LocalDate, val scheduled: Int, val present: Int, val late: Int, val earlyLeave: Int, val absent: Int)
data class ClassroomCounts(val classroomId: ClassroomId, val scheduled: Int, val present: Int, val late: Int, val absent: Int)
data class NoticeReadRow(val date: LocalDate, val noticeId: java.util.UUID, val targets: Int, val read: Int)
data class ReadCounts(val targets: Int, val read: Int)
data class CongestionRow(val weekday: Int, val hour: Int, val checkIns: Int, val checkOuts: Int)
data class WithdrawalRow(val month: YearMonth, val reason: String, val count: Int)

/** STAT-007 엑셀 리포트. 구현: adapter-out-storage (POI) */
interface StatsReportPort {
    fun render(report: StatsReport): ByteArray
}

data class StatsReport(
    val institutionName: String,
    val from: LocalDate,
    val to: LocalDate,
    val generatedAt: Instant,
    val kpi: KpiReport?,
    val classrooms: List<ClassroomStat>?,
    val enrollmentStatus: EnrollmentStatusReport?,
    val enrollmentTrend: EnrollmentTrendReport?,
)

