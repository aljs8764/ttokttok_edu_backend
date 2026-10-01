package com.ttokttok.domain.attendance

import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.StudentId
import java.time.LocalDate
import java.time.YearMonth

/**
 * 월간 출석부 (ATT-003) 한 줄 = 원생 한 명.
 * 출석일수 = O + △ (지각·조퇴도 출석으로 센다), 결석일수 = X.
 */
data class MonthlyRow(
    val studentId: StudentId,
    val studentName: String,
    val cells: Map<LocalDate, MonthlyCell>,
) {
    val presentDays: Int get() = cells.values.count { it.mark == MonthlyMark.O }
    val partialDays: Int get() = cells.values.count { it.mark == MonthlyMark.TRIANGLE }
    val absentDays: Int get() = cells.values.count { it.mark == MonthlyMark.X }
    val attendedDays: Int get() = presentDays + partialDays
    /** 비고 열: "10/7 감기, 10/15 가족여행" */
    val remarks: String
        get() = cells.entries.filter { it.value.absenceReason != null }.sortedBy { it.key }
            .joinToString(", ") { "${it.key.monthValue}/${it.key.dayOfMonth} ${it.value.absenceReason}" }
}

data class MonthlyCell(
    val dayId: AttendanceDayId,
    val mark: MonthlyMark,
    val isLate: Boolean,
    val isEarlyLeave: Boolean,
    val absenceReason: String?,
)

object MonthlyAttendanceSheet {
    /**
     * @param roster 출석부에 올릴 원생(이름순 정렬은 호출자가 아닌 여기서 한다)
     * @param days 같은 반의 해당 월 출결 행. 명단에 없는 원생(중도 반 이동 등)의 행도 들어오면 명단에 추가한다
     */
    fun build(month: YearMonth, roster: Map<StudentId, String>, days: List<AttendanceDay>): List<MonthlyRow> {
        val inMonth = days.filter { YearMonth.from(it.date) == month }
        val byStudent = inMonth.groupBy { it.studentId }
        val ids = roster.keys + byStudent.keys
        return ids.map { id ->
            val cells = byStudent[id].orEmpty().associate { d ->
                d.date to MonthlyCell(d.id, d.monthlyMark, d.isLate, d.isEarlyLeave, d.absenceReason)
            }
            MonthlyRow(id, roster[id] ?: "(전출)", cells)
        }.sortedWith(compareBy({ it.studentName }, { it.studentId.value }))
    }
}
