package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ChildRef
import com.ttokttok.application.port.`in`.ComposeNoticeUseCase
import com.ttokttok.application.port.`in`.GuardianReceipt
import com.ttokttok.application.port.`in`.NoticeQuery
import com.ttokttok.application.port.`in`.NoticeReceipts
import com.ttokttok.application.port.`in`.NoticeView
import com.ttokttok.application.port.`in`.ParentNoticeItem
import com.ttokttok.application.port.`in`.ParentNoticeUseCase
import com.ttokttok.application.port.`in`.PublishDueNoticesUseCase
import com.ttokttok.application.port.`in`.ReadStats
import com.ttokttok.application.port.`in`.ResendUnreadNoticeUseCase
import com.ttokttok.application.port.`in`.StudentReceipt
import com.ttokttok.application.port.`in`.TargetView
import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.NoticePort
import com.ttokttok.application.port.out.NoticeRecipientPort
import com.ttokttok.application.port.out.NoticeSearchCriteria
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.GuardianMessageRequested
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.messaging.MessageTemplate
import com.ttokttok.domain.notice.Notice
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticePublished
import com.ttokttok.domain.notice.NoticeReadSummary
import com.ttokttok.domain.notice.NoticeRecipient
import com.ttokttok.domain.notice.NoticeStatus
import com.ttokttok.domain.notice.NoticeTarget
import com.ttokttok.domain.notice.TargetScope
import com.ttokttok.domain.student.GuardianLinkStatus
import com.ttokttok.domain.student.Student
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.user.Membership
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

internal fun NoticeReadSummary.toStats() = ReadStats(targetStudents, readStudents, rate)

/**
 * 알림장 대상 권한 + 수신 학생 펼치기.
 * 스펙 3장: 원장·실장은 전체 대상, 교사는 담당 반(과 그 반 학생)만. 전체 공지는 관리자만.
 */
@Component
class NoticeAudience(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
    private val enrollments: EnrollmentPort,
    private val students: StudentPort,
) {
    fun authorize(actor: UserId, institutionId: InstitutionId, kind: NoticeKind, targets: List<NoticeTarget>): Membership {
        val m = guard.requireStaff(actor, institutionId)
        if ((kind == NoticeKind.ANNOUNCEMENT || targets.any { it.scope == TargetScope.ALL }) && !m.role.isManager)
            throw ForbiddenException("전체 대상 발송은 원장·실장만 할 수 있습니다")
        targets.forEach { t ->
            when (t.scope) {
                TargetScope.ALL -> Unit
                TargetScope.CLASS -> guard.requireClassroomAccess(actor, institutionId, t.classroomId!!)
                TargetScope.STUDENT -> {
                    val s = students.find(t.studentId!!, institutionId) ?: throw NotFoundException("원생")
                    if (!m.role.isManager) {
                        val mine = enrollments.findCurrent(s.id).any { e -> classrooms.find(e.classroomId, institutionId)?.isTaughtBy(actor) == true }
                        if (!mine) throw ForbiddenException("담당 반 원생에게만 보낼 수 있습니다")
                    }
                }
            }
        }
        return m
    }

    /** 발송 시점 수신 학생. 반·전체 대상은 재원(ACTIVE)만, 학생 지정은 퇴원생만 제외 */
    fun expand(institutionId: InstitutionId, targets: List<NoticeTarget>): List<Student> {
        val picked = targets.flatMap { t ->
            when (t.scope) {
                TargetScope.ALL -> students.findByInstitution(institutionId).filter { it.status == StudentStatus.ACTIVE }
                TargetScope.CLASS -> students.findAllByIds(enrollments.findCurrentStudentIds(t.classroomId!!))
                    .filter { it.status == StudentStatus.ACTIVE }
                TargetScope.STUDENT -> students.findAllByIds(listOf(t.studentId!!)).filter { it.status != StudentStatus.WITHDRAWN }
            }
        }
        return picked.filter { it.institutionId == institutionId }.distinctBy { it.id }
    }

    fun describe(institutionId: InstitutionId, targets: List<NoticeTarget>): List<TargetView> {
        val classNames = classrooms.findByInstitution(institutionId).associate { it.id to it.name }
        val studentNames = students.findAllByIds(targets.mapNotNull { it.studentId }).associate { it.id to it.name }
        return targets.map { t ->
            when (t.scope) {
                TargetScope.ALL -> TargetView("ALL", null, "전체")
                TargetScope.CLASS -> TargetView("CLASS", t.classroomId!!.value, classNames[t.classroomId] ?: "(삭제된 반)")
                TargetScope.STUDENT -> TargetView("STUDENT", t.studentId!!.value, studentNames[t.studentId] ?: "(알 수 없음)")
            }
        }
    }
}

