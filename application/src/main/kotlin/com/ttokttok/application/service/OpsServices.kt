package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.CalendarEvent
import com.ttokttok.application.port.`in`.CounselNoteUseCase
import com.ttokttok.application.port.`in`.CounselNoteView
import com.ttokttok.application.port.`in`.EventCalendarUseCase
import com.ttokttok.application.port.`in`.EventTemplateUseCase
import com.ttokttok.application.port.`in`.EventTemplateView
import com.ttokttok.application.port.`in`.HealthAlert
import com.ttokttok.application.port.`in`.HealthView
import com.ttokttok.application.port.`in`.ManageEventUseCase
import com.ttokttok.application.port.`in`.ParentCounselUseCase
import com.ttokttok.application.port.`in`.ParentSlotView
import com.ttokttok.application.port.`in`.ParentTimetableUseCase
import com.ttokttok.application.port.`in`.SlotView
import com.ttokttok.application.port.`in`.StaffAttendanceUseCase
import com.ttokttok.application.port.`in`.StaffAttendanceView
import com.ttokttok.application.port.`in`.StaffMonthlyRow
import com.ttokttok.application.port.`in`.StudentHealthUseCase
import com.ttokttok.application.port.`in`.StudentTagUseCase
import com.ttokttok.application.port.`in`.TagView
import com.ttokttok.application.port.`in`.TaggedStudent
import com.ttokttok.application.port.`in`.TimetableUseCase
import com.ttokttok.application.port.`in`.VacancyView
import com.ttokttok.application.port.`in`.WaitlistEntryView
import com.ttokttok.application.port.`in`.WaitlistStatus
import com.ttokttok.application.port.`in`.WaitlistUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.CounselNotePort
import com.ttokttok.application.port.out.EventCalendarPort
import com.ttokttok.application.port.out.EventTemplatePort
import com.ttokttok.application.port.out.OpsAccessPort
import com.ttokttok.application.port.out.StaffAttendancePort
import com.ttokttok.application.port.out.StudentHealthPort
import com.ttokttok.application.port.out.StudentTagPort
import com.ttokttok.application.port.out.TimetablePort
import com.ttokttok.application.port.out.WaitlistPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** 기관 소속 확인 + 교사 담당 범위 확인 (원장·실장은 기관 전체) */
private fun OpsAccessPort.requireStudent(guard: AccessGuard, actor: UserId, institutionId: InstitutionId, studentId: UUID): String {
    val m = guard.requireStaff(actor, institutionId)
    val name = studentName(studentId, institutionId) ?: throw NotFoundException("원생")
    if (!m.role.isManager && !teacherCanSee(actor, studentId)) throw ForbiddenException("담당 반 원생이 아닙니다")
    return name
}

// ───────── STU-009 ─────────
@Service
class StudentHealthService(
    private val health: StudentHealthPort,
    private val access: OpsAccessPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : StudentHealthUseCase {
    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId, studentId: UUID): HealthView {
        access.requireStudent(guard, actor, institutionId, studentId)
        return health.find(studentId) ?: HealthView(studentId, null, null, null, null, null)
    }

    @Transactional
    override fun save(actor: UserId, institutionId: InstitutionId, studentId: UUID, command: StudentHealthUseCase.Save): HealthView {
        access.requireStudent(guard, actor, institutionId, studentId)
        listOf(command.allergies, command.conditions, command.notes).forEach {
            if ((it?.length ?: 0) > 500) throw InvalidInputException("INVALID_HEALTH", "각 항목은 500자까지입니다")
        }
        health.upsert(studentId, command.allergies.clean(), command.conditions.clean(), command.notes.clean(), actor, clock.now())
        return health.find(studentId) ?: HealthView(studentId, null, null, null, null, null)
    }

    @Transactional(readOnly = true)
    override fun alerts(actor: UserId, institutionId: InstitutionId, classroomId: UUID): List<HealthAlert> {
        guard.requireClassroomAccess(actor, institutionId, ClassroomId(classroomId))
        return health.alerts(classroomId)
    }

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
}

