package com.ttokttok.application.port.`in`

import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate

/** 원터치 등원 (ATT-005) */
interface CheckInUseCase {
    fun checkIn(command: Command): AttendanceView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId, val classroomId: ClassroomId,
        val clientAt: Instant?, val idempotencyKey: String?,
    )
}

/** 하원 + 다음 목적지 (ATT-006) */
interface CheckOutUseCase {
    fun checkOut(command: Command): AttendanceView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId, val classroomId: ClassroomId,
        val destinationId: DestinationId, val clientAt: Instant?, val idempotencyKey: String?,
    )
}

/** 교사 앱 반 화면 / 데일리 리포트 (ATT-001 최소형) */
interface GetDailyAttendanceQuery {
    fun get(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, date: LocalDate): List<AttendanceView>
}

/** 00:05 당일 예정 생성 / 23:50 미처리 결석 확정 */
interface DailyAttendanceBatchUseCase {
    fun generateScheduled(date: LocalDate): Int
    fun closeUnprocessed(date: LocalDate): Int
}

data class AttendanceView(
    val dayId: AttendanceDayId?,
    val studentId: StudentId,
    val studentName: String,
    val classroomId: ClassroomId,
    val date: LocalDate,
    val status: AttendanceStatus,
    val isLate: Boolean,
    val isEarlyLeave: Boolean,
    val checkInAt: Instant?,
    val checkOutAt: Instant?,
    val nextDestinationId: DestinationId?,
    val nextDestinationName: String?,
)
