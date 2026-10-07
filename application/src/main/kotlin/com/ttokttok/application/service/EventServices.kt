package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ChildRsvp
import com.ttokttok.application.port.`in`.EventQuery
import com.ttokttok.application.port.`in`.EventSummary
import com.ttokttok.application.port.`in`.EventView
import com.ttokttok.application.port.`in`.ExportedFile
import com.ttokttok.application.port.`in`.ManageEventUseCase
import com.ttokttok.application.port.`in`.ParentEventItem
import com.ttokttok.application.port.`in`.ParentEventUseCase
import com.ttokttok.application.port.`in`.RemindEventUseCase
import com.ttokttok.application.port.`in`.RsvpRow
import com.ttokttok.application.port.`in`.Tally
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.EventTargetPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.RsvpResponsePort
import com.ttokttok.application.port.out.SchoolEventPort
import com.ttokttok.application.port.out.SpreadsheetPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.event.RsvpAnswer
import com.ttokttok.domain.event.RsvpResponse
import com.ttokttok.domain.event.RsvpTally
import com.ttokttok.domain.event.SchoolEvent
import com.ttokttok.domain.event.SchoolEventId
import com.ttokttok.domain.event.SchoolEventStatus
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.student.GuardianLinkStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun RsvpTally.toTally() = Tally(targets, attend, absent, pending)