// ───────── STU-010 ─────────
@Service
class CounselNoteService(
    private val notes: CounselNotePort,
    private val access: OpsAccessPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : CounselNoteUseCase, ParentCounselUseCase {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<CounselNoteView> {
        access.requireStudent(guard, actor, institutionId, studentId)
        return notes.list(studentId)
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, studentId: UUID, command: CounselNoteUseCase.Command): CounselNoteView {
        access.requireStudent(guard, actor, institutionId, studentId)
        validate(command)
        val id = notes.insert(institutionId, studentId, actor, command.occurredOn, command.body.trim(), command.parentVisible, clock.now())
        return notes.find(id, institutionId) ?: throw NotFoundException("상담일지")
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: CounselNoteUseCase.Command): CounselNoteView {
        val m = guard.requireStaff(actor, institutionId)
        val current = notes.find(id, institutionId) ?: throw NotFoundException("상담일지")
        if (!m.role.isManager && current.authorId != actor.value) throw ForbiddenException("쓴 사람만 고칠 수 있습니다")
        validate(command)
        notes.update(id, command.occurredOn, command.body.trim(), command.parentVisible, clock.now())
        return notes.find(id, institutionId) ?: throw NotFoundException("상담일지")
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: UUID) {
        val m = guard.requireStaff(actor, institutionId)
        val current = notes.find(id, institutionId) ?: throw NotFoundException("상담일지")
        if (!m.role.isManager && current.authorId != actor.value) throw ForbiddenException("쓴 사람만 지울 수 있습니다")
        notes.delete(id)
    }

    @Transactional(readOnly = true)
    override fun list(parent: UserId, studentIds: Set<UUID>?): List<CounselNoteView> = notes.listForParent(parent, studentIds)

    private fun validate(c: CounselNoteUseCase.Command) {
        if (c.body.isBlank() || c.body.trim().length > 3000) throw InvalidInputException("INVALID_BODY", "내용은 1~3000자입니다")
        if (c.occurredOn.isAfter(clock.today())) throw InvalidInputException("INVALID_DATE", "상담일은 오늘 이전이어야 합니다")
    }
}

// ───────── STU-011 ─────────
private const val MAX_TAGS = 50

@Service
class StudentTagService(
    private val tags: StudentTagPort,
    private val access: OpsAccessPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : StudentTagUseCase {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<TagView> {
        guard.requireStaff(actor, institutionId)
        return tags.list(institutionId)
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, name: String, color: String?): TagView {
        guard.requireManager(actor, institutionId)
        val n = name.trim()
        if (n.isEmpty() || n.length > 30) throw InvalidInputException("INVALID_NAME", "태그 이름은 1~30자입니다")
        if (color != null && !Regex("^#[0-9a-fA-F]{6}$").matches(color)) throw InvalidInputException("INVALID_COLOR", "색은 #RRGGBB 형식입니다")
        if (tags.nameExists(institutionId, n)) throw ConflictException("TAG_EXISTS", "같은 이름의 태그가 있습니다")
        if (tags.count(institutionId) >= MAX_TAGS) throw ConflictException("TAG_LIMIT", "태그는 ${MAX_TAGS}개까지 만들 수 있습니다")
        val id = tags.insert(institutionId, n, color, clock.now())
        return tags.find(id, institutionId) ?: throw NotFoundException("태그")
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: UUID) {
        guard.requireManager(actor, institutionId)
        tags.find(id, institutionId) ?: throw NotFoundException("태그")
        tags.delete(id)
    }

    @Transactional(readOnly = true)
    override fun studentTags(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<TagView> {
        access.requireStudent(guard, actor, institutionId, studentId)
        return tags.tagsOf(studentId)
    }

    @Transactional
    override fun setStudentTags(actor: UserId, institutionId: InstitutionId, studentId: UUID, tagIds: List<UUID>): List<TagView> {
        access.requireStudent(guard, actor, institutionId, studentId)
        val ids = tagIds.distinct()
        if (ids.any { tags.find(it, institutionId) == null }) throw NotFoundException("태그")
        tags.setTags(studentId, ids)
        return tags.tagsOf(studentId)
    }

    @Transactional(readOnly = true)
    override fun students(actor: UserId, institutionId: InstitutionId, tagId: UUID): List<TaggedStudent> {
        val m = guard.requireStaff(actor, institutionId)
        tags.find(tagId, institutionId) ?: throw NotFoundException("태그")
        return tags.students(tagId, if (m.role.isManager) null else actor)
    }
}

// ───────── CLS-003 ─────────
@Service
class TimetableService(
    private val timetable: TimetablePort,
    private val access: OpsAccessPort,
    private val guard: AccessGuard,
) : TimetableUseCase, ParentTimetableUseCase {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<SlotView> {
        val m = guard.requireStaff(actor, institutionId)
        return timetable.list(institutionId, if (m.role.isManager) null else actor)
    }

    @Transactional
    override fun replace(actor: UserId, institutionId: InstitutionId, classroomId: UUID, slots: List<TimetableUseCase.SlotInput>): List<SlotView> {
        guard.requireManager(actor, institutionId)
        access.classroomName(classroomId, institutionId) ?: throw NotFoundException("반")
        if (slots.size > 35) throw InvalidInputException("TOO_MANY_SLOTS", "한 반의 시간표는 35칸까지입니다")
        for (s in slots) {
            if (s.weekday !in 1..7) throw InvalidInputException("INVALID_WEEKDAY", "요일은 1(월)~7(일)입니다")
            if (!s.startTime.isBefore(s.endTime)) throw InvalidInputException("INVALID_TIME", "시작 시각이 끝 시각보다 빨라야 합니다")
            if ((s.subject?.length ?: 0) > 50 || (s.room?.length ?: 0) > 30) throw InvalidInputException("INVALID_TEXT", "과목은 50자, 강의실은 30자까지입니다")
            if (s.teacherId != null && !timetable.isStaffOf(s.teacherId, institutionId)) throw InvalidInputException("INVALID_TEACHER", "이 기관의 교직원이 아닙니다")
        }
        // 같은 반 같은 요일에 시간이 겹치면 거절
        slots.groupBy { it.weekday }.values.forEach { day ->
            val sorted = day.sortedBy { it.startTime }
            sorted.zipWithNext().forEach { (a, b) ->
                if (a.endTime.isAfter(b.startTime)) throw ConflictException("SLOT_OVERLAP", "같은 요일에 시간이 겹치는 칸이 있습니다")
            }
        }
        timetable.replace(classroomId, slots)
        return timetable.list(institutionId, null).filter { it.classroomId == classroomId }
    }

    @Transactional(readOnly = true)
    override fun list(parent: UserId, studentIds: Set<UUID>?): List<ParentSlotView> = timetable.forParent(parent, studentIds)
}

// ───────── CLS-004 ─────────
@Service
class WaitlistService(
    private val waitlist: WaitlistPort,
    private val access: OpsAccessPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : WaitlistUseCase {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, classroomId: UUID?, status: WaitlistStatus?): List<WaitlistEntryView> {
        guard.requireManager(actor, institutionId)
        return waitlist.list(institutionId, classroomId, status)
    }

    @Transactional
    override fun add(actor: UserId, institutionId: InstitutionId, command: WaitlistUseCase.Add): WaitlistEntryView {
        guard.requireManager(actor, institutionId)
        access.classroomName(command.classroomId, institutionId) ?: throw NotFoundException("반")
        val name = command.studentName.trim()
        if (name.isEmpty() || name.length > 50) throw InvalidInputException("INVALID_NAME", "원생 이름은 1~50자입니다")
        val digits = command.phone.filter { it.isDigit() }
        if (digits.length !in 10..11 || !digits.startsWith("01")) throw InvalidInputException("INVALID_PHONE", "휴대폰 번호 형식이 아닙니다")
        if ((command.memo?.length ?: 0) > 300 || (command.guardianName?.length ?: 0) > 50) throw InvalidInputException("INVALID_TEXT", "메모는 300자, 보호자 이름은 50자까지입니다")
        val id = waitlist.insert(institutionId, command.classroomId, name, command.guardianName?.trim()?.ifEmpty { null }, digits, command.memo?.trim()?.ifEmpty { null }, actor, clock.now())
        return waitlist.find(id, institutionId) ?: throw NotFoundException("대기자")
    }

    @Transactional
    override fun setStatus(actor: UserId, institutionId: InstitutionId, id: UUID, status: WaitlistStatus): WaitlistEntryView {
        guard.requireManager(actor, institutionId)
        waitlist.find(id, institutionId) ?: throw NotFoundException("대기자")
        waitlist.setStatus(id, status, clock.now())
        return waitlist.find(id, institutionId) ?: throw NotFoundException("대기자")
    }

    @Transactional
    override fun markContacted(actor: UserId, institutionId: InstitutionId, id: UUID): WaitlistEntryView {
        guard.requireManager(actor, institutionId)
        waitlist.find(id, institutionId) ?: throw NotFoundException("대기자")
        waitlist.markContacted(id, clock.now())
        return waitlist.find(id, institutionId) ?: throw NotFoundException("대기자")
    }

    @Transactional(readOnly = true)
    override fun vacancies(actor: UserId, institutionId: InstitutionId): List<VacancyView> {
        guard.requireManager(actor, institutionId)
        return waitlist.vacancies(institutionId)
    }
}

// ───────── STF-004 ─────────
@Service
class StaffAttendanceService(
    private val attendance: StaffAttendancePort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : StaffAttendanceUseCase {
    @Transactional
    override fun checkIn(actor: UserId, institutionId: InstitutionId): StaffAttendanceView {
        guard.requireStaff(actor, institutionId)
        val today = clock.today()
        if (attendance.find(institutionId, actor, today) != null) throw ConflictException("ALREADY_CHECKED_IN", "오늘은 이미 출근 처리됐습니다")
        attendance.insert(institutionId, actor, today, clock.now())
        return attendance.find(institutionId, actor, today) ?: throw NotFoundException("출퇴근 기록")
    }

    @Transactional
    override fun checkOut(actor: UserId, institutionId: InstitutionId): StaffAttendanceView {
        guard.requireStaff(actor, institutionId)
        val today = clock.today()
        val current = attendance.find(institutionId, actor, today) ?: throw ConflictException("NOT_CHECKED_IN", "먼저 출근 처리를 해 주세요")
        if (current.checkOut != null) throw ConflictException("ALREADY_CHECKED_OUT", "오늘은 이미 퇴근 처리됐습니다")
        attendance.checkOut(current.id, clock.now())
        return attendance.find(institutionId, actor, today) ?: throw NotFoundException("출퇴근 기록")
    }

    @Transactional(readOnly = true)
    override fun today(actor: UserId, institutionId: InstitutionId): StaffAttendanceView? {
        guard.requireStaff(actor, institutionId)
        return attendance.find(institutionId, actor, clock.today())
    }

    @Transactional(readOnly = true)
    override fun monthly(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<StaffMonthlyRow> {
        val m = guard.requireStaff(actor, institutionId)
        val rows = attendance.between(institutionId, if (m.role.isManager) null else actor, month.atDay(1), month.atEndOfMonth())
        return rows.groupBy { it.userId }.map { (uid, list) ->
            StaffMonthlyRow(uid, list.first().userName, list.size, list.sumOf { it.workedMinutes ?: 0 }, list.sortedBy { it.workDate })
        }.sortedBy { it.userName }
    }
}

// ───────── EVT-002 · EVT-006 ─────────
private const val MAX_EVENT_TEMPLATES = 50
private const val MAX_GENERATE = 31

@Service
class EventTemplateService(
    private val templates: EventTemplatePort,
    private val calendar: EventCalendarPort,
    private val events: ManageEventUseCase,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : EventTemplateUseCase, EventCalendarUseCase {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<EventTemplateView> {
        guard.requireStaff(actor, institutionId)
        return templates.list(institutionId)
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: EventTemplateUseCase.Command): EventTemplateView {
        guard.requireStaff(actor, institutionId)
        validate(command)
        if (templates.count(institutionId) >= MAX_EVENT_TEMPLATES) throw ConflictException("TEMPLATE_LIMIT", "행사 템플릿은 ${MAX_EVENT_TEMPLATES}개까지 만들 수 있습니다")
        val id = templates.insert(institutionId, actor, command, clock.now())
        return templates.find(id, institutionId) ?: throw NotFoundException("행사 템플릿")
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: EventTemplateUseCase.Command): EventTemplateView {
        guard.requireStaff(actor, institutionId)
        templates.find(id, institutionId) ?: throw NotFoundException("행사 템플릿")
        validate(command)
        templates.update(id, command, clock.now())
        return templates.find(id, institutionId) ?: throw NotFoundException("행사 템플릿")
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: UUID) {
        guard.requireStaff(actor, institutionId)
        templates.find(id, institutionId) ?: throw NotFoundException("행사 템플릿")
        templates.delete(id)
    }

    /** 대상 권한(교사는 담당 반만, 전체는 원장·실장만)은 행사 생성이 검사한다 */
    @Transactional
    override fun generate(actor: UserId, institutionId: InstitutionId, id: UUID, dates: List<LocalDate>): List<UUID> {
        guard.requireStaff(actor, institutionId)
        val t = templates.find(id, institutionId) ?: throw NotFoundException("행사 템플릿")
        val days = dates.distinct().sorted()
        if (days.isEmpty()) throw InvalidInputException("NO_DATES", "만들 날짜를 골라 주세요")
        if (days.size > MAX_GENERATE) throw InvalidInputException("TOO_MANY_DATES", "한 번에 ${MAX_GENERATE}개까지 만들 수 있습니다")
        if (days.first().isBefore(clock.today())) throw InvalidInputException("PAST_DATE", "지난 날짜로는 만들 수 없습니다")
        val targets = t.targets.map {
            when (it.scope) {
                "ALL" -> NoticeTarget.all()
                "CLASS" -> NoticeTarget.classroom(ClassroomId(it.id ?: throw InvalidInputException("INVALID_TARGET", "반 id가 필요합니다")))
                "STUDENT" -> NoticeTarget.student(StudentId(it.id ?: throw InvalidInputException("INVALID_TARGET", "원생 id가 필요합니다")))
                else -> throw InvalidInputException("INVALID_TARGET", "알 수 없는 대상입니다")
            }
        }
        val zone = clock.zone()
        return days.map { day ->
            val startsAt = day.atTime(t.startTime).atZone(zone).toInstant()
            val created = events.create(
                ManageEventUseCase.CreateCommand(
                    actor, institutionId, t.title, t.body, t.location, startsAt, startsAt.plusSeconds(t.durationMinutes * 60L),
                    targets, t.rsvpEnabled, t.rsvpDeadlineDaysBefore?.let { startsAt.minusSeconds(it * 86_400L) }, t.reminderHoursBefore,
                ),
            )
            created.id.value
        }
    }

    @Transactional(readOnly = true)
    override fun month(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<CalendarEvent> {
        guard.requireStaff(actor, institutionId)
        val zone = clock.zone()
        return calendar.between(institutionId, month.atDay(1).atStartOfDay(zone).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
    }

    private fun validate(c: EventTemplateUseCase.Command) {
        if (c.name.isBlank() || c.name.trim().length > 50) throw InvalidInputException("INVALID_NAME", "템플릿 이름은 1~50자입니다")
        if (c.title.isBlank() || c.title.trim().length > 100) throw InvalidInputException("INVALID_TITLE", "행사명은 1~100자입니다")
        if ((c.body?.length ?: 0) > 5000 || (c.location?.length ?: 0) > 200) throw InvalidInputException("INVALID_TEXT", "내용은 5000자, 장소는 200자까지입니다")
        if (c.durationMinutes !in 5..1440) throw InvalidInputException("INVALID_DURATION", "진행 시간은 5분~24시간입니다")
        if (c.targets.isEmpty() || c.targets.size > 200) throw InvalidInputException("INVALID_TARGET", "대상을 1~200개 지정해 주세요")
        if (c.rsvpDeadlineDaysBefore != null && c.rsvpDeadlineDaysBefore !in 0..60) throw InvalidInputException("INVALID_DEADLINE", "응답 마감은 행사 0~60일 전입니다")
    }
}
