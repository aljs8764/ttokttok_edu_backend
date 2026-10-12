package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.ConsultToday
import com.ttokttok.application.port.`in`.DashboardWidget
import com.ttokttok.application.port.`in`.EventFollowUp
import com.ttokttok.application.port.`in`.InboxItem
import com.ttokttok.application.port.`in`.InstitutionCard
import com.ttokttok.application.port.`in`.NoticeFollowUp
import com.ttokttok.application.port.`in`.OwnerBilling
import com.ttokttok.application.port.`in`.OwnerClassRow
import com.ttokttok.application.port.`in`.OwnerKpi
import com.ttokttok.application.port.`in`.OwnerStaff
import com.ttokttok.application.port.`in`.ProgressMissing
import com.ttokttok.application.port.`in`.StaffMemoAck
import com.ttokttok.application.port.`in`.StaffMemoView
import com.ttokttok.application.port.`in`.StudentCaution
import com.ttokttok.application.port.`in`.TeacherClassToday
import com.ttokttok.application.port.`in`.TeacherWork
import com.ttokttok.application.port.`in`.TodoAbsence
import com.ttokttok.application.port.`in`.TodoStudent
import com.ttokttok.application.port.`in`.UnansweredConversation
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** DASH-013 이탈 위험 판정 재료 (재원 원생마다) */
data class ChurnSignal(
    val studentId: UUID,
    val studentName: String,
    val classroomNames: List<String>,
    /** 최근 28일 / 그 전 28일 출결 대상·결석 수 */
    val scheduledRecent: Int,
    val absentRecent: Int,
    val scheduledBefore: Int,
    val absentBefore: Int,
    /** 최근 30일 받은 알림장 수 / 보호자 누구도 안 읽은 수 */
    val noticesRecent: Int,
    val noticesUnread: Int,
    /** 최근 90일 안에 휴원했다가 돌아옴 */
    val pausedRecently: Boolean,
    val overdueInvoices: Int,
    val lastCounselOn: LocalDate?,
    /** 최근 60일 상담일지에 퇴원·그만·이동 등 언급 */
    val counselMentionsWithdraw: Boolean,
)

/** 원장·교사 대시보드 집계 (읽기 전용) */
interface RoleDashboardPort {
    // 원장
    fun ownerKpi(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerKpi
    /** flags 는 비워서 돌려준다 (서비스가 채움) */
    fun ownerClasses(institutionId: InstitutionId, today: LocalDate, now: Instant): List<OwnerClassRow>
    fun inbox(institutionId: InstitutionId, today: LocalDate, now: Instant): List<InboxItem>
    fun billing(institutionId: InstitutionId, today: LocalDate): OwnerBilling
    fun churnSignals(institutionId: InstitutionId, today: LocalDate): List<ChurnSignal>
    fun staffToday(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerStaff
    fun institutionCards(userId: UserId, today: LocalDate, now: Instant): List<InstitutionCard>

    // 교사 (teacherId 가 담당한 반 기준)
    fun teacherName(teacherId: UUID): String?
    fun teacherClasses(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TeacherClassToday>
    /** 수업 시작 + 지각 기준이 지났는데 SCHEDULED */
    fun notArrived(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent>
    /** 수업 종료 + 30분이 지났는데 IN */
    fun notCheckedOut(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent>
    fun pendingAbsences(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<TodoAbsence>
    fun cautions(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<StudentCaution>
    fun unansweredConversations(institutionId: InstitutionId, teacherId: UUID, limit: Int): List<UnansweredConversation>
    fun noticeFollowUps(institutionId: InstitutionId, teacherId: UUID, since: Instant): List<NoticeFollowUp>
    fun eventFollowUps(institutionId: InstitutionId, teacherId: UUID, now: Instant, until: Instant): List<EventFollowUp>
    fun consultsToday(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<ConsultToday>
    fun progressMissing(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<ProgressMissing>
    fun work(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, weekStart: LocalDate): TeacherWork
}

interface StaffMemoPort {
    fun insert(institutionId: InstitutionId, author: UserId, title: String, body: String?, dueDate: LocalDate?, targets: List<UUID>, now: Instant): UUID
    /** 작성자 집계 포함 */
    fun list(institutionId: InstitutionId, includeCanceled: Boolean): List<StaffMemoView>
    fun find(institutionId: InstitutionId, memoId: UUID): StaffMemoView?
    fun acks(institutionId: InstitutionId, memoId: UUID): List<StaffMemoAck>
    fun cancel(memoId: UUID, now: Instant)
    /** 받는 사람 기준 (취소 제외) */
    fun mine(institutionId: InstitutionId, user: UserId, includeDone: Boolean): List<StaffMemoView>
    fun isRecipient(memoId: UUID, user: UserId): Boolean
    fun ack(memoId: UUID, user: UserId, done: Boolean?, now: Instant)
    /** 받는 교직원 user id (to_all 이면 기관 교직원 전원, 작성자 제외) */
    fun recipients(institutionId: InstitutionId, memoId: UUID): List<UserId>
    fun staffIds(institutionId: InstitutionId): Set<UUID>
}

interface DashboardLayoutPort {
    fun find(user: UserId, kind: String): List<DashboardWidget>?
    fun save(user: UserId, kind: String, widgets: List<DashboardWidget>, now: Instant)
    fun delete(user: UserId, kind: String)
}
