package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.CurriculumUseCase
import com.ttokttok.application.port.`in`.EventAlbumLink
import com.ttokttok.application.port.`in`.EventReviewView
import com.ttokttok.application.port.`in`.MissingAlertPolicy
import com.ttokttok.application.port.`in`.MissingAlertView
import com.ttokttok.application.port.`in`.ProgressView
import com.ttokttok.application.port.`in`.ReadRankRow
import com.ttokttok.application.port.`in`.StaffWorkRow
import com.ttokttok.application.port.`in`.TextbookView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ───────── ATT-010 ─────────
data class MissingCandidate(
    val attendanceDayId: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val childId: UUID?,
    val classroomId: UUID,
    val classroomName: String,
    val classStart: Instant,
    val minutes: Int,
    val notifyParent: Boolean,
    /** 지각 사전 신청의 예상 등원 시각 (있으면 그 시각 기준으로 늦춘다) */
    val lateExpected: LocalTime?,
)

interface MissingAlertPort {
    fun policy(institutionId: InstitutionId): MissingAlertPolicy
    fun setPolicy(institutionId: InstitutionId, policy: MissingAlertPolicy)
    /** 오늘 SCHEDULED 그대로이고 (수업 시작 + 기준 분) 이 [windowStart, now] 사이인, 아직 경고 안 한 출결. 결석 사전 신청 제외 */
    fun candidates(today: LocalDate, now: Instant, windowStart: Instant): List<MissingCandidate>
    /** 같은 아이가 오늘 다른 기관에서 마지막으로 하원한 기록 */
    fun previousCheckout(childId: UUID, excludeInstitution: UUID, date: LocalDate, before: Instant): Pair<String, Instant>?
    /** 처음이면 true */
    fun record(c: MissingCandidate, previous: Pair<String, Instant>?, notifiedParent: Boolean, now: Instant): Boolean
    fun list(institutionId: InstitutionId, date: LocalDate, classroomIds: Collection<UUID>?): List<MissingAlertView>
    /** 원장·실장 + 그 반 담당 교사 */
    fun staffRecipients(institutionId: UUID, classroomId: UUID): List<UserId>
    fun guardians(studentId: UUID): List<UserId>
}

// ───────── STAT-007 · STF-005 ─────────
data class WorkStaff(val userId: UUID, val name: String, val role: String)
data class ReadAgg(val targets: Int, val read: Int)

interface WorkAnalyticsPort {
    fun readByClassroom(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow>
    fun readByAuthor(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow>
    fun readOverall(institutionId: InstitutionId, from: Instant, to: Instant): ReadAgg

    fun staff(institutionId: InstitutionId): List<WorkStaff>
    fun noticesSent(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int>
    fun repliesSent(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int>
    fun avgReplyMinutes(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Double>
    fun unansweredMessages(institutionId: InstitutionId, from: Instant, to: Instant): Int
    fun attendanceActions(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int>
    fun avgCheckInOffset(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Double>
    fun consultsDone(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int>
}

// ───────── CLS-005 ─────────
interface CurriculumPort {
    fun textbooks(classroomId: UUID, includeArchived: Boolean): List<TextbookView>
    fun findTextbook(id: UUID): Pair<UUID, TextbookView>?   // (institutionId, view)
    fun insertTextbook(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveTextbook, now: Instant): UUID
    fun updateTextbook(id: UUID, command: CurriculumUseCase.SaveTextbook)
    fun progress(classroomIds: Collection<UUID>, from: LocalDate?, to: LocalDate?, limit: Int): List<ProgressView>
    fun findProgress(id: UUID): Pair<UUID, ProgressView>?   // (institutionId, view)
    fun insertProgress(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveProgress, author: UserId, now: Instant): UUID
    fun updateProgress(id: UUID, command: CurriculumUseCase.SaveProgress, now: Instant)
    fun deleteProgress(id: UUID)
}

// ───────── EVT-009 ─────────
data class EventBrief(val id: UUID, val institutionId: UUID, val title: String, val startsAt: Instant)

interface EventFollowUpPort {
    fun event(id: UUID): EventBrief?
    fun albums(eventId: UUID, visibleToStudents: Collection<UUID>?): List<EventAlbumLink>
    fun albumInstitution(albumId: UUID): UUID?
    fun setAlbumEvent(albumId: UUID, eventId: UUID?)
    fun targetStudents(eventId: UUID): Set<UUID>
    fun reviews(eventId: UUID, viewer: UserId?, includeHidden: Boolean): List<EventReviewView>
    fun findReview(id: UUID): Pair<UUID, EventReviewView>?  // (eventId, view)
    fun upsertReview(eventId: UUID, studentId: UUID, user: UserId, body: String, now: Instant)
    fun setHidden(reviewId: UUID, hidden: Boolean, now: Instant)
    fun deleteReview(eventId: UUID, user: UserId)
}