/**
 * 발송: 수신자 스냅샷 생성 → 상태 SENT → Outbox(푸시) → 실시간.
 * 앱 연결 보호자가 없는 학생은 대기(PENDING) 보호자 번호로 알림톡 안내 (스펙 6장 채널 매트릭스).
 */
@Component
class NoticePublisher(
    private val audience: NoticeAudience,
    private val institutions: InstitutionPort,
    private val guardians: GuardianPort,
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
    private val outbox: OutboxPort,
    private val links: AppLinksPort,
    private val realtime: RealtimePort,
) {
    fun publish(notice: Notice, now: Instant): Notice {
        val institution = institutions.findById(notice.institutionId) ?: throw NotFoundException("기관")
        val targets = audience.expand(notice.institutionId, notice.targets)
        val byStudent = guardians.findByStudents(targets.map { it.id }).filter { it.linkStatus != GuardianLinkStatus.UNLINKED }
            .groupBy { it.studentId }

        val rows = targets.flatMap { s ->
            val users = byStudent[s.id].orEmpty().filter { it.linkStatus == GuardianLinkStatus.LINKED }.mapNotNull { it.userId }.distinct()
            if (users.isEmpty()) listOf(NoticeRecipient(notice.id, s.id, null))
            else users.map { NoticeRecipient(notice.id, s.id, it) }
        }
        recipients.saveAll(rows)
        val sent = notices.save(notice.publish(now))

        val userIds = rows.mapNotNull { it.guardianUserId }.distinct()
        if (userIds.isNotEmpty()) {
            outbox.publish(NoticePublished(notice.institutionId, institution.name, notice.id, notice.kind, notice.title, userIds, false, now))
        }
        // 앱 미설치 보호자: 번호당 한 번만 안내
        targets.filter { s -> byStudent[s.id].orEmpty().none { it.linkStatus == GuardianLinkStatus.LINKED } }
            .flatMap { s -> byStudent[s.id].orEmpty().map { g -> g.phone to s } }
            .distinctBy { it.first.digits }
            .forEach { (phone, s) ->
                outbox.publish(
                    GuardianMessageRequested(
                        notice.institutionId, phone, MessageTemplate.NOTICE_NEW,
                        mapOf("institutionName" to institution.name, "studentName" to s.name, "title" to notice.title, "installUrl" to links.appInstallUrl()),
                        now,
                    ),
                )
            }
        realtime.institutionEvent(
            notice.institutionId,
            mapOf("type" to "notice.sent", "noticeId" to notice.id.value.toString(), "targetStudents" to targets.size),
        )
        return sent
    }
}

