package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Phase 3 P3-⑥ 역할별 대시보드 (DASH-008~023)
// 원장 대시보드 = 원장·실장(OWNER·ADMIN), 교사 대시보드 = 교직원 누구나(자기가 담당한 반 기준).
// 금액·비율: 비율은 0~1, 금액은 원.

// ───────── 원장 대시보드 ─────────

/** DASH-009 경영 KPI */
data class OwnerKpi(
    val asOf: Instant,
    val activeStudents: Int,
    /** 지난달 말 재원생 수 (현재 − 이번 달 신규 + 이번 달 퇴원으로 근사) */
    val activeStudentsLastMonth: Int,
    val newThisMonth: Int,
    val withdrawnThisMonth: Int,
    val capacity: Int,
    /** 반 배정 인원 / 정원 합 (정원 0 이면 null) */
    val fillRate: Double?,
    /** 이번 달 납부기한 청구(취소 제외) */
    val billedThisMonth: Long,
    val paidThisMonth: Long,
    val collectionRate: Double?,
    /** 미납 전체 (기한 무관) */
    val unpaidAmount: Long,
    val unpaidCount: Int,
    val todayScheduled: Int,
    val todayPresent: Int,
    val todayAttendanceRate: Double?,
)

/** DASH-010 반별 운영 현황 */
data class OwnerClassRow(
    val classroomId: UUID,
    val name: String,
    val teachers: List<String>,
    val heldToday: Boolean,
    val startTime: String,
    val endTime: String,
    val scheduled: Int,
    val present: Int,
    val absent: Int,
    val notArrived: Int,
    val capacity: Int,
    val headcount: Int,
    val waiting: Int,
    /** 최근 7일 이 반 원생에게 간 알림장 열람률 */
    val readRate7d: Double?,
    val missingAlertsToday: Int,
    /** FULL(정원 참) · MISSING(미등원 경고) · LOW_READ(열람률 평균 −10%p) · NO_TEACHER */
    val flags: List<String>,
)

/** DASH-011 처리 대기함 항목 */
data class InboxItem(val key: String, val count: Int, val oldestAt: Instant?)

/** DASH-012 수납·미납 */
data class OwnerBilling(
    val unpaidCount: Int,
    val unpaidAmount: Long,
    val overdueCount: Int,
    val overdueAmount: Long,
    val dueSoon: List<InvoiceBrief>,
    val recentPaid: List<InvoiceBrief>,
)
data class InvoiceBrief(val id: UUID, val studentName: String, val title: String, val amount: Long, val dueDate: LocalDate, val paidOn: LocalDate?)

/** DASH-013 이탈 위험 */
data class ChurnRisk(
    val studentId: UUID,
    val studentName: String,
    val classroomNames: List<String>,
    val score: Int,
    /** ABSENCE_UP · UNREAD · PAUSED_BEFORE · OVERDUE · COUNSEL_WITHDRAW */
    val reasons: List<String>,
    val absentRecent: Int,
    val absentBefore: Int,
    val lastCounselOn: LocalDate?,
)

/** DASH-014 교직원 현황 */
data class StaffTodayRow(
    val userId: UUID,
    val name: String,
    val role: String,
    val checkInAt: Instant?,
    val checkOutAt: Instant?,
    val classesToday: List<String>,
    /** 담당 반 원생 보호자가 보낸, 아직 안 읽은 메시지 수 */
    val unansweredMessages: Int,
)
data class CoverNeeded(val classroomId: UUID, val classroomName: String, val startTime: String, val teachers: List<String>)
data class OwnerStaff(val staff: List<StaffTodayRow>, val coverNeeded: List<CoverNeeded>)

/** DASH-016 지점 비교 */
data class InstitutionCard(
    val institutionId: UUID,
    val name: String,
    val role: String,
    val activeStudents: Int,
    val todayAttendanceRate: Double?,
    val collectionRate: Double?,
    val pending: Int,
)

interface OwnerDashboardQuery {
    fun kpi(actor: UserId, institutionId: InstitutionId): OwnerKpi
    fun classes(actor: UserId, institutionId: InstitutionId): List<OwnerClassRow>
    fun inbox(actor: UserId, institutionId: InstitutionId): List<InboxItem>
    fun billing(actor: UserId, institutionId: InstitutionId): OwnerBilling
    fun churnRisks(actor: UserId, institutionId: InstitutionId, limit: Int): List<ChurnRisk>
    fun staff(actor: UserId, institutionId: InstitutionId): OwnerStaff
    /** 원장·실장으로 소속된 모든 기관 (기관 헤더 불필요) */
    fun institutions(actor: UserId): List<InstitutionCard>
}

// ───────── 교사 대시보드 ─────────

