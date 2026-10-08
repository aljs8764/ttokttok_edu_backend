package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

// ───────── STU-009 건강/알레르기 특이사항 ─────────
data class HealthView(
    val studentId: UUID,
    val allergies: String?,
    val conditions: String?,
    val notes: String?,
    val updatedByName: String?,
    val updatedAt: Instant?,
)

/** 교사 앱의 경고 배지용: 알레르기가 적힌 원생만 */
data class HealthAlert(val studentId: UUID, val studentName: String, val allergies: String)

/** 원장·실장은 전 원생, 교사는 담당 반 원생만. 저장 전 암호화 */
interface StudentHealthUseCase {
    fun get(actor: UserId, institutionId: InstitutionId, studentId: UUID): HealthView
    fun save(actor: UserId, institutionId: InstitutionId, studentId: UUID, command: Save): HealthView
    fun alerts(actor: UserId, institutionId: InstitutionId, classroomId: UUID): List<HealthAlert>
    data class Save(val allergies: String?, val conditions: String?, val notes: String?)
}

// ───────── STU-010 상담일지 ─────────
data class CounselNoteView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val authorId: UUID,
    val authorName: String,
    val occurredOn: LocalDate,
    val body: String,
    val parentVisible: Boolean,
    val createdAt: Instant,
)

interface CounselNoteUseCase {
    fun list(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<CounselNoteView>
    fun create(actor: UserId, institutionId: InstitutionId, studentId: UUID, command: Command): CounselNoteView
    /** 쓴 사람 또는 원장·실장 */
    fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: Command): CounselNoteView
    fun delete(actor: UserId, institutionId: InstitutionId, id: UUID)
    data class Command(val occurredOn: LocalDate, val body: String, val parentVisible: Boolean)
}

/** 학부모 앱: 공개로 표시된 상담일지만 */
interface ParentCounselUseCase {
    fun list(parent: UserId, studentIds: Set<UUID>?): List<CounselNoteView>
}

// ───────── STU-011 태그/그룹 ─────────
data class TagView(val id: UUID, val name: String, val color: String?, val studentCount: Int)
data class TaggedStudent(val studentId: UUID, val name: String)

