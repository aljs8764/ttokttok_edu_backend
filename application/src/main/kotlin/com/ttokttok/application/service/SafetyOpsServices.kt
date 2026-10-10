package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.CurriculumUseCase
import com.ttokttok.application.port.`in`.EventAlbumLink
import com.ttokttok.application.port.`in`.EventFollowUpUseCase
import com.ttokttok.application.port.`in`.EventReviewView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.MissingAlertPolicy
import com.ttokttok.application.port.`in`.MissingAlertUseCase
import com.ttokttok.application.port.`in`.MissingAlertView
import com.ttokttok.application.port.`in`.NoticeReadRanking
import com.ttokttok.application.port.`in`.ProgressView
import com.ttokttok.application.port.`in`.ReadRankRow
import com.ttokttok.application.port.`in`.StaffWorkReport
import com.ttokttok.application.port.`in`.StaffWorkRow
import com.ttokttok.application.port.`in`.TextbookView
import com.ttokttok.application.port.`in`.WorkAnalyticsQuery
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.CurriculumPort
import com.ttokttok.application.port.out.EventFollowUpPort
import com.ttokttok.application.port.out.MissingAlertPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.WorkAnalyticsPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.messaging.TeacherPushRequested
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// ───────── ATT-010 미등원 초과 경고 ─────────
@Service
class MissingAlertService(
    private val guard: AccessGuard,
    private val alerts: MissingAlertPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : MissingAlertUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun policy(actor: UserId, institutionId: InstitutionId): MissingAlertPolicy {
        guard.requireStaff(actor, institutionId)
        return alerts.policy(institutionId)
    }

    @Transactional
    override fun setPolicy(actor: UserId, institutionId: InstitutionId, policy: MissingAlertPolicy): MissingAlertPolicy {
        guard.requireManager(actor, institutionId)
        if (policy.minutes != 0 && policy.minutes !in 10..180) throw InvalidInputException("INVALID_MINUTES", "기준 시간은 10~180분 또는 0(끔)입니다")
        alerts.setPolicy(institutionId, policy)
        return policy
    }

    @Transactional(readOnly = true)
    override fun alerts(actor: UserId, institutionId: InstitutionId, date: LocalDate): List<MissingAlertView> {
        val m = guard.requireStaff(actor, institutionId)
        val classes = if (m.role.isManager) null else guard.visibleClassrooms(actor, institutionId).map { it.id.value }
        return alerts.list(institutionId, date, classes)
    }

    /**
     * 수업 시작 + 기준 시간이 지났는데도 등원 기록이 없는 출결을 찾아 한 번씩 알린다.
     * 같은 날 다른 기관에서 하원한 기록이 있으면(다기관 아이) "어디서 몇 시에 하원했는지"를 함께 알린다.
     * 서버 재시작·배포 직후 오래된 건을 한꺼번에 보내지 않도록 최근 2시간 안에 기준을 넘긴 것만 본다.
     */
    @Transactional
    override fun scan(now: Instant): Int {
        val zone = clock.zone()
        val today = clock.today()
        var sent = 0
        alerts.candidates(today, now, now.minus(Duration.ofHours(2))).forEach { c ->
            c.lateExpected?.let { t ->
                val expected = today.atTime(t).atZone(zone).toInstant()
                if (now.isBefore(expected.plusSeconds(c.minutes * 60L))) return@forEach
            }
            val previous = c.childId?.let { alerts.previousCheckout(it, c.institutionId, today, now) }
            val notifyParent = c.notifyParent
            if (!alerts.record(c, previous, notifyParent, now)) return@forEach
            val minutesLate = Duration.between(c.classStart, now).toMinutes()
            val startText = HM.format(c.classStart.atZone(zone))
            val prevText = previous?.let { (name, at) -> " · ${name}에서 ${HM.format(at.atZone(zone))}에 하원" } ?: ""
            val inst = InstitutionId(c.institutionId)
            val data = mapOf("type" to "attendance_missing", "studentId" to c.studentId.toString(), "attendanceDayId" to c.attendanceDayId.toString())
            val staff = alerts.staffRecipients(c.institutionId, c.classroomId)
            if (staff.isNotEmpty()) {
                outbox.publish(
                    TeacherPushRequested(
                        inst, "ATTENDANCE_MISSING", "미등원 확인 필요: ${c.studentName}",
                        "${c.classroomName} ${startText} 수업, ${minutesLate}분째 등원 기록 없음$prevText", data, staff, now,
                    ),
                )
            }
            if (notifyParent) {
                val guardians = alerts.guardians(c.studentId)
                if (guardians.isNotEmpty()) {
                    outbox.publish(
                        ParentPushRequested(
                            inst, "ATTENDANCE_MISSING", "[${c.institutionName}] ${c.studentName} 아직 등원하지 않았어요",
                            "${startText}에 시작한 수업에 ${minutesLate}분째 등원 기록이 없어요$prevText. 확인해 주세요.", data, guardians, now,
                        ),
                    )
                }
            }
            sent++
        }
        if (sent > 0) log.info("미등원 초과 경고 {}건", sent)
        return sent
    }
}