/** 행사 알림 푸시 — 대상 학생의 연결된 보호자 계정 */
@Component
class EventNotifier(
    private val guardians: GuardianPort,
    private val institutions: InstitutionPort,
    private val outbox: OutboxPort,
) {
    private val fmt = DateTimeFormatter.ofPattern("M/d(E) HH:mm", java.util.Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul"))

    fun notify(event: SchoolEvent, students: Collection<StudentId>, kind: Kind, now: Instant): Int {
        val users = guardians.findByStudents(students).filter { it.linkStatus == GuardianLinkStatus.LINKED }.mapNotNull { it.userId }.distinct()
        if (users.isEmpty()) return 0
        val inst = institutions.findById(event.institutionId)?.name ?: ""
        val body = when (kind) {
            Kind.NEW -> if (event.rsvpEnabled) "${event.title} — ${fmt.format(event.rsvpDeadline)}까지 참석 여부를 알려 주세요"
            else "${event.title} (${fmt.format(event.startsAt)})"
            Kind.REMINDER -> "${event.title} 참석 여부 응답이 ${fmt.format(event.rsvpDeadline)}에 마감돼요"
            Kind.CANCELED -> "${event.title} 행사가 취소되었어요"
            Kind.UPDATED -> "${event.title} 일정이 변경되었어요 (${fmt.format(event.startsAt)})"
        }
        outbox.publish(
            ParentPushRequested(
                institutionId = event.institutionId, template = kind.template, title = "[$inst] 행사", body = body,
                data = mapOf("type" to "event", "eventId" to event.id.value.toString(), "kind" to kind.name),
                recipientUserIds = users, occurredAt = now,
            ),
        )
        return users.size
    }

    enum class Kind(val template: String) {
        NEW("EVENT_NEW"), REMINDER("EVENT_RSVP_REMINDER"), CANCELED("EVENT_CANCELED"), UPDATED("EVENT_UPDATED")
    }
}

@Component
class EventViewAssembler(
    private val audience: NoticeAudience,
    private val targets: EventTargetPort,
    private val responses: RsvpResponsePort,
    private val users: UserPort,
) {
    fun view(e: SchoolEvent, withTally: Boolean = true) = EventView(
        id = e.id, title = e.title, body = e.body, location = e.location, startsAt = e.startsAt, endsAt = e.endsAt,
        targets = audience.describe(e.institutionId, e.targets), rsvpEnabled = e.rsvpEnabled, rsvpDeadline = e.rsvpDeadline,
        reminderHoursBefore = e.reminderHoursBefore, remindedAt = e.remindedAt, status = e.status,
        authorName = users.findById(e.authorId)?.name ?: "(알 수 없음)", createdAt = e.createdAt,
        tally = if (withTally && e.rsvpEnabled) RsvpTally.of(targets.findTargetStudents(e.id), responses.findByEvent(e.id)).toTally() else null,
    )
}

@Service
class ManageEventService(
    private val audience: NoticeAudience,
    private val events: SchoolEventPort,
    private val targets: EventTargetPort,
    private val notifier: EventNotifier,
    private val views: EventViewAssembler,
    private val clock: ClockPort,
) : ManageEventUseCase {

    @Transactional
    override fun create(command: ManageEventUseCase.CreateCommand): EventView {
        // 대상 권한은 알림장과 같다: 교사는 담당 반, 전체는 원장·실장
        audience.authorize(command.actor, command.institutionId, NoticeKind.NOTE, command.targets)
        val now = clock.now()
        val event = SchoolEvent.create(
            command.institutionId, command.actor, command.title, command.body, command.location,
            command.startsAt, command.endsAt, command.targets, command.rsvpEnabled, command.rsvpDeadline,
            command.reminderHoursBefore, now,
        )
        val students = audience.expand(command.institutionId, event.targets).map { it.id }
        if (students.isEmpty()) throw InvalidInputException("NO_RECIPIENTS", "대상 원생이 없습니다")
        events.save(event)
        targets.saveTargets(event.id, students)
        notifier.notify(event, students, EventNotifier.Kind.NEW, now)
        return views.view(event)
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: SchoolEventId, command: ManageEventUseCase.UpdateCommand): EventView {
        val current = editable(actor, institutionId, id)
        val now = clock.now()
        if (command.rsvpDeadline != null && current.rsvpEnabled && command.rsvpDeadline != current.rsvpDeadline && !command.rsvpDeadline.isAfter(now))
            throw InvalidInputException("INVALID_DEADLINE", "응답 마감은 현재 이후여야 합니다")
        val edited = events.save(
            current.edit(command.title, command.body, command.location, command.startsAt, command.endsAt, command.rsvpDeadline, command.reminderHoursBefore),
        )
        // 일정(시작 시각·장소)이 바뀌면 학부모에게 알린다
        if (edited.startsAt != current.startsAt || edited.location != current.location) {
            notifier.notify(edited, targets.findTargetStudents(id), EventNotifier.Kind.UPDATED, now)
        }
        return views.view(edited)
    }

    @Transactional
    override fun cancel(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): EventView {
        val canceled = events.save(editable(actor, institutionId, id).cancel())
        notifier.notify(canceled, targets.findTargetStudents(id), EventNotifier.Kind.CANCELED, clock.now())
        return views.view(canceled)
    }

    private fun editable(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): SchoolEvent {
        val m = audience.authorize(actor, institutionId, NoticeKind.NOTE, emptyList())
        val e = events.findForUpdate(id, institutionId) ?: throw NotFoundException("행사")
        if (!m.role.isManager && e.authorId != actor) throw ForbiddenException("본인이 만든 행사만 수정·취소할 수 있습니다")
        return e
    }
}

@Service
class EventQueryService(
    private val guard: AccessGuard,
    private val events: SchoolEventPort,
    private val targets: EventTargetPort,
    private val responses: RsvpResponsePort,
    private val views: EventViewAssembler,
    private val students: StudentPort,
    private val enrollments: EnrollmentPort,
    private val classrooms: ClassroomPort,
    private val users: UserPort,
    private val sheets: SpreadsheetPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : EventQuery {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, upcomingOnly: Boolean, page: Int, size: Int): PageResult<EventView> {
        if (page < 0 || size !in 1..100) throw InvalidInputException("INVALID_PAGE", "page ≥ 0, size 1~100")
        val m = guard.requireStaff(actor, institutionId)
        val from = if (upcomingOnly) clock.now().minus(Duration.ofDays(1)) else null
        return events.search(institutionId, if (m.role.isManager) null else actor, from, page, size).map { views.view(it) }
    }

    @Transactional(readOnly = true)
    override fun summary(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): EventSummary {
        val e = readable(actor, institutionId, id)
        val targetIds = targets.findTargetStudents(id)
        val rs = responses.findByEvent(id).associateBy { it.studentId }
        val names = students.findAllByIds(targetIds).associate { it.id to it.name }
        val classNames = classrooms.findByInstitution(institutionId).associate { it.id to it.name }
        val responders = rs.values.map { it.respondedBy }.distinct().associateWith { users.findById(it)?.name ?: "(알 수 없음)" }
        val rows = targetIds.map { sid ->
            val r = rs[sid]
            RsvpRow(
                studentId = sid, studentName = names[sid] ?: "(알 수 없음)",
                classroomNames = enrollments.findCurrent(sid).mapNotNull { classNames[it.classroomId] },
                answer = r?.answer, reason = r?.reason, respondedAt = r?.respondedAt, respondedByName = r?.let { responders[it.respondedBy] },
            )
        }.sortedWith(compareBy({ it.answer?.ordinal ?: -1 }, { it.studentName })) // 미응답 먼저
        return EventSummary(views.view(e, withTally = false), RsvpTally.of(targetIds, rs.values).toTally(), rows)
    }

    /** 명단 엑셀: 학생명·반·응답·사유·응답시각 (스펙 7-6) */
    @Transactional
    override fun exportResponses(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): ExportedFile {
        val s = summary(actor, institutionId, id)
        val bytes = sheets.write(
            "참석 명단",
            listOf("학생명", "반", "응답", "사유", "응답 시각", "응답자"),
            s.rows.map { r ->
                listOf(
                    r.studentName, r.classroomNames.joinToString(", "),
                    when (r.answer) { RsvpAnswer.ATTEND -> "참석"; RsvpAnswer.ABSENT -> "불참"; null -> "미응답" },
                    r.reason.orEmpty(), r.respondedAt?.let(fmt::format).orEmpty(), r.respondedByName.orEmpty(),
                )
            },
            listOf("${s.event.title} · 참석 ${s.tally.attend} / 불참 ${s.tally.absent} / 미응답 ${s.tally.pending}"),
        )
        audit.record(
            AuditEntry(institutionId, actor, "EVENT_RSVP_EXPORT", "event", id.value.toString(), mapOf("rows" to s.rows.size), clock.now()),
        )
        return ExportedFile("${s.event.title.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")}_참석명단.xlsx", bytes)
    }

    private fun readable(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): SchoolEvent {
        val m = guard.requireStaff(actor, institutionId)
        val e = events.find(id, institutionId) ?: throw NotFoundException("행사")
        if (!m.role.isManager && e.authorId != actor) throw ForbiddenException("본인이 만든 행사만 볼 수 있습니다")
        return e
    }
}

