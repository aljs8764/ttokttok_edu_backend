package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AttendanceCounts
import com.ttokttok.application.port.`in`.AttendanceView
import com.ttokttok.application.port.`in`.BulkAttendanceUseCase
import com.ttokttok.application.port.`in`.ChangeAttendanceStatusUseCase
import com.ttokttok.application.port.`in`.CheckInUseCase
import com.ttokttok.application.port.`in`.CheckOutUseCase
import com.ttokttok.application.port.`in`.ClassDailySummary
import com.ttokttok.application.port.`in`.DailyReport
import com.ttokttok.application.port.`in`.ExportAttendanceUseCase
import com.ttokttok.application.port.`in`.ExportedFile
import com.ttokttok.application.port.`in`.GetDailyReportQuery
import com.ttokttok.application.port.`in`.GetMonthlyAttendanceQuery
import com.ttokttok.application.port.`in`.MonthlyAttendanceView
import com.ttokttok.application.port.`in`.UpdateAbsenceReasonUseCase
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.AttendanceRegister
import com.ttokttok.application.port.out.AttendanceRegisterPort
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.attendance.AttendanceDay
import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.MonthlyAttendanceSheet
import com.ttokttok.domain.attendance.ScheduleRule
import com.ttokttok.domain.attendance.TodayKpi
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DomainException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.institution.Institution
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** 반 시간표 + 기관 설정 → 지각·조퇴 기준 */
internal fun ruleOf(classroom: Classroom, institution: Institution, clock: ClockPort) = ScheduleRule(
    classroom.startTime, classroom.endTime, institution.lateThresholdMinutes, institution.earlyLeaveThresholdMinutes, clock.zone(),
)

internal fun TodayKpi.toCounts() = AttendanceCounts(
    total = total, scheduled = scheduled, present = present, checkedOut = checkedOut,
    absent = absent, late = late, earlyLeave = earlyLeave, attendanceRate = attendanceRate,
)

// ───────── ATT-002 수동 변경 ─────────

