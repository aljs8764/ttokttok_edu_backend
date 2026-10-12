package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ChurnRisk
import com.ttokttok.application.port.`in`.DashboardLayoutUseCase
import com.ttokttok.application.port.`in`.DashboardWidget
import com.ttokttok.application.port.`in`.InboxItem
import com.ttokttok.application.port.`in`.InstitutionCard
import com.ttokttok.application.port.`in`.OwnerBilling
import com.ttokttok.application.port.`in`.OwnerClassRow
import com.ttokttok.application.port.`in`.OwnerDashboardQuery
import com.ttokttok.application.port.`in`.OwnerKpi
import com.ttokttok.application.port.`in`.OwnerStaff
import com.ttokttok.application.port.`in`.StaffMemoAck
import com.ttokttok.application.port.`in`.StaffMemoUseCase
import com.ttokttok.application.port.`in`.StaffMemoView
import com.ttokttok.application.port.`in`.TeacherDashboard
import com.ttokttok.application.port.`in`.TeacherDashboardQuery
import com.ttokttok.application.port.out.ChurnSignal
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DashboardLayoutPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RoleDashboardPort
import com.ttokttok.application.port.out.StaffMemoPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.TeacherPushRequested
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Duration
import java.util.UUID

// ───────── DASH-009~016 원장 대시보드 ─────────
@Service
class OwnerDashboardService(
    private val guard: AccessGuard,
    private val board: RoleDashboardPort,
    private val clock: ClockPort,
) : OwnerDashboardQuery {

    @Transactional(readOnly = true)
    override fun kpi(actor: UserId, institutionId: InstitutionId): OwnerKpi {
        guard.requireManager(actor, institutionId)
        return board.ownerKpi(institutionId, clock.today(), clock.now())
    }

    /**
     * 반별 현황 + 경고 배지.
     * LOW_READ 는 열람률이 반 평균보다 10%p 넘게 낮은 반 (STAT-007 과 같은 기준, 비교할 반이 2개 이상일 때만).
     */
    @Transactional(readOnly = true)
    override fun classes(actor: UserId, institutionId: InstitutionId): List<OwnerClassRow> {
        guard.requireManager(actor, institutionId)
        val rows = board.ownerClasses(institutionId, clock.today(), clock.now())
        val rates = rows.mapNotNull { it.readRate7d }
        val avg = if (rates.size >= 2) rates.average() else null
        return rows.map { r ->
            val flags = buildList {
                if (r.teachers.isEmpty()) add("NO_TEACHER")
                if (r.capacity > 0 && r.headcount >= r.capacity) add("FULL")
                if (r.missingAlertsToday > 0) add("MISSING")
                if (avg != null && r.readRate7d != null && r.readRate7d < avg - 0.10) add("LOW_READ")
            }
            r.copy(flags = flags)
        }.sortedWith(compareByDescending<OwnerClassRow> { it.flags.size }.thenBy { it.startTime }.thenBy { it.name })
    }

    @Transactional(readOnly = true)
    override fun inbox(actor: UserId, institutionId: InstitutionId): List<InboxItem> {
        guard.requireManager(actor, institutionId)
        return board.inbox(institutionId, clock.today(), clock.now())
    }

    @Transactional(readOnly = true)
    override fun billing(actor: UserId, institutionId: InstitutionId): OwnerBilling {
        guard.requireManager(actor, institutionId)
        return board.billing(institutionId, clock.today())
    }

    @Transactional(readOnly = true)
    override fun churnRisks(actor: UserId, institutionId: InstitutionId, limit: Int): List<ChurnRisk> {
        guard.requireManager(actor, institutionId)
        if (limit !in 1..50) throw InvalidInputException("INVALID_LIMIT", "limit은 1~50입니다")
        return board.churnSignals(institutionId, clock.today())
            .mapNotNull(::score)
            .sortedWith(compareByDescending<ChurnRisk> { it.score }.thenBy { it.studentName })
            .take(limit)
    }

    @Transactional(readOnly = true)
    override fun staff(actor: UserId, institutionId: InstitutionId): OwnerStaff {
        guard.requireManager(actor, institutionId)
        return board.staffToday(institutionId, clock.today(), clock.now())
    }

    @Transactional(readOnly = true)
    override fun institutions(actor: UserId): List<InstitutionCard> =
        board.institutionCards(actor, clock.today(), clock.now())

    companion object {
        /** 이 점수 이상만 목록에 올린다 */
        const val CHURN_THRESHOLD = 40

        /**
         * 규칙 기반 이탈 위험 점수 (0~100). 학습 모델이 아니라 설명 가능한 규칙만 쓴다.
         *  - 결석 증가 +40: 최근 28일 결석률이 그 전 28일보다 20%p 이상 높고 최근 결석 3회 이상
         *  - 알림장 미열람 +25: 최근 30일 받은 알림장 3건 이상 중 70% 이상을 보호자 아무도 안 읽음
         *  - 상담일지 퇴원 언급 +30: 최근 60일
         *  - 연체 +20, 최근 90일 휴원 이력 +15
         */
        fun score(s: ChurnSignal): ChurnRisk? {
            var score = 0
            val reasons = mutableListOf<String>()
            val recentRate = if (s.scheduledRecent > 0) s.absentRecent.toDouble() / s.scheduledRecent else 0.0
            val beforeRate = if (s.scheduledBefore > 0) s.absentBefore.toDouble() / s.scheduledBefore else 0.0
            if (s.absentRecent >= 3 && recentRate - beforeRate >= 0.20) { score += 40; reasons += "ABSENCE_UP" }
            if (s.noticesRecent >= 3 && s.noticesUnread.toDouble() / s.noticesRecent >= 0.7) { score += 25; reasons += "UNREAD" }
            if (s.counselMentionsWithdraw) { score += 30; reasons += "COUNSEL_WITHDRAW" }
            if (s.overdueInvoices > 0) { score += 20; reasons += "OVERDUE" }
            if (s.pausedRecently) { score += 15; reasons += "PAUSED_BEFORE" }
            if (score < CHURN_THRESHOLD) return null
            return ChurnRisk(
                s.studentId, s.studentName, s.classroomNames, minOf(score, 100), reasons,
                s.absentRecent, s.absentBefore, s.lastCounselOn,
            )
        }
    }
}