interface StudentTagUseCase {
    fun list(actor: UserId, institutionId: InstitutionId): List<TagView>
    fun create(actor: UserId, institutionId: InstitutionId, name: String, color: String?): TagView
    fun delete(actor: UserId, institutionId: InstitutionId, id: UUID)
    fun studentTags(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<TagView>
    fun setStudentTags(actor: UserId, institutionId: InstitutionId, studentId: UUID, tagIds: List<UUID>): List<TagView>
    /** 태그가 붙은 원생 (교사는 담당 반 원생만). 알림장·행사 대상 고르기에 쓴다 */
    fun students(actor: UserId, institutionId: InstitutionId, tagId: UUID): List<TaggedStudent>
}

// ───────── CLS-003 주간 시간표 ─────────
data class SlotView(
    val id: UUID,
    val classroomId: UUID,
    val classroomName: String,
    val weekday: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val subject: String?,
    val room: String?,
    val teacherId: UUID?,
    val teacherName: String?,
)

data class ParentSlotView(val studentId: UUID, val studentName: String, val institutionName: String, val slot: SlotView)

interface TimetableUseCase {
    /** 원장·실장은 전 반, 교사는 담당 반 */
    fun list(actor: UserId, institutionId: InstitutionId): List<SlotView>
    /** 한 반의 칸을 통째로 바꾼다 (원장·실장) */
    fun replace(actor: UserId, institutionId: InstitutionId, classroomId: UUID, slots: List<SlotInput>): List<SlotView>
    data class SlotInput(val weekday: Int, val startTime: LocalTime, val endTime: LocalTime, val subject: String?, val room: String?, val teacherId: UUID?)
}

interface ParentTimetableUseCase {
    fun list(parent: UserId, studentIds: Set<UUID>?): List<ParentSlotView>
}

// ───────── CLS-004 대기자 명단 ─────────
enum class WaitlistStatus { WAITING, ENROLLED, CANCELLED }

data class WaitlistEntryView(
    val id: UUID,
    val classroomId: UUID,
    val classroomName: String,
    val studentName: String,
    val guardianName: String?,
    val phone: String,
    val memo: String?,
    val status: WaitlistStatus,
    val contactedAt: Instant?,
    val createdAt: Instant,
    /** 같은 반 대기 순번 (대기 중일 때만) */
    val position: Int?,
)

data class VacancyView(val classroomId: UUID, val classroomName: String, val capacity: Int, val enrolled: Int, val waiting: Int) {
    val vacancy: Int get() = (capacity - enrolled).coerceAtLeast(0)
}

interface WaitlistUseCase {
    fun list(actor: UserId, institutionId: InstitutionId, classroomId: UUID?, status: WaitlistStatus?): List<WaitlistEntryView>
    fun add(actor: UserId, institutionId: InstitutionId, command: Add): WaitlistEntryView
    fun setStatus(actor: UserId, institutionId: InstitutionId, id: UUID, status: WaitlistStatus): WaitlistEntryView
    /** "연락함" 표시 */
    fun markContacted(actor: UserId, institutionId: InstitutionId, id: UUID): WaitlistEntryView
    /** 반별 정원·재원·대기 — 자리가 난 반과 순서대로 연락할 대기자를 찾는다 */
    fun vacancies(actor: UserId, institutionId: InstitutionId): List<VacancyView>
    data class Add(val classroomId: UUID, val studentName: String, val guardianName: String?, val phone: String, val memo: String?)
}

// ───────── STF-004 교사 출퇴근 ─────────
data class StaffAttendanceView(
    val id: UUID,
    val userId: UUID,
    val userName: String,
    val workDate: LocalDate,
    val checkIn: Instant,
    val checkOut: Instant?,
) {
    val workedMinutes: Int? get() = checkOut?.let { java.time.Duration.between(checkIn, it).toMinutes().toInt() }
}

data class StaffMonthlyRow(val userId: UUID, val userName: String, val days: Int, val totalMinutes: Int, val records: List<StaffAttendanceView>)

interface StaffAttendanceUseCase {
    fun checkIn(actor: UserId, institutionId: InstitutionId): StaffAttendanceView
    fun checkOut(actor: UserId, institutionId: InstitutionId): StaffAttendanceView
    fun today(actor: UserId, institutionId: InstitutionId): StaffAttendanceView?
    /** 원장·실장은 전 직원, 교사는 본인만 */
    fun monthly(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<StaffMonthlyRow>
}

// ───────── EVT-002 반복 행사 템플릿 · EVT-006 월간 행사 달력 ─────────
data class TemplateTarget(val scope: String, val id: UUID?)

data class EventTemplateView(
    val id: UUID,
    val name: String,
    val title: String,
    val body: String?,
    val location: String?,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val targets: List<TemplateTarget>,
    val rsvpEnabled: Boolean,
    val rsvpDeadlineDaysBefore: Int?,
    val reminderHoursBefore: Int?,
    val createdByName: String,
    val updatedAt: Instant,
)

interface EventTemplateUseCase {
    fun list(actor: UserId, institutionId: InstitutionId): List<EventTemplateView>
    fun create(actor: UserId, institutionId: InstitutionId, command: Command): EventTemplateView
    fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: Command): EventTemplateView
    fun delete(actor: UserId, institutionId: InstitutionId, id: UUID)
    /** 고른 날짜마다 행사를 만든다 (대상 권한은 행사 생성과 같은 규칙). 만든 행사 id 를 돌려준다 */
    fun generate(actor: UserId, institutionId: InstitutionId, id: UUID, dates: List<LocalDate>): List<UUID>
    data class Command(
        val name: String,
        val title: String,
        val body: String?,
        val location: String?,
        val startTime: LocalTime,
        val durationMinutes: Int,
        val targets: List<TemplateTarget>,
        val rsvpEnabled: Boolean,
        val rsvpDeadlineDaysBefore: Int?,
        val reminderHoursBefore: Int?,
    )
}

data class CalendarEvent(
    val id: UUID,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val location: String?,
    val status: String,
    val rsvpEnabled: Boolean,
)

interface EventCalendarUseCase {
    fun month(actor: UserId, institutionId: InstitutionId, month: YearMonth): List<CalendarEvent>
}