@Service
class ChangeAttendanceStatusService(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val students: StudentPort,
    private val destinations: DestinationPort,
    private val guardians: GuardianPort,
    private val audit: AuditLogPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : ChangeAttendanceStatusUseCase {

    @Transactional
    override fun change(command: ChangeAttendanceStatusUseCase.Command): AttendanceView {
        val current = attendance.findDayById(command.dayId)?.takeIf { it.institutionId == command.institutionId }
            ?: throw NotFoundException("출결 기록")
        val classroom = guard.requireClassroomAccess(command.actor, command.institutionId, current.classroomId)
        // 등·하원 원터치와 같은 행 잠금으로 동시 변경 직렬화
        val locked = attendance.findDayForUpdate(current.studentId, current.classroomId, current.date)!!
        val now = clock.now()
        val t = locked.overrideStatus(command.status, command.reason, now, command.actor, command.source, command.isLate, command.isEarlyLeave)

        val saved = attendance.saveDay(t.day)
        attendance.saveEvent(t.event, null)
        val student = students.find(saved.studentId, command.institutionId) ?: throw NotFoundException("원생")
        audit.record(
            AuditEntry(
                institutionId = command.institutionId, actorId = command.actor, action = "ATTENDANCE_STATUS_CHANGE",
                resource = "attendance_day", resourceId = saved.id.value.toString(),
                diff = mapOf(
                    "student" to student.name, "date" to saved.date.toString(),
                    "from" to mapOf("status" to locked.status.name, "isLate" to locked.isLate, "isEarlyLeave" to locked.isEarlyLeave),
                    "to" to mapOf("status" to saved.status.name, "isLate" to saved.isLate, "isEarlyLeave" to saved.isEarlyLeave),
                    "reason" to t.event.reason,
                ),
                at = now,
            ),
        )
        // 수동 정정은 학부모 푸시를 보내지 않는다(오등록 정정 알림이 혼란을 줌). 화면 동기화만.
        val view = toAttendanceView(saved, student.name, saved.nextDestinationId?.let { destinations.find(it, command.institutionId) })
        realtime.attendanceUpdated(command.institutionId, classroom.id, view.toPayload())
        notifyGuardians(guardians, realtime, saved.studentId, saved.status.name)
        return view
    }
}

@Service
class UpdateAbsenceReasonService(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val students: StudentPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : UpdateAbsenceReasonUseCase {

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, dayId: AttendanceDayId, reason: String?): AttendanceView {
        val day = attendance.findDayById(dayId)?.takeIf { it.institutionId == institutionId } ?: throw NotFoundException("출결 기록")
        guard.requireClassroomAccess(actor, institutionId, day.classroomId)
        val updated = attendance.saveDay(day.withAbsenceReason(reason))
        val student = students.find(day.studentId, institutionId) ?: throw NotFoundException("원생")
        audit.record(
            AuditEntry(
                institutionId, actor, "ATTENDANCE_ABSENCE_REASON", "attendance_day", dayId.value.toString(),
                mapOf("student" to student.name, "date" to day.date.toString(), "from" to day.absenceReason, "to" to updated.absenceReason),
                clock.now(),
            ),
        )
        return toAttendanceView(updated, student.name, null)
    }
}

// ───────── ATT-005~006 오프라인 큐 일괄 ─────────

/**
 * 트랜잭션을 걸지 않는다 — 항목마다 CheckIn/CheckOut 유스케이스가 각자 트랜잭션을 연다.
 * 같은 Idempotency-Key 재전송은 기존 결과를 그대로 돌려주므로 큐 재시도에 안전하다.
 */
@Service
class BulkAttendanceService(
    private val checkIn: CheckInUseCase,
    private val checkOut: CheckOutUseCase,
) : BulkAttendanceUseCase {

    override fun submit(actor: UserId, institutionId: InstitutionId, items: List<BulkAttendanceUseCase.Item>): List<BulkAttendanceUseCase.Result> {
        if (items.isEmpty()) throw InvalidInputException("EMPTY_BULK", "전송할 항목이 없습니다")
        if (items.size > BulkAttendanceUseCase.MAX_ITEMS) throw InvalidInputException("TOO_MANY_ITEMS", "한 번에 최대 ${BulkAttendanceUseCase.MAX_ITEMS}건까지 보낼 수 있습니다")
        // 오프라인 동안 쌓인 순서대로(등원 → 하원) 처리해야 전이가 맞는다
        return items.withIndex().sortedBy { it.value.clientAt ?: Instant.MAX }.map { (i, item) ->
            try {
                val view = when (item.type) {
                    AttendanceEventType.CHECK_IN -> checkIn.checkIn(
                        CheckInUseCase.Command(actor, institutionId, item.studentId, item.classroomId, item.clientAt, item.idempotencyKey),
                    )
                    AttendanceEventType.CHECK_OUT -> checkOut.checkOut(
                        CheckOutUseCase.Command(
                            actor, institutionId, item.studentId, item.classroomId,
                            item.destinationId ?: throw InvalidInputException("DESTINATION_REQUIRED", "하원은 목적지가 필요합니다"),
                            item.clientAt, item.idempotencyKey,
                        ),
                    )
                    AttendanceEventType.STATUS_CHANGE -> throw InvalidInputException("UNSUPPORTED_TYPE", "일괄 전송은 등원·하원만 가능합니다")
                }
                BulkAttendanceUseCase.Result(i, true, view, null, null)
            } catch (e: DomainException) {
                BulkAttendanceUseCase.Result(i, false, null, e.code, e.message)
            }
        }.sortedBy { it.index }
    }
}

// ───────── ATT-001 데일리 리포트 ─────────

@Service
class GetDailyReportService(
    private val guard: AccessGuard,
    private val institutions: InstitutionPort,
    private val attendance: AttendancePort,
    private val enrollments: EnrollmentPort,
    private val students: StudentPort,
    private val destinations: DestinationPort,
    private val clock: ClockPort,
) : GetDailyReportQuery {

    @Transactional(readOnly = true)
    override fun report(actor: UserId, institutionId: InstitutionId, date: LocalDate, classroomId: ClassroomId?): DailyReport {
        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        val classes = if (classroomId != null) listOf(guard.requireClassroomAccess(actor, institutionId, classroomId))
        else guard.visibleClassrooms(actor, institutionId)
        val visibleIds = classes.map { it.id }.toSet()

        val days = attendance.findDaysByInstitution(institutionId, date).filter { it.classroomId in visibleIds }
        val daysByClass = days.groupBy { it.classroomId }
        val rosterIds = classes.associate { it.id to enrollments.findCurrentStudentIds(it.id) }
        val names = students.findAllByIds((rosterIds.values.flatten() + days.map { it.studentId }).toSet()).associate { it.id to it.name }
        val dests = destinations.findAllByIds(days.mapNotNull { it.nextDestinationId }.toSet()).associateBy { it.id }
        // 과거 날짜는 마감 시점 기준으로 보이도록 "지금" 대신 그날 끝을 쓴다
        val asOf = minOf(clock.now(), date.plusDays(1).atStartOfDay(clock.zone()).toInstant())

        val summaries = classes.sortedBy { it.startTime }.map { c ->
            val rule = ruleOf(c, institution, clock)
            val classDays = daysByClass[c.id].orEmpty()
            val kpi = TodayKpi.compute(classDays.map { it to rule }, asOf)
            val byStudent = classDays.associateBy { it.studentId }
            // 출결 행이 없는 재원생도 빈 행으로 보여 준다(배치 전·수업 없는 날)
            val ids = (rosterIds[c.id].orEmpty() + byStudent.keys).distinct()
            val rows = ids.map { sid ->
                val d = byStudent[sid]
                if (d != null) toAttendanceView(d, names[sid] ?: "(알 수 없음)", d.nextDestinationId?.let { dests[it] })
                else toAttendanceView(AttendanceDay(AttendanceDayId(UUID(0, 0)), institutionId, sid, c.id, date), names[sid] ?: "(알 수 없음)", null)
                    .copy(dayId = null)
            }.sortedBy { it.studentName }
            ClassDailySummary(c.id, c.name, c.startTime, c.endTime, c.isHeldOn(date), kpi.toCounts(), rows)
        }
        return DailyReport(date, summaries)
    }
}

// ───────── ATT-003 월간 출석부 / ATT-004 엑셀 ─────────

@Service
class MonthlyAttendanceService(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val enrollments: EnrollmentPort,
    private val students: StudentPort,
) : GetMonthlyAttendanceQuery {

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, month: YearMonth): MonthlyAttendanceView {
        val classroom = guard.requireClassroomAccess(actor, institutionId, classroomId)
        return build(classroom, month)
    }

    /** 권한 확인 없이 조립 — 호출자가 확인한다 (엑셀 내보내기 재사용) */
    fun build(classroom: Classroom, month: YearMonth): MonthlyAttendanceView {
        val days = attendance.findDaysInRange(classroom.id, month.atDay(1), month.atEndOfMonth())
        val ids = (enrollments.findCurrentStudentIds(classroom.id) + days.map { it.studentId }).toSet()
        val roster = students.findAllByIds(ids).associate { it.id to it.name }
        val classDays = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { classroom.isHeldOn(it) }
        // 수업 요일이 아닌 날 기록(보강)도 열에 포함
        val allDays = (classDays + days.map { it.date }).distinct().sorted()
        return MonthlyAttendanceView(classroom.id, classroom.name, month, allDays, MonthlyAttendanceSheet.build(month, roster, days))
    }
}