@Service
class RemindEventService(
    private val guard: AccessGuard,
    private val events: SchoolEventPort,
    private val targets: EventTargetPort,
    private val responses: RsvpResponsePort,
    private val notifier: EventNotifier,
    private val clock: ClockPort,
) : RemindEventUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun remind(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): Int {
        val m = guard.requireStaff(actor, institutionId)
        val e = events.findForUpdate(id, institutionId) ?: throw NotFoundException("행사")
        if (!m.role.isManager && e.authorId != actor) throw ForbiddenException("본인이 만든 행사만 독촉할 수 있습니다")
        val now = clock.now()
        val reminded = e.remind(now)
        val pending = pendingStudents(e)
        if (pending.isEmpty()) throw com.ttokttok.domain.common.ConflictException("NOTHING_TO_REMIND", "미응답 원생이 없습니다")
        events.save(reminded)
        return notifier.notify(reminded, pending, EventNotifier.Kind.REMINDER, now)
    }

    /** 5분마다: 마감 N시간 전에 들어온 행사 1회 자동 독촉 */
    @Transactional
    override fun remindDue(limit: Int): Int {
        val now = clock.now()
        var count = 0
        events.lockReminderCandidates(now, limit).filter { it.isReminderDue(now) }.forEach { e ->
            events.save(e.copy(remindedAt = now))
            val pending = pendingStudents(e)
            if (pending.isNotEmpty()) {
                val users = notifier.notify(e, pending, EventNotifier.Kind.REMINDER, now)
                log.info("행사 자동 독촉 {} — 미응답 {}명, 보호자 {}명", e.id.value, pending.size, users)
            }
            count++
        }
        return count
    }

    private fun pendingStudents(e: SchoolEvent): List<StudentId> {
        val answered = responses.findByEvent(e.id).map { it.studentId }.toSet()
        return targets.findTargetStudents(e.id).filter { it !in answered }
    }
}

