package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ChildWeek
import com.ttokttok.application.port.`in`.DashboardScheduleItem
import com.ttokttok.application.port.`in`.DashboardScheduleQuery
import com.ttokttok.application.port.`in`.DaySchedule
import com.ttokttok.application.port.`in`.ParentScheduleQuery
import com.ttokttok.application.port.`in`.ScheduleItem
import com.ttokttok.application.port.`in`.WeekSchedule
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.EventTargetPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.NoticePort
import com.ttokttok.application.port.out.NoticeSearchCriteria
import com.ttokttok.application.port.out.RsvpResponsePort
import com.ttokttok.application.port.out.SchoolEventPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.event.SchoolEventStatus
import com.ttokttok.domain.notice.NoticeStatus
import com.ttokttok.domain.student.StudentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * PAR-003 자녀 주간 스케줄. 소속 전 기관의 반 시간표 + 그 주 행사 + 그 주 출결 상태를 하루 단위로 합친다.
 * 다자녀·다기관이 한 화면에 나오도록 자녀별로 묶는다.
 */
@Service
class ParentScheduleService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val enrollments: EnrollmentPort,
    private val classrooms: ClassroomPort,
    private val attendance: AttendancePort,
    private val events: SchoolEventPort,
    private val eventTargets: EventTargetPort,
    private val responses: RsvpResponsePort,
    private val clock: ClockPort,
) : ParentScheduleQuery {

    @Transactional(readOnly = true)
    override fun week(parent: UserId, childId: StudentId?, weekStart: LocalDate?): WeekSchedule {
        val monday = (weekStart ?: clock.today()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dates = (0L..6L).map { monday.plusDays(it) }
        val mine = guardians.findLinkedByUser(parent).map { it.studentId }.toSet()
        if (childId != null && childId !in mine) throw ForbiddenException("본인 자녀가 아닙니다")
        val kids = students.findAllByIds(childId?.let { setOf(it) } ?: mine).filter { it.status != StudentStatus.WITHDRAWN }
        val instNames = institutions.findAllByIds(kids.map { it.institutionId }.toSet()).associate { it.id to it.name }

        val zone = clock.zone()
        val from = monday.atStartOfDay(zone).toInstant()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant()

        val result = kids.sortedBy { it.name }.map { kid ->
            val classes = enrollments.findCurrent(kid.id).mapNotNull { classrooms.find(it.classroomId, kid.institutionId) }
            val days = classes.flatMap { c -> attendance.findDaysInRange(c.id, monday, dates.last()).filter { it.studentId == kid.id } }
                .associateBy { it.classroomId to it.date }

            val eventIds = eventTargets.findEventIdsForStudents(listOf(kid.id), from, 100)
            val weekEvents = events.findAllByIds(eventIds).filter { it.status == SchoolEventStatus.ACTIVE && it.startsAt.isBefore(to) }
            val myAnswers = responses.findByEvents(weekEvents.map { it.id }).filter { it.studentId == kid.id }.associateBy { it.eventId }
            val instName = instNames[kid.institutionId] ?: ""

            ChildWeek(
                kid.id, kid.name,
                dates.map { date ->
                    val classItems = classes.filter { kid.status == StudentStatus.ACTIVE && it.isHeldOn(date) }.map { c ->
                        ScheduleItem(
                            kind = "CLASS", title = c.name, institutionName = instName, startsAt = c.startTime, endsAt = c.endTime,
                            classroomId = c.id, eventId = null, status = days[c.id to date]?.status?.name,
                        )
                    }
                    val eventItems = weekEvents.filter { LocalDate.ofInstant(it.startsAt, zone) == date }.map { e ->
                        ScheduleItem(
                            kind = "EVENT", title = e.title, institutionName = instName,
                            startsAt = e.startsAt.atZone(zone).toLocalTime(), endsAt = e.endsAt?.atZone(zone)?.toLocalTime(),
                            classroomId = null, eventId = e.id.value, status = myAnswers[e.id]?.answer?.name,
                        )
                    }
                    DaySchedule(date, date.dayOfWeek, (classItems + eventItems).sortedBy { it.startsAt })
                },
            )
        }
        return WeekSchedule(monday, result)
    }
}

/** DASH-003 주요 일정: 다가오는 행사·RSVP 마감·예약 알림장 */
@Service
class DashboardScheduleService(
    private val guard: AccessGuard,
    private val events: SchoolEventPort,
    private val notices: NoticePort,
    private val clock: ClockPort,
) : DashboardScheduleQuery {

    @Transactional(readOnly = true)
    override fun upcoming(actor: UserId, institutionId: InstitutionId, days: Int): List<DashboardScheduleItem> {
        if (days !in 1..31) throw InvalidInputException("INVALID_DAYS", "days는 1~31입니다")
        val m = guard.requireStaff(actor, institutionId)
        val author = if (m.role.isManager) null else actor
        val now = clock.now()
        val until = now.plus(Duration.ofDays(days.toLong()))

        // 시작은 기간 밖이어도 응답 마감이 기간 안일 수 있어 넉넉히 가져와 나눈다
        val fetched = events.search(institutionId, author, now, 0, 100).items.filter { it.status == SchoolEventStatus.ACTIVE }
        val eventItems = fetched.filter { it.startsAt.isBefore(until) }.map { e ->
            DashboardScheduleItem("EVENT", e.startsAt, e.title, e.id.value, e.location)
        } + fetched.filter { e -> e.rsvpEnabled && e.rsvpDeadline?.let { d -> d.isAfter(now) && d.isBefore(until) } == true }
            .map { e -> DashboardScheduleItem("RSVP_DEADLINE", e.rsvpDeadline!!, "${e.title} 응답 마감", e.id.value, null) }

        val scheduled = notices.search(NoticeSearchCriteria(institutionId, author, null, NoticeStatus.SCHEDULED, 0, 100)).items
            .filter { it.scheduledAt.isBefore(until) }
            .map { n -> DashboardScheduleItem("NOTICE_SCHEDULED", n.scheduledAt, n.title, n.id.value, n.kind.name) }

        return (eventItems + scheduled).sortedBy { it.at }
    }
}