@Service
class ExportAttendanceService(
    private val guard: AccessGuard,
    private val monthly: MonthlyAttendanceService,
    private val institutions: InstitutionPort,
    private val users: UserPort,
    private val register: AttendanceRegisterPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : ExportAttendanceUseCase {

    @Transactional
    override fun export(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, month: YearMonth, password: String?): ExportedFile {
        // 교육청 제출용 원본은 관리자만 (스펙 5장: ADMIN+)
        guard.requireManager(actor, institutionId)
        val classroom = guard.requireClassroomAccess(actor, institutionId, classroomId)
        val pw = password?.takeIf { it.isNotEmpty() }
        if (pw != null && pw.length !in 4..64) throw InvalidInputException("INVALID_PASSWORD", "엑셀 비밀번호는 4~64자입니다")

        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        val view = monthly.build(classroom, month)
        val now = clock.now()
        val bytes = register.render(
            AttendanceRegister(
                institutionName = institution.name,
                classroomName = classroom.name,
                teacherNames = classroom.teacherIds.mapNotNull { users.findById(it)?.name }.sorted(),
                month = month,
                classDays = view.classDays.filter { classroom.isHeldOn(it) }.toSet(),
                rows = view.rows,
                generatedAt = now,
            ),
            pw,
        )
        audit.record(
            AuditEntry(
                institutionId, actor, "ATTENDANCE_EXPORT", "classroom", classroom.id.value.toString(),
                mapOf("month" to month.toString(), "rows" to view.rows.size, "encrypted" to (pw != null)),
                now,
            ),
        )
        return ExportedFile("${sanitize(classroom.name)}_출석부_$month.xlsx", bytes)
    }

    private fun sanitize(name: String) = name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
}