@Service
class ParentEventService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val events: SchoolEventPort,
    private val targets: EventTargetPort,
    private val responses: RsvpResponsePort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : ParentEventUseCase {

    @Transactional(readOnly = true)
    override fun list(parent: UserId, filter: Set<StudentId>?, includePast: Boolean): List<ParentEventItem> {
        val mine = guardians.findLinkedByUser(parent).map { it.studentId }.toSet()
        if (filter != null && !mine.containsAll(filter)) throw ForbiddenException("본인 자녀가 아닙니다")
        val kids = filter ?: mine
        if (kids.isEmpty()) return emptyList()
        val now = clock.now()
        val ids = targets.findEventIdsForStudents(kids, if (includePast) null else now.minus(Duration.ofDays(1)), 100)
        return items(ids, kids, now)
    }

    @Transactional
    override fun respond(parent: UserId, id: SchoolEventId, studentId: StudentId, answer: RsvpAnswer, reason: String?): ParentEventItem {
        val mine = guardians.findLinkedByUser(parent).map { it.studentId }.toSet()
        if (studentId !in mine) throw ForbiddenException("본인 자녀가 아닙니다")
        val event = events.findAllByIds(listOf(id)).firstOrNull() ?: throw NotFoundException("행사")
        if (studentId !in targets.findTargetStudents(id)) throw NotFoundException("행사")
        val now = clock.now()
        event.requireOpen(now)
        responses.upsert(RsvpResponse(id, studentId, answer, reason?.trim()?.ifEmpty { null }, parent, now))

        val tally = RsvpTally.of(targets.findTargetStudents(id), responses.findByEvent(id))
        realtime.institutionEvent(
            event.institutionId,
            mapOf(
                "type" to "event.responded", "eventId" to id.value.toString(),
                "attend" to tally.attend, "absent" to tally.absent, "pending" to tally.pending,
            ),
        )
        return items(listOf(id), mine, now).first()
    }

    private fun items(ids: List<SchoolEventId>, kids: Set<StudentId>, now: Instant): List<ParentEventItem> {
        if (ids.isEmpty()) return emptyList()
        val list = events.findAllByIds(ids)
        val rs = responses.findByEvents(ids).groupBy { it.eventId }
        val names = students.findAllByIds(kids).associate { it.id to it.name }
        val insts = institutions.findAllByIds(list.map { it.institutionId }.toSet()).associate { it.id to it.name }
        return list.map { e ->
            val targetKids = targets.findTargetStudents(e.id).filter { it in kids }
            val byStudent = rs[e.id].orEmpty().associateBy { it.studentId }
            ParentEventItem(
                id = e.id, institutionId = e.institutionId, institutionName = insts[e.institutionId] ?: "",
                title = e.title, body = e.body, location = e.location, startsAt = e.startsAt, endsAt = e.endsAt,
                status = e.status, rsvpEnabled = e.rsvpEnabled, rsvpDeadline = e.rsvpDeadline,
                open = e.isOpenForResponse(now),
                children = targetKids.map { sid ->
                    val r = byStudent[sid]
                    ChildRsvp(sid, names[sid] ?: "", r?.answer, r?.reason, r?.respondedAt)
                },
            )
        }.sortedWith(compareBy({ it.status == SchoolEventStatus.CANCELED }, { it.startsAt }))
    }
}