// ───────── DASH-018~023 교사 대시보드 ─────────
@Service
class TeacherDashboardService(
    private val guard: AccessGuard,
    private val board: RoleDashboardPort,
    private val memos: StaffMemoPort,
    private val clock: ClockPort,
) : TeacherDashboardQuery {

    @Transactional(readOnly = true)
    override fun dashboard(actor: UserId, institutionId: InstitutionId, teacherId: UUID?): TeacherDashboard {
        val m = guard.requireStaff(actor, institutionId)
        val target = teacherId ?: actor.value
        if (target != actor.value) {
            // DASH-008 '교사 화면 보기': 원장·실장이 다른 교직원의 대시보드를 본다
            if (!m.role.isManager) throw ForbiddenException("다른 선생님의 대시보드는 원장·실장만 볼 수 있습니다")
            if (target !in memos.staffIds(institutionId)) throw NotFoundException("교직원")
        }
        val today = clock.today()
        val now = clock.now()
        val weekStart = today.with(DayOfWeek.MONDAY)
        return TeacherDashboard(
            teacherId = target,
            teacherName = board.teacherName(target) ?: "",
            date = today,
            classes = board.teacherClasses(institutionId, target, today, now),
            notArrived = board.notArrived(institutionId, target, today, now),
            notCheckedOut = board.notCheckedOut(institutionId, target, today, now),
            absenceRequests = board.pendingAbsences(institutionId, target, today),
            cautions = board.cautions(institutionId, target, today),
            conversations = board.unansweredConversations(institutionId, target, 10),
            notices = board.noticeFollowUps(institutionId, target, now.minus(Duration.ofDays(7))),
            events = board.eventFollowUps(institutionId, target, now, now.plus(Duration.ofDays(3))),
            consults = board.consultsToday(institutionId, target, today),
            progressMissing = board.progressMissing(institutionId, target, today, now),
            work = board.work(institutionId, target, today, weekStart),
            // 다른 교사 화면을 볼 때 그 교사가 받은 공지를 대신 '읽음' 처리하지 않도록 조회만
            memos = memos.mine(institutionId, UserId(target), includeDone = false),
        )
    }
}