@Service
class ComposeNoticeService(
    private val audience: NoticeAudience,
    private val publisher: NoticePublisher,
    private val notices: NoticePort,
    private val views: NoticeViewAssembler,
    private val fileRefs: FileRefResolver,
    private val clock: ClockPort,
) : ComposeNoticeUseCase {

    @Transactional
    override fun create(command: ComposeNoticeUseCase.Command): NoticeView {
        audience.authorize(command.actor, command.institutionId, command.kind, command.targets)
        fileRefs.requireUsable(command.attachments, command.institutionId, FilePurpose.NOTICE_ATTACHMENT)
        val now = clock.now()
        val notice = Notice.compose(
            command.institutionId, command.actor, command.kind, command.title, command.body,
            command.pinned, command.targets, command.sendAt, now, command.attachments,
        )
        if (audience.expand(command.institutionId, notice.targets).isEmpty())
            throw InvalidInputException("NO_RECIPIENTS", "받을 원생이 없습니다")
        notices.save(notice)
        val result = if (notice.isDue(now)) publisher.publish(notice, now) else notice
        return views.view(result)
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: NoticeId, command: ComposeNoticeUseCase.Command): NoticeView {
        val current = editable(actor, institutionId, id)
        if (command.kind != current.kind) throw InvalidInputException("KIND_IMMUTABLE", "알림장 종류는 바꿀 수 없습니다")
        audience.authorize(actor, institutionId, current.kind, command.targets)
        fileRefs.requireUsable(command.attachments, institutionId, FilePurpose.NOTICE_ATTACHMENT)
        val now = clock.now()
        val edited = notices.save(current.edit(command.title, command.body, command.pinned, command.targets, command.sendAt, now, command.attachments))
        val result = if (edited.isDue(now)) publisher.publish(edited, now) else edited
        return views.view(result)
    }

    @Transactional
    override fun cancel(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeView =
        views.view(notices.save(editable(actor, institutionId, id).cancel()))

    /** 작성자 본인 또는 원장·실장 */
    private fun editable(actor: UserId, institutionId: InstitutionId, id: NoticeId): Notice {
        val m = audience.authorize(actor, institutionId, NoticeKind.NOTE, emptyList())
        val n = notices.findForUpdate(id, institutionId) ?: throw NotFoundException("알림장")
        if (!m.role.isManager && n.authorId != actor) throw ForbiddenException("본인이 작성한 알림장만 수정·취소할 수 있습니다")
        return n
    }
}

/** 관리자 화면용 뷰 조립 (대상 이름·작성자 이름·열람 통계) */
@Component
class NoticeViewAssembler(
    private val audience: NoticeAudience,
    private val recipients: NoticeRecipientPort,
    private val users: UserPort,
    private val fileRefs: FileRefResolver,
) {
    fun view(n: Notice, stats: ReadStats? = null, authorName: String? = null) = NoticeView(
        id = n.id, kind = n.kind, title = n.title, body = n.body, pinned = n.pinned,
        targets = audience.describe(n.institutionId, n.targets), status = n.status,
        scheduledAt = n.scheduledAt, sentAt = n.sentAt, lastResentAt = n.lastResentAt,
        authorId = n.authorId, authorName = authorName ?: users.findById(n.authorId)?.name ?: "(알 수 없음)",
        createdAt = n.createdAt,
        readStats = stats ?: if (n.status == NoticeStatus.SENT) NoticeReadSummary.of(recipients.findByNotice(n.id)).toStats() else null,
        attachments = fileRefs.refs(n.attachments, withUrl = false),
    )

    fun views(list: List<Notice>): List<NoticeView> {
        val sent = list.filter { it.status == NoticeStatus.SENT }.map { it.id }
        val stats = recipients.findByNotices(sent).groupBy { it.noticeId }.mapValues { NoticeReadSummary.of(it.value).toStats() }
        val authors = list.map { it.authorId }.distinct().associateWith { users.findById(it)?.name ?: "(알 수 없음)" }
        return list.map { n ->
            view(n, stats[n.id] ?: if (n.status == NoticeStatus.SENT) ReadStats(0, 0, null) else null, authors[n.authorId])
        }
    }
}

@Service
class NoticeQueryService(
    private val guard: AccessGuard,
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
    private val views: NoticeViewAssembler,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val classrooms: ClassroomPort,
    private val users: UserPort,
) : NoticeQuery {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, kind: NoticeKind?, status: NoticeStatus?, page: Int, size: Int): PageResult<NoticeView> {
        if (page < 0 || size !in 1..100) throw InvalidInputException("INVALID_PAGE", "page ≥ 0, size 1~100")
        val m = guard.requireStaff(actor, institutionId)
        val result = notices.search(NoticeSearchCriteria(institutionId, if (m.role.isManager) null else actor, kind, status, page, size))
        return PageResult(views.views(result.items), result.page, result.size, result.totalElements)
    }

    @Transactional(readOnly = true)
    override fun detail(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeView {
        val n = readable(actor, institutionId, id, allowAnnouncement = true)
        return views.view(n)
    }

    @Transactional(readOnly = true)
    override fun receipts(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeReceipts {
        val n = readable(actor, institutionId, id, allowAnnouncement = false)
        if (n.status != NoticeStatus.SENT) throw ConflictException("NOT_SENT", "발송 전 알림장입니다")
        val rows = recipients.findByNotice(n.id)
        val byStudent = rows.groupBy { it.studentId }
        val names = students.findAllByIds(byStudent.keys).associate { it.id to it.name }
        val classNames = classrooms.findByInstitution(institutionId).associate { it.id to it.name }
        val userNames = rows.mapNotNull { it.guardianUserId }.distinct().associateWith { users.findById(it)?.name ?: "(알 수 없음)" }

        val list = byStudent.map { (sid, rs) ->
            val reads = rs.mapNotNull { it.readAt }
            StudentReceipt(
                studentId = sid, studentName = names[sid] ?: "(알 수 없음)",
                classroomNames = enrollments.findCurrent(sid).mapNotNull { classNames[it.classroomId] },
                read = reads.isNotEmpty(), firstReadAt = reads.minOrNull(), resentCount = rs.maxOf { it.resentCount },
                guardians = rs.filter { it.guardianUserId != null }.map {
                    GuardianReceipt(it.guardianUserId!!, userNames.getValue(it.guardianUserId), it.deliveredAt, it.readAt)
                },
            )
        }.sortedWith(compareBy({ it.read }, { it.studentName })) // 미열람 먼저
        return NoticeReceipts(n.id, NoticeReadSummary.of(rows).toStats(), n.lastResentAt?.plus(Notice.RESEND_COOLDOWN), list)
    }

    private fun readable(actor: UserId, institutionId: InstitutionId, id: NoticeId, allowAnnouncement: Boolean): Notice {
        val m = guard.requireStaff(actor, institutionId)
        val n = notices.find(id, institutionId) ?: throw NotFoundException("알림장")
        val ok = m.role.isManager || n.authorId == actor || (allowAnnouncement && n.kind == NoticeKind.ANNOUNCEMENT)
        if (!ok) throw ForbiddenException("본인이 작성한 알림장만 볼 수 있습니다")
        return n
    }
}

@Service
class ResendUnreadNoticeService(
    private val guard: AccessGuard,
    private val institutions: InstitutionPort,
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : ResendUnreadNoticeUseCase {

    @Transactional
    override fun resend(actor: UserId, institutionId: InstitutionId, id: NoticeId): ResendUnreadNoticeUseCase.ResendResult {
        val m = guard.requireStaff(actor, institutionId)
        val n = notices.findForUpdate(id, institutionId) ?: throw NotFoundException("알림장")
        if (!m.role.isManager && n.authorId != actor) throw ForbiddenException("본인이 작성한 알림장만 재발송할 수 있습니다")
        val now = clock.now()
        val marked = n.markResent(now) // 쿨타임 검사

        val unread = NoticeReadSummary.unreadGuardians(recipients.findByNotice(n.id))
        val users = unread.values.flatten().distinct()
        if (users.isEmpty()) throw ConflictException("NOTHING_TO_RESEND", "앱으로 다시 보낼 미열람 보호자가 없습니다")

        notices.save(marked)
        recipients.incrementResent(n.id, unread.filterValues { it.isNotEmpty() }.keys)
        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        outbox.publish(NoticePublished(institutionId, institution.name, n.id, n.kind, n.title, users, true, now))
        return ResendUnreadNoticeUseCase.ResendResult(unread.count { it.value.isNotEmpty() }, users.size, now)
    }
}

/** 매분 실행. 건마다 예외를 격리하지 않도록 한 번에 적은 수만 잠근다 */
@Service
class PublishDueNoticesService(
    private val notices: NoticePort,
    private val publisher: NoticePublisher,
    private val clock: ClockPort,
) : PublishDueNoticesUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun publishDue(limit: Int): Int {
        val now = clock.now()
        val due = notices.lockDue(now, limit)
        due.forEach { n ->
            publisher.publish(n, now)
            log.info("예약 알림장 발송 {} ({})", n.id.value, n.title)
        }
        return due.size
    }
}

@Service
class ParentNoticeService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
    private val users: UserPort,
    private val realtime: RealtimePort,
    private val fileRefs: FileRefResolver,
    private val clock: ClockPort,
) : ParentNoticeUseCase {

    @Transactional(readOnly = true)
    override fun inbox(parent: UserId, childId: StudentId?, before: Instant?, limit: Int): List<ParentNoticeItem> {
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        val mine = guardians.findLinkedByUser(parent).map { it.studentId }.toSet()
        if (childId != null && childId !in mine) throw ForbiddenException("본인 자녀가 아닙니다")
        val ids = recipients.findInboxNoticeIds(parent, childId?.let { setOf(it) }, before, limit)
        return items(parent, ids, preview = true)
    }

    @Transactional(readOnly = true)
    override fun detail(parent: UserId, id: NoticeId): ParentNoticeItem =
        items(parent, listOf(id), preview = false).firstOrNull() ?: throw NotFoundException("알림장")

    @Transactional
    override fun markRead(parent: UserId, id: NoticeId) {
        val notice = notices.findAllByIds(listOf(id)).firstOrNull()?.takeIf { it.status == NoticeStatus.SENT } ?: throw NotFoundException("알림장")
        if (recipients.findForUser(listOf(id), parent).isEmpty()) throw NotFoundException("알림장")
        if (recipients.markRead(id, parent, clock.now())) {
            val stats = NoticeReadSummary.of(recipients.findByNotice(id))
            realtime.institutionEvent(
                notice.institutionId,
                mapOf(
                    "type" to "notice.read", "noticeId" to id.value.toString(),
                    "readStudents" to stats.readStudents, "targetStudents" to stats.targetStudents,
                ),
            )
        }
    }

    private fun items(parent: UserId, ids: List<NoticeId>, preview: Boolean): List<ParentNoticeItem> {
        if (ids.isEmpty()) return emptyList()
        val list = notices.findAllByIds(ids).filter { it.status == NoticeStatus.SENT }
        val mine = recipients.findForUser(ids, parent).groupBy { it.noticeId }
        val names = students.findAllByIds(mine.values.flatten().map { it.studentId }.toSet()).associate { it.id to it.name }
        val insts = institutions.findAllByIds(list.map { it.institutionId }.toSet()).associate { it.id to it.name }
        val authors = list.map { it.authorId }.distinct().associateWith { users.findById(it)?.name ?: "" }
        return list.filter { it.id in mine }.map { n ->
            val rows = mine.getValue(n.id)
            ParentNoticeItem(
                id = n.id, institutionId = n.institutionId, institutionName = insts[n.institutionId] ?: "",
                kind = n.kind, title = n.title,
                body = if (preview && n.body.length > PREVIEW) n.body.take(PREVIEW) + "…" else n.body,
                pinned = n.pinned, sentAt = n.sentAt!!,
                children = rows.map { it.studentId }.distinct().map { ChildRef(it, names[it] ?: "") },
                readAt = rows.mapNotNull { it.readAt }.minOrNull(),
                authorName = authors[n.authorId] ?: "",
                attachments = fileRefs.refs(n.attachments, withUrl = !preview),
            )
        }.sortedByDescending { it.sentAt }
    }

    companion object { private const val PREVIEW = 100 }
}

/** 대시보드 공지 열람률 (스펙 7-2): 최근 24시간 발송 알림장의 열람률 평균 */
@Component
class NoticeReadRateCalculator(
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
) {
    fun last24h(institutionId: InstitutionId, authorId: UserId?, now: Instant): Double? {
        val sent = notices.findSentBetween(institutionId, now.minus(Duration.ofHours(24)), now, authorId)
        if (sent.isEmpty()) return null
        val rates = recipients.findByNotices(sent.map { it.id }).groupBy { it.noticeId }
            .mapNotNull { NoticeReadSummary.of(it.value).rate }
        return if (rates.isEmpty()) null else Math.round(rates.average() * 1000) / 10.0 // % 소수 첫째 자리
    }
}
