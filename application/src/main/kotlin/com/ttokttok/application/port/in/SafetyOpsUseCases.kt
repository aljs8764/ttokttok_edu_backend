package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Phase 3 P3-① 안전·운영 고도화

// ───────── ATT-010 미등원 초과 경고 ─────────
data class MissingAlertPolicy(val minutes: Int, val notifyParent: Boolean)

data class MissingAlertView(
    val attendanceDayId: UUID,
    val studentId: UUID,
    val studentName: String,
    val classroomId: UUID,
    val classroomName: String,
    val classStart: String,
    val previousInstitutionName: String?,
    val previousCheckoutAt: Instant?,
    val notifiedParent: Boolean,
    val sentAt: Instant,
    /** 지금 출결 상태 (경고 뒤에 등원했으면 IN/OUT) */
    val currentStatus: String,
)

interface MissingAlertUseCase {
    fun policy(actor: UserId, institutionId: InstitutionId): MissingAlertPolicy
    /** 원장·실장. minutes 0 = 끔, 10~180 */
    fun setPolicy(actor: UserId, institutionId: InstitutionId, policy: MissingAlertPolicy): MissingAlertPolicy
    /** 교사는 담당 반만 */
    fun alerts(actor: UserId, institutionId: InstitutionId, date: LocalDate): List<MissingAlertView>
    /** 스케줄러(5분): 경고 대상 찾아 알림. 보낸 건수 */
    fun scan(now: Instant): Int
}

// ───────── STAT-007 알림장 열람률 랭킹 · STF-005 교사 업무 리포트 ─────────
data class ReadRankRow(val id: UUID, val name: String, val notices: Int, val targets: Int, val read: Int, val rate: Double?, val weak: Boolean)
data class NoticeReadRanking(val from: LocalDate, val to: LocalDate, val institutionRate: Double?, val classrooms: List<ReadRankRow>, val teachers: List<ReadRankRow>)

data class StaffWorkRow(
    val userId: UUID,
    val name: String,
    val role: String,
    val noticesSent: Int,
    val noticeReadRate: Double?,
    val repliesSent: Int,
    /** 학부모 메시지 → 그 대화의 첫 교직원 답장까지 평균(분). 답한 것만 */
    val avgReplyMinutes: Double?,
    val attendanceActions: Int,
    /** 교사·관리자가 처리한 등원(CHECK_IN)이 수업 시작 기준 몇 분에 기록됐는지 평균 (음수 = 시작 전) */
    val avgCheckInOffsetMinutes: Double?,
    val consultsDone: Int,
)
data class StaffWorkReport(
    val from: LocalDate,
    val to: LocalDate,
    val staff: List<StaffWorkRow>,
    /** 기간 안 학부모 메시지 중 아직 교직원 답장이 없는 수 (기관 전체) */
    val unansweredMessages: Int,
)

interface WorkAnalyticsQuery {
    fun noticeRanking(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): NoticeReadRanking
    fun staffWork(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): StaffWorkReport
}

// ───────── CLS-005 교재·진도 ─────────
data class TextbookView(val id: UUID, val classroomId: UUID, val name: String, val publisher: String?, val memo: String?, val archived: Boolean)
data class ProgressView(
    val id: UUID, val classroomId: UUID, val classroomName: String, val date: LocalDate, val textbookId: UUID?, val textbookName: String?,
    val content: String, val homework: String?, val authorName: String, val updatedAt: Instant,
)

interface CurriculumUseCase {
    data class SaveTextbook(val name: String, val publisher: String?, val memo: String?, val archived: Boolean)
    data class SaveProgress(val date: LocalDate, val textbookId: UUID?, val content: String, val homework: String?)

    /** 교직원 (교사는 담당 반) */
    fun textbooks(actor: UserId, institutionId: InstitutionId, classroomId: UUID, includeArchived: Boolean): List<TextbookView>
    fun addTextbook(actor: UserId, institutionId: InstitutionId, classroomId: UUID, command: SaveTextbook): TextbookView
    fun updateTextbook(actor: UserId, institutionId: InstitutionId, textbookId: UUID, command: SaveTextbook): TextbookView
    fun progress(actor: UserId, institutionId: InstitutionId, classroomId: UUID, from: LocalDate?, to: LocalDate?): List<ProgressView>
    fun addProgress(actor: UserId, institutionId: InstitutionId, classroomId: UUID, command: SaveProgress): ProgressView
    fun updateProgress(actor: UserId, institutionId: InstitutionId, progressId: UUID, command: SaveProgress): ProgressView
    fun deleteProgress(actor: UserId, institutionId: InstitutionId, progressId: UUID)
    /** 알림장 작성 시 인용할 문구: 반별 가장 최근(또는 그날) 진도·숙제 */
    fun quote(actor: UserId, institutionId: InstitutionId, classroomIds: List<UUID>, date: LocalDate?): String
}

// ───────── EVT-009 행사 후기·사진 ─────────
data class EventReviewView(val id: UUID, val eventId: UUID, val studentName: String, val authorName: String, val body: String, val hidden: Boolean, val mine: Boolean, val createdAt: Instant)
data class EventAlbumLink(val albumId: UUID, val title: String, val itemCount: Int, val createdAt: Instant)

interface EventFollowUpUseCase {
    // 교직원
    fun albums(actor: UserId, institutionId: InstitutionId, eventId: UUID): List<EventAlbumLink>
    fun linkAlbum(actor: UserId, institutionId: InstitutionId, eventId: UUID, albumId: UUID): List<EventAlbumLink>
    fun unlinkAlbum(actor: UserId, institutionId: InstitutionId, eventId: UUID, albumId: UUID): List<EventAlbumLink>
    fun reviews(actor: UserId, institutionId: InstitutionId, eventId: UUID): List<EventReviewView>
    fun hideReview(actor: UserId, institutionId: InstitutionId, reviewId: UUID, hidden: Boolean): EventReviewView

    // 학부모 (행사 대상 아이의 보호자, 행사 시작 뒤부터)
    fun myAlbums(parent: UserId, eventId: UUID): List<EventAlbumLink>
    fun myReviews(parent: UserId, eventId: UUID): List<EventReviewView>
    fun writeReview(parent: UserId, eventId: UUID, studentId: UUID, body: String): EventReviewView
    fun deleteMyReview(parent: UserId, eventId: UUID)
}