data class TeacherClassToday(
    val classroomId: UUID,
    val name: String,
    val startTime: String,
    val endTime: String,
    /** NOW · UPCOMING · DONE */
    val phase: String,
    val scheduled: Int,
    val present: Int,
    val checkedOut: Int,
    val absent: Int,
)
data class TodoStudent(
    val studentId: UUID,
    val studentName: String,
    val classroomId: UUID,
    val classroomName: String,
    val attendanceDayId: UUID?,
    val minutes: Long,
    val guardianPhone: String?,
)
data class TodoAbsence(val id: UUID, val studentName: String, val date: LocalDate, val kind: String, val expectedTime: String?, val reason: String?)
data class StudentCaution(
    val studentId: UUID,
    val studentName: String,
    val classroomName: String,
    /** HEALTH · BIRTHDAY · ABSENCE_NOTE · COUNSEL */
    val kind: String,
    /** 입력된 내용 그대로 (HEALTH 알레르기, ABSENCE_NOTE 사유, COUNSEL 상담 내용 앞부분, BIRTHDAY 만 나이) — 라벨은 화면이 kind 로 붙인다 */
    val text: String?,
    /** HEALTH 지병, ABSENCE_NOTE "LATE 15:30" 처럼 종류·예상 시각, COUNSEL 상담일 */
    val sub: String?,
)
data class UnansweredConversation(val conversationId: UUID, val studentName: String, val preview: String?, val unread: Int, val lastMessageAt: Instant)
data class NoticeFollowUp(val noticeId: UUID, val title: String, val sentAt: Instant, val targets: Int, val unread: Int, val canResendAt: Instant?)
data class EventFollowUp(val eventId: UUID, val title: String, val deadline: Instant, val noResponse: Int)
data class ConsultToday(val slotId: UUID, val startsAt: Instant, val studentName: String?, val mode: String, val topic: String?)
data class ProgressMissing(val classroomId: UUID, val classroomName: String, val endTime: String)
data class TeacherWork(
    val checkInAt: Instant?,
    val checkOutAt: Instant?,
    val weekMinutes: Long,
    val runningTripId: UUID?,
    val runningRouteName: String?,
    val hasShuttleRoutes: Boolean,
)
data class StaffMemoView(
    val id: UUID,
    val title: String,
    val body: String?,
    val dueDate: LocalDate?,
    val authorName: String,
    val createdAt: Instant,
    val toAll: Boolean,
    val canceled: Boolean,
    // 받은 사람 기준
    val readAt: Instant?,
    val doneAt: Instant?,
    // 작성자(원장) 기준 집계
    val targets: Int,
    val readCount: Int,
    val doneCount: Int,
)
data class StaffMemoAck(val userId: UUID, val name: String, val readAt: Instant?, val doneAt: Instant?)

data class TeacherDashboard(
    val teacherId: UUID,
    val teacherName: String,
    val date: LocalDate,
    val classes: List<TeacherClassToday>,
    val notArrived: List<TodoStudent>,
    val notCheckedOut: List<TodoStudent>,
    val absenceRequests: List<TodoAbsence>,
    val cautions: List<StudentCaution>,
    val conversations: List<UnansweredConversation>,
    val notices: List<NoticeFollowUp>,
    val events: List<EventFollowUp>,
    val consults: List<ConsultToday>,
    val progressMissing: List<ProgressMissing>,
    val work: TeacherWork,
    val memos: List<StaffMemoView>,
)

interface TeacherDashboardQuery {
    /** teacherId 가 없으면 본인. 다른 교사 것은 원장·실장만 (DASH-008 '교사 화면 보기') */
    fun dashboard(actor: UserId, institutionId: InstitutionId, teacherId: UUID?): TeacherDashboard
}

// ───────── DASH-015·023 교직원 공지 ─────────
interface StaffMemoUseCase {
    data class Create(val title: String, val body: String?, val dueDate: LocalDate?, val targetUserIds: List<UUID>)

    /** 원장·실장: 작성·목록(집계 포함)·확인 현황·취소 */
    fun create(actor: UserId, institutionId: InstitutionId, command: Create): StaffMemoView
    fun list(actor: UserId, institutionId: InstitutionId, includeCanceled: Boolean): List<StaffMemoView>
    fun acks(actor: UserId, institutionId: InstitutionId, memoId: UUID): List<StaffMemoAck>
    fun cancel(actor: UserId, institutionId: InstitutionId, memoId: UUID)

    /** 교직원 본인: 받은 공지 (취소 제외), 확인·완료 */
    fun mine(actor: UserId, institutionId: InstitutionId, includeDone: Boolean): List<StaffMemoView>
    fun ack(actor: UserId, institutionId: InstitutionId, memoId: UUID, done: Boolean?): StaffMemoView
}

// ───────── DASH-017 위젯 배치 ─────────
data class DashboardWidget(val key: String, val visible: Boolean)

interface DashboardLayoutUseCase {
    /** kind = OWNER | TEACHER. 저장한 적 없으면 빈 목록 (화면이 기본 배치 사용) */
    fun layout(actor: UserId, kind: String): List<DashboardWidget>
    fun save(actor: UserId, kind: String, widgets: List<DashboardWidget>): List<DashboardWidget>
    fun reset(actor: UserId, kind: String)
}
