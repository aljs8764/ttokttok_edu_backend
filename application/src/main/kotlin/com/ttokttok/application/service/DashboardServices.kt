package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.DashboardQuery
import com.ttokttok.application.port.`in`.DashboardStudentItem
import com.ttokttok.application.port.`in`.DashboardTimelineEntry
import com.ttokttok.application.port.`in`.DashboardToday
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.attendance.AttendanceDay
import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.attendance.TodayKpi
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * 관리자 웹 대시보드 (DASH-001·002·005).
 * 교사는 담당 반 범위로 좁혀서 같은 화면을 본다 (스펙 3장 권한 매트릭스).
 * 값은 매 요청 DB에서 계산한다 — 기관당 하루 수백 행 수준이라 인덱스(institution_id, date)로 충분.
 * 실시간 갱신은 기존 STOMP /topic/inst.{id} 의 attendance.updated 메시지를 받으면 다시 조회하는 방식.
 */
@Service
class DashboardService(
    private val guard: AccessGuard,
    private val institutions: InstitutionPort,
    private val attendance: AttendancePort,
    private val students: StudentPort,
    private val destinations: DestinationPort,
    private val users: UserPort,
    private val noticeReadRate: NoticeReadRateCalculator,
    private val clock: ClockPort,
) : DashboardQuery {

    @Transactional(readOnly = true)
    override fun today(actor: UserId, institutionId: InstitutionId): DashboardToday {
        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        val classes = guard.visibleClassrooms(actor, institutionId).associateBy { it.id }
        val now = clock.now()
        val date = clock.today()

        val days = attendance.findDaysByInstitution(institutionId, date).filter { it.classroomId in classes }
        val kpi = TodayKpi.compute(days.map { it to ruleOf(classes.getValue(it.classroomId), institution, clock) }, now)
        val names = students.findAllByIds((kpi.notArrived + kpi.lateDays + kpi.absentDays).map { it.studentId }.toSet())
            .associate { it.id to it.name }

        fun item(d: AttendanceDay): DashboardStudentItem {
            val c = classes.getValue(d.classroomId)
            val start = d.date.atTime(c.startTime).atZone(clock.zone()).toInstant()
            val minutes = when {
                d.checkInAt != null && d.isLate -> Duration.between(start, d.checkInAt).toMinutes()
                d.checkInAt == null && d.absenceReason == null && now.isAfter(start) && d.status == AttendanceStatus.SCHEDULED -> Duration.between(start, now).toMinutes()
                else -> null
            }
            return DashboardStudentItem(
                dayId = d.id, studentId = d.studentId, studentName = names[d.studentId] ?: "(알 수 없음)",
                classroomId = c.id, classroomName = c.name, status = d.status, classStartTime = c.startTime,
                checkInAt = d.checkInAt, minutesLate = minutes, absenceReason = d.absenceReason,
            )
        }

        return DashboardToday(
            date = date, asOf = now, counts = kpi.toCounts(),
            excusedAbsent = kpi.excusedAbsent, notArrivedCount = kpi.notArrived.size,
            // 교사는 본인이 보낸 알림장 기준
            noticeReadRate = noticeReadRate.last24h(
                institutionId, if (guard.requireStaff(actor, institutionId).role.isManager) null else actor, now,
            ),
            // 미등원은 오래 기다린 순, 지각은 많이 늦은 순
            notArrived = kpi.notArrived.map(::item).sortedByDescending { it.minutesLate ?: 0 },
            late = kpi.lateDays.map(::item).sortedByDescending { it.minutesLate ?: 0 },
            absent = kpi.absentDays.map(::item).sortedBy { it.studentName },
        )
    }

    @Transactional(readOnly = true)
    override fun timeline(actor: UserId, institutionId: InstitutionId, limit: Int): List<DashboardTimelineEntry> {
        val m = guard.requireStaff(actor, institutionId)
        val classes: Map<ClassroomId, Classroom> = guard.visibleClassrooms(actor, institutionId).associateBy { it.id }
        val scope = if (m.role.isManager) null else classes.keys
        val events = attendance.findRecentEvents(institutionId, scope, limit.coerceIn(1, 100))
        if (events.isEmpty()) return emptyList()

        val days = attendance.findDaysByIds(events.map { it.attendanceDayId }.toSet()).associateBy { it.id }
        val names = students.findAllByIds(events.map { it.studentId }.toSet()).associate { it.id to it.name }
        val dests = destinations.findAllByIds(events.mapNotNull { it.destinationId }.toSet()).associate { it.id to it.name }
        val actors = events.map { it.actorId }.toSet().associateWith { id -> if (id == SYSTEM_ACTOR) "시스템" else users.findById(id)?.name ?: "(알 수 없음)" }

        return events.map { e ->
            val day = days[e.attendanceDayId]
            val c = day?.let { classes[it.classroomId] }
            DashboardTimelineEntry(
                eventId = e.id, studentId = e.studentId, studentName = names[e.studentId] ?: "(알 수 없음)",
                classroomId = day?.classroomId, classroomName = c?.name,
                type = e.type, fromStatus = e.fromStatus, toStatus = e.toStatus,
                // 이벤트 시점의 지각 여부는 등원 이벤트 + 현재 행 기준으로 근사
                isLate = e.type == AttendanceEventType.CHECK_IN && (day?.isLate ?: false),
                destinationName = e.destinationId?.let { dests[it] }, reason = e.reason,
                actorName = actors.getValue(e.actorId), source = e.source, occurredAt = e.occurredAt,
            )
        }
    }
}