// ───────── STAT-007 · STF-005 ─────────
@Service
class WorkAnalyticsService(
    private val guard: AccessGuard,
    private val work: WorkAnalyticsPort,
    private val clock: ClockPort,
) : WorkAnalyticsQuery {

    private fun range(from: LocalDate?, to: LocalDate?): Pair<LocalDate, LocalDate> {
        val t = to ?: clock.today()
        val f = from ?: t.minusDays(29)
        if (f.isAfter(t)) throw InvalidInputException("INVALID_RANGE", "시작일이 종료일보다 늦습니다")
        if (f.isBefore(t.minusDays(366))) throw InvalidInputException("INVALID_RANGE", "기간은 최대 1년입니다")
        return f to t
    }

    @Transactional(readOnly = true)
    override fun noticeRanking(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): NoticeReadRanking {
        guard.requireManager(actor, institutionId)
        val (f, t) = range(from, to)
        val zone = clock.zone()
        val a = f.atStartOfDay(zone).toInstant()
        val b = t.plusDays(1).atStartOfDay(zone).toInstant()
        val overall = work.readOverall(institutionId, a, b)
        val instRate = if (overall.targets > 0) overall.read.toDouble() / overall.targets else null
        fun mark(rows: List<ReadRankRow>) = rows
            .map { it.copy(weak = instRate != null && it.rate != null && it.targets >= 5 && it.rate < instRate - WEAK_GAP) }
            .sortedWith(compareBy(nullsLast()) { it.rate })
        return NoticeReadRanking(f, t, instRate, mark(work.readByClassroom(institutionId, a, b)), mark(work.readByAuthor(institutionId, a, b)))
    }

    @Transactional(readOnly = true)
    override fun staffWork(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): StaffWorkReport {
        guard.requireManager(actor, institutionId)
        val (f, t) = range(from, to)
        val zone = clock.zone()
        val a = f.atStartOfDay(zone).toInstant()
        val b = t.plusDays(1).atStartOfDay(zone).toInstant()
        val notices = work.noticesSent(institutionId, a, b)
        val read = work.readByAuthor(institutionId, a, b).associateBy { it.id }
        val replies = work.repliesSent(institutionId, a, b)
        val replyMin = work.avgReplyMinutes(institutionId, a, b)
        val actions = work.attendanceActions(institutionId, a, b)
        val offset = work.avgCheckInOffset(institutionId, a, b)
        val consults = work.consultsDone(institutionId, a, b)
        val rows = work.staff(institutionId).map { s ->
            StaffWorkRow(
                s.userId, s.name, s.role, notices[s.userId] ?: 0, read[s.userId]?.rate, replies[s.userId] ?: 0, replyMin[s.userId],
                actions[s.userId] ?: 0, offset[s.userId], consults[s.userId] ?: 0,
            )
        }.sortedByDescending { it.noticesSent + it.repliesSent + it.attendanceActions }
        return StaffWorkReport(f, t, rows, work.unansweredMessages(institutionId, a, b))
    }

    companion object {
        /** 기관 평균보다 이만큼(10%p) 낮으면 취약으로 표시 */
        const val WEAK_GAP = 0.10
    }
}

