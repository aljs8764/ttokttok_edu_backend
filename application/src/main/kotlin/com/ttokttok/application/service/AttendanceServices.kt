package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AttendanceView
import com.ttokttok.application.port.`in`.CheckInUseCase
import com.ttokttok.application.port.`in`.CheckOutUseCase
import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import com.ttokttok.application.port.`in`.GetDailyAttendanceQuery
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.attendance.AttendanceChanged
import com.ttokttok.domain.attendance.AttendanceDay
import com.ttokttok.domain.attendance.AttendanceSource
import com.ttokttok.domain.attendance.ScheduleRule
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.institution.Institution
import com.ttokttok.domain.student.Student
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 시스템(배치) 행위자 — 감사 로그에서 사람과 구분 */
val SYSTEM_ACTOR = UserId(UUID(0, 0))

/** 오프라인 큐로 늦게 도착한 요청: 서버 시각과 10분 이내면 client 시각 채택 (스펙 7-1) */
private val CLIENT_SKEW_TOLERANCE: Duration = Duration.ofMinutes(10)

internal fun resolveEventTime(clientAt: Instant?, serverNow: Instant): Instant =
    if (clientAt != null && Duration.between(clientAt, serverNow).abs() <= CLIENT_SKEW_TOLERANCE) clientAt else serverNow

/**
 * 등원·하원 공통 흐름:
 * 권한 확인 → 멱등 확인 → 행 잠금 조회(없으면 생성) → 도메인 전이 → 저장 + Outbox + 실시간
 * 모두 한 트랜잭션. 외부 발송은 워커가 Outbox를 읽어 처리한다.
 */
@Service
class AttendanceCommandSupport(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val institutions: InstitutionPort,
    private val destinations: DestinationPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) {
    data class Context(val institution: Institution, val classroom: Classroom, val student: Student, val day: AttendanceDay, val at: Instant)

    fun load(actor: UserId, institutionId: InstitutionId, studentId: StudentId, classroomId: ClassroomId, clientAt: Instant?): Context {
        val classroom = guard.requireClassroomAccess(actor, institutionId, classroomId)
        val student = students.find(studentId, institutionId) ?: throw NotFoundException("원생")
        if (enrollments.findCurrent(studentId).none { it.classroomId == classroomId }) throw ForbiddenException("이 반에 소속된 원생이 아닙니다")
        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")

        val now = clock.now()
        val at = resolveEventTime(clientAt, now)
        val date = LocalDate.ofInstant(at, clock.zone())
        val day = attendance.findDayForUpdate(studentId, classroomId, date) ?: run {
            // 배치 전이거나 수업 없는 날(보강 등)의 처리 → 즉시 생성 후 잠금 재조회
            attendance.insertDayIfAbsent(AttendanceDay(AttendanceDayId.new(), institutionId, studentId, classroomId, date))
            attendance.findDayForUpdate(studentId, classroomId, date)!!
        }
        return Context(institution, classroom, student, day, at)
    }

    fun ruleOf(ctx: Context) = ScheduleRule(
        ctx.classroom.startTime, ctx.classroom.endTime,
        ctx.institution.lateThresholdMinutes, ctx.institution.earlyLeaveThresholdMinutes, clock.zone(),
    )

    fun commit(ctx: Context, transition: AttendanceDay.Transition, idempotencyKey: String?, destination: Destination?): AttendanceView {
        val saved = attendance.saveDay(transition.day)
        attendance.saveEvent(transition.event, idempotencyKey)
        outbox.publish(
            AttendanceChanged(
                institutionId = ctx.institution.id, institutionName = ctx.institution.name,
                studentId = ctx.student.id, studentName = ctx.student.name,
                type = transition.event.type, toStatus = saved.status, isLate = saved.isLate,
                destinationName = destination?.name, occurredAt = transition.event.occurredAt,
            ),
        )
        val view = toAttendanceView(saved, ctx.student.name, destination)
        realtime.attendanceUpdated(ctx.institution.id, ctx.classroom.id, view.toPayload())
        return view
    }

    /** 같은 Idempotency-Key 재요청이면 현재 상태를 그대로 돌려준다 (네트워크 재시도·더블 터치) */
    fun replayIfDuplicate(institutionId: InstitutionId, key: String?): AttendanceView? {
        if (key.isNullOrBlank()) return null
        val prior = attendance.findEventByIdempotencyKey(institutionId, key) ?: return null
        val day = attendance.findDayById(prior.attendanceDayId) ?: return null
        val student = students.find(day.studentId, institutionId) ?: return null
        val dest = day.nextDestinationId?.let { destinations.find(it, institutionId) }
        return toAttendanceView(day, student.name, dest)
    }
}

