package com.ttokttok.domain.attendance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 투데이 KPI (DASH-001) + 결석/지각 위젯 (DASH-005). 스펙 7-2.
 *
 * - 실시간 등원율 = (IN + OUT) ÷ (오늘 출결 행 수 − 사전 연락 결석) × 100
 *   사전 연락 결석 = 사유가 등록된 ABSENT (학부모·교사가 미리 알린 결석)
 * - 미등원 = SCHEDULED 이면서 수업 시작 + 지각 기준이 지난 학생
 */
data class TodayKpi(
    val total: Int,
    val present: Int,
    val checkedOut: Int,
    val absent: Int,
    val excusedAbsent: Int,
    val late: Int,
    val earlyLeave: Int,
    val scheduled: Int,
    /** 소수점 첫째 자리. 분모가 0이면 null */
    val attendanceRate: Double?,
    val notArrived: List<AttendanceDay>,
    val lateDays: List<AttendanceDay>,
    val absentDays: List<AttendanceDay>,
) {
    companion object {
        fun compute(days: List<Pair<AttendanceDay, ScheduleRule>>, now: Instant): TodayKpi {
            val all = days.map { it.first }
            val attended = all.filter { it.status == AttendanceStatus.IN || it.status == AttendanceStatus.OUT }
            val absent = all.filter { it.status == AttendanceStatus.ABSENT }
            val excused = absent.count { it.absenceReason != null }
            val denominator = all.size - excused
            val rate = if (denominator <= 0) null
            else BigDecimal(attended.size * 100).divide(BigDecimal(denominator), 1, RoundingMode.HALF_UP).toDouble()
            val notArrived = days.filter { (d, rule) -> d.status == AttendanceStatus.SCHEDULED && now.isAfter(rule.lateDeadline(d.date)) }
                .map { it.first }
            return TodayKpi(
                total = all.size,
                present = attended.size,
                checkedOut = all.count { it.status == AttendanceStatus.OUT },
                absent = absent.size,
                excusedAbsent = excused,
                late = attended.count { it.isLate },
                earlyLeave = attended.count { it.isEarlyLeave },
                scheduled = all.count { it.status == AttendanceStatus.SCHEDULED },
                attendanceRate = rate,
                notArrived = notArrived,
                lateDays = attended.filter { it.isLate },
                absentDays = absent,
            )
        }
    }
}