// ───────── CLS-005 교재·진도 ─────────
@Service
class CurriculumService(
    private val guard: AccessGuard,
    private val curriculum: CurriculumPort,
    private val clock: ClockPort,
) : CurriculumUseCase {

    private fun access(actor: UserId, institutionId: InstitutionId, classroomId: UUID) =
        guard.requireClassroomAccess(actor, institutionId, ClassroomId(classroomId))

    private fun check(c: CurriculumUseCase.SaveTextbook): CurriculumUseCase.SaveTextbook {
        val name = c.name.trim()
        if (name.isEmpty() || name.length > 100) throw InvalidInputException("INVALID_NAME", "교재 이름은 1~100자입니다")
        return c.copy(name = name, publisher = c.publisher?.trim()?.takeIf { it.isNotEmpty() }?.take(50), memo = c.memo?.trim()?.takeIf { it.isNotEmpty() }?.take(300))
    }

    private fun check(c: CurriculumUseCase.SaveProgress): CurriculumUseCase.SaveProgress {
        val content = c.content.trim()
        if (content.isEmpty() || content.length > 500) throw InvalidInputException("INVALID_CONTENT", "진도 내용은 1~500자입니다")
        return c.copy(content = content, homework = c.homework?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > 500) throw InvalidInputException("TOO_LONG", "숙제는 500자까지입니다")
        })
    }

    @Transactional(readOnly = true)
    override fun textbooks(actor: UserId, institutionId: InstitutionId, classroomId: UUID, includeArchived: Boolean): List<TextbookView> {
        access(actor, institutionId, classroomId)
        return curriculum.textbooks(classroomId, includeArchived)
    }

    @Transactional
    override fun addTextbook(actor: UserId, institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveTextbook): TextbookView {
        access(actor, institutionId, classroomId)
        val id = curriculum.insertTextbook(institutionId, classroomId, check(command), clock.now())
        return curriculum.findTextbook(id)!!.second
    }

    @Transactional
    override fun updateTextbook(actor: UserId, institutionId: InstitutionId, textbookId: UUID, command: CurriculumUseCase.SaveTextbook): TextbookView {
        val (inst, tb) = curriculum.findTextbook(textbookId)?.takeIf { it.first == institutionId.value } ?: throw NotFoundException("교재")
        access(actor, InstitutionId(inst), tb.classroomId)
        curriculum.updateTextbook(textbookId, check(command))
        return curriculum.findTextbook(textbookId)!!.second
    }

    @Transactional(readOnly = true)
    override fun progress(actor: UserId, institutionId: InstitutionId, classroomId: UUID, from: LocalDate?, to: LocalDate?): List<ProgressView> {
        access(actor, institutionId, classroomId)
        return curriculum.progress(listOf(classroomId), from, to, 200)
    }

    @Transactional
    override fun addProgress(actor: UserId, institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveProgress): ProgressView {
        access(actor, institutionId, classroomId)
        val c = check(command)
        c.textbookId?.let { tid -> if (curriculum.findTextbook(tid)?.second?.classroomId != classroomId) throw InvalidInputException("INVALID_TEXTBOOK", "이 반의 교재가 아닙니다") }
        val id = curriculum.insertProgress(institutionId, classroomId, c, actor, clock.now())
        return curriculum.findProgress(id)!!.second
    }

    @Transactional
    override fun updateProgress(actor: UserId, institutionId: InstitutionId, progressId: UUID, command: CurriculumUseCase.SaveProgress): ProgressView {
        val (inst, p) = curriculum.findProgress(progressId)?.takeIf { it.first == institutionId.value } ?: throw NotFoundException("진도")
        access(actor, InstitutionId(inst), p.classroomId)
        val c = check(command)
        c.textbookId?.let { tid -> if (curriculum.findTextbook(tid)?.second?.classroomId != p.classroomId) throw InvalidInputException("INVALID_TEXTBOOK", "이 반의 교재가 아닙니다") }
        curriculum.updateProgress(progressId, c, clock.now())
        return curriculum.findProgress(progressId)!!.second
    }

    @Transactional
    override fun deleteProgress(actor: UserId, institutionId: InstitutionId, progressId: UUID) {
        val (inst, p) = curriculum.findProgress(progressId)?.takeIf { it.first == institutionId.value } ?: throw NotFoundException("진도")
        access(actor, InstitutionId(inst), p.classroomId)
        curriculum.deleteProgress(progressId)
    }

    @Transactional(readOnly = true)
    override fun quote(actor: UserId, institutionId: InstitutionId, classroomIds: List<UUID>, date: LocalDate?): String {
        if (classroomIds.isEmpty() || classroomIds.size > 20) throw InvalidInputException("INVALID_CLASSES", "반을 1~20개 고르세요")
        classroomIds.forEach { access(actor, institutionId, it) }
        val lines = classroomIds.mapNotNull { cid ->
            val p = (if (date != null) curriculum.progress(listOf(cid), date, date, 1) else curriculum.progress(listOf(cid), null, null, 1)).firstOrNull()
                ?: return@mapNotNull null
            buildString {
                append("[${p.classroomName}] ${p.date.monthValue}/${p.date.dayOfMonth} 진도: ${p.content}")
                p.textbookName?.let { append(" ($it)") }
                p.homework?.let { append("\n숙제: $it") }
            }
        }
        return lines.joinToString("\n\n")
    }
}