// ───────── DASH-015·023 교직원 공지 ─────────
@Service
class StaffMemoService(
    private val guard: AccessGuard,
    private val memos: StaffMemoPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : StaffMemoUseCase {

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: StaffMemoUseCase.Create): StaffMemoView {
        guard.requireManager(actor, institutionId)
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "제목은 1~100자입니다")
        val body = command.body?.trim()?.takeIf { it.isNotEmpty() }
        if (body != null && body.length > 2000) throw InvalidInputException("INVALID_BODY", "내용은 2000자 이내입니다")
        if (command.dueDate != null && command.dueDate.isBefore(clock.today())) throw InvalidInputException("PAST_DATE", "지난 날짜로는 만들 수 없습니다")
        val staff = memos.staffIds(institutionId)
        val targets = command.targetUserIds.distinct()
        if (targets.size > 200) throw InvalidInputException("INVALID_TARGET", "대상을 1~200개 지정해 주세요")
        if (targets.any { it !in staff }) throw InvalidInputException("INVALID_TEACHER", "이 기관의 교직원이 아닙니다")
        val now = clock.now()
        val id = memos.insert(institutionId, actor, title, body, command.dueDate, targets, now)
        val recipients = memos.recipients(institutionId, id).filter { it != actor }
        if (recipients.isNotEmpty()) {
            outbox.publish(
                TeacherPushRequested(
                    institutionId, "STAFF_MEMO", "교직원 공지: $title",
                    body?.take(80) ?: (command.dueDate?.let { "마감 $it" } ?: "앱에서 확인해 주세요"),
                    mapOf("type" to "staff_memo", "memoId" to id.toString()), recipients, now,
                ),
            )
        }
        return memos.find(institutionId, id)!!
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, includeCanceled: Boolean): List<StaffMemoView> {
        guard.requireManager(actor, institutionId)
        return memos.list(institutionId, includeCanceled)
    }

    @Transactional(readOnly = true)
    override fun acks(actor: UserId, institutionId: InstitutionId, memoId: UUID): List<StaffMemoAck> {
        guard.requireManager(actor, institutionId)
        memos.find(institutionId, memoId) ?: throw NotFoundException("공지")
        return memos.acks(institutionId, memoId)
    }

    @Transactional
    override fun cancel(actor: UserId, institutionId: InstitutionId, memoId: UUID) {
        guard.requireManager(actor, institutionId)
        val memo = memos.find(institutionId, memoId) ?: throw NotFoundException("공지")
        if (!memo.canceled) memos.cancel(memoId, clock.now())
    }

    @Transactional(readOnly = true)
    override fun mine(actor: UserId, institutionId: InstitutionId, includeDone: Boolean): List<StaffMemoView> {
        guard.requireStaff(actor, institutionId)
        return memos.mine(institutionId, actor, includeDone)
    }

    /** done = null 이면 읽음만, true/false 면 완료 표시/해제 */
    @Transactional
    override fun ack(actor: UserId, institutionId: InstitutionId, memoId: UUID, done: Boolean?): StaffMemoView {
        guard.requireStaff(actor, institutionId)
        val memo = memos.find(institutionId, memoId) ?: throw NotFoundException("공지")
        if (memo.canceled) throw ConflictException("MEMO_CANCELED", "취소된 공지입니다")
        if (!memos.isRecipient(memoId, actor)) throw ForbiddenException("받은 공지가 아닙니다")
        memos.ack(memoId, actor, done, clock.now())
        return memos.mine(institutionId, actor, includeDone = true).first { it.id == memoId }
    }
}

// ───────── DASH-017 위젯 배치 ─────────
@Service
class DashboardLayoutService(
    private val layouts: DashboardLayoutPort,
    private val clock: ClockPort,
) : DashboardLayoutUseCase {
    private val kinds = setOf("OWNER", "TEACHER")
    private val keyPattern = Regex("^[a-z][a-z0-9-]{0,39}$")

    private fun kind(raw: String): String = raw.uppercase().also {
        if (it !in kinds) throw InvalidInputException("INVALID_TYPE", "대시보드 종류는 OWNER, TEACHER 입니다")
    }

    @Transactional(readOnly = true)
    override fun layout(actor: UserId, kind: String): List<DashboardWidget> = layouts.find(actor, kind(kind)) ?: emptyList()

    @Transactional
    override fun save(actor: UserId, kind: String, widgets: List<DashboardWidget>): List<DashboardWidget> {
        val k = kind(kind)
        if (widgets.size > 30) throw InvalidInputException("TOO_MANY_ITEMS", "위젯은 30개까지입니다")
        if (widgets.any { !keyPattern.matches(it.key) }) throw InvalidInputException("INVALID_KEY", "위젯 이름이 올바르지 않습니다")
        if (widgets.map { it.key }.toSet().size != widgets.size) throw InvalidInputException("DUPLICATE_KEY", "같은 위젯이 두 번 들어 있습니다")
        layouts.save(actor, k, widgets, clock.now())
        return widgets
    }

    @Transactional
    override fun reset(actor: UserId, kind: String) = layouts.delete(actor, kind(kind))
}