@Service
class CheckInService(private val support: AttendanceCommandSupport) : CheckInUseCase {
    @Transactional
    override fun checkIn(command: CheckInUseCase.Command): AttendanceView {
        support.replayIfDuplicate(command.institutionId, command.idempotencyKey)?.let { return it }
        val ctx = support.load(command.actor, command.institutionId, command.studentId, command.classroomId, command.clientAt)
        val t = ctx.day.checkIn(support.ruleOf(ctx), ctx.at, command.actor, AttendanceSource.TEACHER_APP)
        return support.commit(ctx, t, command.idempotencyKey, null)
    }
}

@Service
class CheckOutService(
    private val support: AttendanceCommandSupport,
    private val destinations: DestinationPort,
) : CheckOutUseCase {
    @Transactional
    override fun checkOut(command: CheckOutUseCase.Command): AttendanceView {
        support.replayIfDuplicate(command.institutionId, command.idempotencyKey)?.let { return it }
        val destination = destinations.find(command.destinationId, command.institutionId) ?: throw NotFoundException("목적지")
        val ctx = support.load(command.actor, command.institutionId, command.studentId, command.classroomId, command.clientAt)
        val t = ctx.day.checkOut(support.ruleOf(ctx), ctx.at, destination.id, command.actor, AttendanceSource.TEACHER_APP)
        return support.commit(ctx, t, command.idempotencyKey, destination)
    }
}

@Service
class GetDailyAttendanceService(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val enrollments: EnrollmentPort,
    private val students: StudentPort,
    private val destinations: DestinationPort,
) : GetDailyAttendanceQuery {
    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, date: LocalDate): List<AttendanceView> {
        guard.requireClassroomAccess(actor, institutionId, classroomId)
        val days = attendance.findDays(classroomId, date).associateBy { it.studentId }
        val roster = students.findAllByIds(enrollments.findCurrentStudentIds(classroomId)).sortedBy { it.name }
        val dests = destinations.findAllByIds(days.values.mapNotNull { it.nextDestinationId }).associateBy { it.id }
        return roster.map { s ->
            val day = days[s.id] ?: AttendanceDay(AttendanceDayId(UUID(0, 0)), institutionId, s.id, classroomId, date)
            toAttendanceView(day, s.name, day.nextDestinationId?.let { dests[it] }).let { if (days[s.id] == null) it.copy(dayId = null) else it }
        }
    }
}

@Service
class DailyAttendanceBatchService(
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val students: StudentPort,
    private val attendance: AttendancePort,
    private val clock: ClockPort,
) : DailyAttendanceBatchUseCase {

    /** 00:05 — 수업 요일 기준 SCHEDULED 행 생성. "금일 등원 예정 수"의 분모 */
    @Transactional
    override fun generateScheduled(date: LocalDate): Int {
        var created = 0
        classrooms.findAllHeldOn(date).forEach { c ->
            val ids = enrollments.findCurrentStudentIds(c.id)
            students.findAllByIds(ids).filter { it.status == com.ttokttok.domain.student.StudentStatus.ACTIVE }.forEach { s ->
                if (attendance.insertDayIfAbsent(AttendanceDay(AttendanceDayId.new(), c.institutionId, s.id, c.id, date))) created++
            }
        }
        return created
    }

    /** 23:50 — 그날까지 미처리 SCHEDULED → ABSENT 확정 */
    @Transactional
    override fun closeUnprocessed(date: LocalDate): Int {
        val now = clock.now()
        var closed = 0
        attendance.findScheduledBefore(date).forEach { day ->
            day.closeAsAbsent(now, SYSTEM_ACTOR)?.let { t ->
                attendance.saveDay(t.day)
                attendance.saveEvent(t.event, null)
                closed++
            }
        }
        // 자동 결석은 학부모 푸시 대상이 아님(학부모 사전 신청 기능은 Phase2)
        return closed
    }
}

internal fun toAttendanceView(day: AttendanceDay, studentName: String, destination: Destination?) = AttendanceView(
    dayId = day.id, studentId = day.studentId, studentName = studentName, classroomId = day.classroomId, date = day.date,
    status = day.status, isLate = day.isLate, isEarlyLeave = day.isEarlyLeave,
    checkInAt = day.checkInAt, checkOutAt = day.checkOutAt,
    nextDestinationId = day.nextDestinationId, nextDestinationName = destination?.name,
)

internal fun AttendanceView.toPayload(): Map<String, Any?> = mapOf(
    "type" to "attendance.updated",
    "studentId" to studentId.value.toString(),
    "studentName" to studentName,
    "classroomId" to classroomId.value.toString(),
    "date" to date.toString(),
    "status" to status.name,
    "isLate" to isLate,
    "isEarlyLeave" to isEarlyLeave,
    "checkInAt" to checkInAt?.toString(),
    "checkOutAt" to checkOutAt?.toString(),
    "nextDestinationName" to nextDestinationName,
)