// ───────── EVT-009 행사 후기·사진 ─────────
@Service
class EventFollowUpService(
    private val guard: AccessGuard,
    private val events: EventFollowUpPort,
    private val children: GetMyChildrenQuery,
    private val clock: ClockPort,
) : EventFollowUpUseCase {

    private fun staffEvent(actor: UserId, institutionId: InstitutionId, eventId: UUID) = run {
        guard.requireStaff(actor, institutionId)
        events.event(eventId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("행사")
    }

    @Transactional(readOnly = true)
    override fun albums(actor: UserId, institutionId: InstitutionId, eventId: UUID): List<EventAlbumLink> {
        staffEvent(actor, institutionId, eventId)
        return events.albums(eventId, null)
    }

    @Transactional
    override fun linkAlbum(actor: UserId, institutionId: InstitutionId, eventId: UUID, albumId: UUID): List<EventAlbumLink> {
        staffEvent(actor, institutionId, eventId)
        if (events.albumInstitution(albumId) != institutionId.value) throw NotFoundException("앨범")
        events.setAlbumEvent(albumId, eventId)
        return events.albums(eventId, null)
    }

    @Transactional
    override fun unlinkAlbum(actor: UserId, institutionId: InstitutionId, eventId: UUID, albumId: UUID): List<EventAlbumLink> {
        staffEvent(actor, institutionId, eventId)
        if (events.albumInstitution(albumId) != institutionId.value) throw NotFoundException("앨범")
        events.setAlbumEvent(albumId, null)
        return events.albums(eventId, null)
    }

    @Transactional(readOnly = true)
    override fun reviews(actor: UserId, institutionId: InstitutionId, eventId: UUID): List<EventReviewView> {
        staffEvent(actor, institutionId, eventId)
        return events.reviews(eventId, null, true)
    }

    @Transactional
    override fun hideReview(actor: UserId, institutionId: InstitutionId, reviewId: UUID, hidden: Boolean): EventReviewView {
        val (eventId, _) = events.findReview(reviewId) ?: throw NotFoundException("후기")
        staffEvent(actor, institutionId, eventId)
        events.setHidden(reviewId, hidden, clock.now())
        return events.findReview(reviewId)!!.second
    }

    private fun myStudentsIn(parent: UserId, eventId: UUID): Set<UUID> {
        val mine = children.children(parent).flatMap { c -> c.enrollments.map { it.studentId.value } }.toSet()
        return mine.intersect(events.targetStudents(eventId))
    }

    @Transactional(readOnly = true)
    override fun myAlbums(parent: UserId, eventId: UUID): List<EventAlbumLink> {
        val students = myStudentsIn(parent, eventId)
        if (students.isEmpty()) throw ForbiddenException("이 행사의 대상이 아닙니다")
        return events.albums(eventId, students)
    }

    @Transactional(readOnly = true)
    override fun myReviews(parent: UserId, eventId: UUID): List<EventReviewView> {
        if (myStudentsIn(parent, eventId).isEmpty()) throw ForbiddenException("이 행사의 대상이 아닙니다")
        return events.reviews(eventId, parent, false)
    }

    @Transactional
    override fun writeReview(parent: UserId, eventId: UUID, studentId: UUID, body: String): EventReviewView {
        val ev = events.event(eventId) ?: throw NotFoundException("행사")
        if (studentId !in myStudentsIn(parent, eventId)) throw ForbiddenException("이 행사의 대상 자녀가 아닙니다")
        if (ev.startsAt.isAfter(clock.now())) throw InvalidInputException("NOT_STARTED", "후기는 행사가 시작된 뒤에 남길 수 있습니다")
        val text = body.trim()
        if (text.isEmpty() || text.length > 1000) throw InvalidInputException("INVALID_BODY", "후기는 1~1000자입니다")
        events.upsertReview(eventId, studentId, parent, text, clock.now())
        return events.reviews(eventId, parent, false).first { it.mine }
    }

    @Transactional
    override fun deleteMyReview(parent: UserId, eventId: UUID) = events.deleteReview(eventId, parent)
}
