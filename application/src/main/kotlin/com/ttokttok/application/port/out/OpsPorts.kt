package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.CalendarEvent
import com.ttokttok.application.port.`in`.CounselNoteView
import com.ttokttok.application.port.`in`.EventTemplateUseCase
import com.ttokttok.application.port.`in`.EventTemplateView
import com.ttokttok.application.port.`in`.HealthAlert
import com.ttokttok.application.port.`in`.HealthView
import com.ttokttok.application.port.`in`.ParentSlotView
import com.ttokttok.application.port.`in`.SlotView
import com.ttokttok.application.port.`in`.StaffAttendanceView
import com.ttokttok.application.port.`in`.TagView
import com.ttokttok.application.port.`in`.TaggedStudent
import com.ttokttok.application.port.`in`.TimetableUseCase
import com.ttokttok.application.port.`in`.VacancyView
import com.ttokttok.application.port.`in`.WaitlistEntryView
import com.ttokttok.application.port.`in`.WaitlistStatus
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Phase 2 ③ 공통: 원생·반이 이 기관 것인지, 교사의 담당 범위인지 */
interface OpsAccessPort {
    fun studentName(studentId: UUID, institutionId: InstitutionId): String?
    /** 현재 재원 중인 반 중 이 교사가 담당하는 반에 속한 원생인가 */
    fun teacherCanSee(teacher: UserId, studentId: UUID): Boolean
    fun classroomName(classroomId: UUID, institutionId: InstitutionId): String?
    fun teacherOfClassroom(teacher: UserId, classroomId: UUID): Boolean
}

interface StudentHealthPort {
    fun find(studentId: UUID): HealthView?
    fun upsert(studentId: UUID, allergies: String?, conditions: String?, notes: String?, actor: UserId, now: Instant)
    fun alerts(classroomId: UUID): List<HealthAlert>
}

interface CounselNotePort {
    fun list(studentId: UUID): List<CounselNoteView>
    fun find(id: UUID, institutionId: InstitutionId): CounselNoteView?
    fun insert(institutionId: InstitutionId, studentId: UUID, author: UserId, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant): UUID
    fun update(id: UUID, occurredOn: LocalDate, body: String, parentVisible: Boolean, now: Instant)
    fun delete(id: UUID)
    fun listForParent(parent: UserId, studentIds: Set<UUID>?): List<CounselNoteView>
}

interface StudentTagPort {
    fun list(institutionId: InstitutionId): List<TagView>
    fun find(id: UUID, institutionId: InstitutionId): TagView?
    fun nameExists(institutionId: InstitutionId, name: String): Boolean
    fun insert(institutionId: InstitutionId, name: String, color: String?, now: Instant): UUID
    fun delete(id: UUID)
    fun tagsOf(studentId: UUID): List<TagView>
    fun setTags(studentId: UUID, tagIds: List<UUID>)
    /** teacher != null 이면 그 교사의 담당 반 원생만 */
    fun students(tagId: UUID, teacher: UserId?): List<TaggedStudent>
    fun count(institutionId: InstitutionId): Int
}

interface TimetablePort {
    /** teacher != null 이면 담당 반만 */
    fun list(institutionId: InstitutionId, teacher: UserId?): List<SlotView>
    fun replace(classroomId: UUID, slots: List<TimetableUseCase.SlotInput>)
    fun isStaffOf(userId: UUID, institutionId: InstitutionId): Boolean
    fun forParent(parent: UserId, studentIds: Set<UUID>?): List<ParentSlotView>
}

interface WaitlistPort {
    fun list(institutionId: InstitutionId, classroomId: UUID?, status: WaitlistStatus?): List<WaitlistEntryView>
    fun find(id: UUID, institutionId: InstitutionId): WaitlistEntryView?
    fun insert(institutionId: InstitutionId, classroomId: UUID, studentName: String, guardianName: String?, phone: String, memo: String?, creator: UserId, now: Instant): UUID
    fun setStatus(id: UUID, status: WaitlistStatus, now: Instant)
    fun markContacted(id: UUID, now: Instant)
    fun vacancies(institutionId: InstitutionId): List<VacancyView>
    fun waitingCount(classroomId: UUID): Int
}

interface StaffAttendancePort {
    fun find(institutionId: InstitutionId, userId: UserId, date: LocalDate): StaffAttendanceView?
    fun insert(institutionId: InstitutionId, userId: UserId, date: LocalDate, checkIn: Instant): UUID
    fun checkOut(id: UUID, at: Instant)
    /** user != null 이면 그 사람만 */
    fun between(institutionId: InstitutionId, user: UserId?, from: LocalDate, to: LocalDate): List<StaffAttendanceView>
}

interface EventTemplatePort {
    fun list(institutionId: InstitutionId): List<EventTemplateView>
    fun find(id: UUID, institutionId: InstitutionId): EventTemplateView?
    fun insert(institutionId: InstitutionId, creator: UserId, command: EventTemplateUseCase.Command, now: Instant): UUID
    fun update(id: UUID, command: EventTemplateUseCase.Command, now: Instant)
    fun delete(id: UUID)
    fun count(institutionId: InstitutionId): Int
}

interface EventCalendarPort {
    fun between(institutionId: InstitutionId, from: Instant, to: Instant): List<CalendarEvent>
}
