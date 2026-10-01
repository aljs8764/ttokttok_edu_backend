package com.ttokttok.domain.attendance

import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.AttendanceEventId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 출결 상태. IA의 5개 상태값(등원/하원/결석/지각/조퇴)은
 * 상태 4개 + 플래그 2개(isLate, isEarlyLeave)로 표현한다.
 */
enum class AttendanceStatus { SCHEDULED, IN, OUT, ABSENT }

enum class AttendanceEventType { CHECK_IN, CHECK_OUT, STATUS_CHANGE }
enum class AttendanceSource { TEACHER_APP, ADMIN_WEB, SYSTEM }

/** 월간 출석부 표기 (ATT-003): O 출석, △ 지각·조퇴, X 결석 */
enum class MonthlyMark { O, TRIANGLE, X, NONE }

data class AttendanceDay(
    val id: AttendanceDayId,
    val institutionId: InstitutionId,
    val studentId: StudentId,
    val classroomId: ClassroomId,
    val date: LocalDate,
    val status: AttendanceStatus = AttendanceStatus.SCHEDULED,
    val isLate: Boolean = false,
    val isEarlyLeave: Boolean = false,
    val checkInAt: Instant? = null,
    val checkOutAt: Instant? = null,
    val nextDestinationId: DestinationId? = null,
) {
    val monthlyMark: MonthlyMark
        get() = when (status) {
            AttendanceStatus.SCHEDULED -> MonthlyMark.NONE
            AttendanceStatus.ABSENT -> MonthlyMark.X
            AttendanceStatus.IN, AttendanceStatus.OUT -> if (isLate || isEarlyLeave) MonthlyMark.TRIANGLE else MonthlyMark.O
        }

    /** [출석] 원터치 (ATT-005). 허용 전이: SCHEDULED → IN */
    fun checkIn(rule: ScheduleRule, at: Instant, actor: UserId, source: AttendanceSource): Transition {
        if (status != AttendanceStatus.SCHEDULED) throw invalid(AttendanceStatus.IN)
        val late = at.isAfter(rule.lateDeadline(date))
        val next = copy(status = AttendanceStatus.IN, isLate = late, checkInAt = at)
        return Transition(next, event(AttendanceEventType.CHECK_IN, AttendanceStatus.IN, at, actor, source, null, null))
    }

    /** [하원] + 다음 목적지 (ATT-006). 허용 전이: IN → OUT */
    fun checkOut(rule: ScheduleRule, at: Instant, destination: DestinationId, actor: UserId, source: AttendanceSource): Transition {
        if (status != AttendanceStatus.IN) throw invalid(AttendanceStatus.OUT)
        val early = at.isBefore(rule.earlyLeaveDeadline(date))
        val next = copy(status = AttendanceStatus.OUT, isEarlyLeave = early, checkOutAt = at, nextDestinationId = destination)
        return Transition(next, event(AttendanceEventType.CHECK_OUT, AttendanceStatus.OUT, at, actor, source, null, destination))
    }

    /** 수동 강제 변경 (ATT-002). 어떤 전이든 가능하지만 사유는 필수. */
    fun overrideStatus(to: AttendanceStatus, reason: String, at: Instant, actor: UserId): Transition {
        if (reason.isBlank()) throw InvalidInputException("REASON_REQUIRED", "수동 변경 사유는 필수입니다")
        if (to == AttendanceStatus.SCHEDULED) throw InvalidInputException("INVALID_STATUS", "예정 상태로는 변경할 수 없습니다")
        val next = copy(status = to)
        return Transition(next, event(AttendanceEventType.STATUS_CHANGE, to, at, actor, AttendanceSource.ADMIN_WEB, reason, null))
    }

    /** 23:50 배치: 아무 처리도 없던 예정 건을 결석으로 확정 */
    fun closeAsAbsent(at: Instant, systemActor: UserId): Transition? {
        if (status != AttendanceStatus.SCHEDULED) return null
        val next = copy(status = AttendanceStatus.ABSENT)
        return Transition(next, event(AttendanceEventType.STATUS_CHANGE, AttendanceStatus.ABSENT, at, systemActor, AttendanceSource.SYSTEM, "미처리 자동 결석", null))
    }

    private fun event(
        type: AttendanceEventType, to: AttendanceStatus, at: Instant, actor: UserId,
        source: AttendanceSource, reason: String?, destination: DestinationId?,
    ) = AttendanceEvent(
        id = AttendanceEventId.new(), attendanceDayId = id, institutionId = institutionId, studentId = studentId,
        type = type, fromStatus = status, toStatus = to, actorId = actor, source = source,
        reason = reason, destinationId = destination, occurredAt = at,
    )

    private fun invalid(to: AttendanceStatus) =
        ConflictException("INVALID_TRANSITION", "현재 상태(${status})에서 ${to}(으)로 바로 변경할 수 없습니다. 수동 변경을 이용하세요")

    data class Transition(val day: AttendanceDay, val event: AttendanceEvent)
}

/** 지각·조퇴 판정 기준 (반 시간표 + 기관 설정). */
data class ScheduleRule(
    val startTime: LocalTime,
    val endTime: LocalTime,
    val lateThresholdMinutes: Int,
    val earlyLeaveThresholdMinutes: Int,
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    fun lateDeadline(date: LocalDate): Instant =
        date.atTime(startTime).plusMinutes(lateThresholdMinutes.toLong()).atZone(zone).toInstant()

    fun earlyLeaveDeadline(date: LocalDate): Instant =
        date.atTime(endTime).minusMinutes(earlyLeaveThresholdMinutes.toLong()).atZone(zone).toInstant()
}

/** 출결 이벤트 로그 — 타임라인·감사·통계의 원천. */
data class AttendanceEvent(
    val id: AttendanceEventId,
    val attendanceDayId: AttendanceDayId,
    val institutionId: InstitutionId,
    val studentId: StudentId,
    val type: AttendanceEventType,
    val fromStatus: AttendanceStatus,
    val toStatus: AttendanceStatus,
    val actorId: UserId,
    val source: AttendanceSource,
    val reason: String?,
    val destinationId: DestinationId?,
    val occurredAt: Instant,
)
