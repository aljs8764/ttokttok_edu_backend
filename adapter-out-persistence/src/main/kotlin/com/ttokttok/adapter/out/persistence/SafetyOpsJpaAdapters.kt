package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.AttendanceMissingAlertEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomProgressEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomTextbookEntity
import com.ttokttok.adapter.out.persistence.entity.EventReviewEntity
import com.ttokttok.adapter.out.persistence.repository.AlbumJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomProgressJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ClassroomTextbookJpaRepository
import com.ttokttok.adapter.out.persistence.repository.EventReviewJpaRepository
import com.ttokttok.adapter.out.persistence.repository.GuardianJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InstitutionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SchoolEventJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SchoolEventTargetJpaRepository
import com.ttokttok.application.port.`in`.CurriculumUseCase
import com.ttokttok.application.port.`in`.EventAlbumLink
import com.ttokttok.application.port.`in`.EventReviewView
import com.ttokttok.application.port.`in`.MissingAlertPolicy
import com.ttokttok.application.port.`in`.MissingAlertView
import com.ttokttok.application.port.`in`.ProgressView
import com.ttokttok.application.port.`in`.ReadRankRow
import com.ttokttok.application.port.`in`.TextbookView
import com.ttokttok.application.port.out.CurriculumPort
import com.ttokttok.application.port.out.EventBrief
import com.ttokttok.application.port.out.EventFollowUpPort
import com.ttokttok.application.port.out.MissingAlertPort
import com.ttokttok.application.port.out.MissingCandidate
import com.ttokttok.application.port.out.ReadAgg
import com.ttokttok.application.port.out.WorkAnalyticsPort
import com.ttokttok.application.port.out.WorkStaff
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// ───────── ATT-010 미등원 초과 경고 ─────────
@Component
class MissingAlertJpaAdapter(
    private val institutions: InstitutionJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val guardians: GuardianJpaRepository,
    private val q: JpaQueries,
) : MissingAlertPort {

    override fun policy(institutionId: InstitutionId): MissingAlertPolicy =
        institutions.findByIdOrNull(institutionId.value)!!.let { MissingAlertPolicy(it.missingAlertMinutes, it.missingAlertParent) }

    override fun setPolicy(institutionId: InstitutionId, policy: MissingAlertPolicy) {
        institutions.setMissingAlertPolicy(institutionId.value, policy.minutes, policy.notifyParent, Instant.now())
    }

    /** 반 시작 시각(서울) + 기준 분이 지난 미등원 — 날짜·시간대 연산이 많아 네이티브 쿼리 */
    override fun candidates(today: LocalDate, now: Instant, windowStart: Instant): List<MissingCandidate> =
        q.rows(
            """with base as (
                   select ad.id, ad.institution_id, i.name as institution_name, ad.student_id, s.name as student_name, s.child_id,
                          ad.classroom_id, c.name as classroom_name,
                          ((ad.date + c.start_time) at time zone 'Asia/Seoul') as class_start,
                          i.missing_alert_minutes as minutes, i.missing_alert_parent as notify_parent,
                          (select ar.expected_time from absence_request ar
                            where ar.student_id = ad.student_id and ar.date = ad.date and ar.kind = 'LATE' and ar.status <> 'CANCELLED'
                            limit 1) as late_expected
                     from attendance_day ad
                     join institution i on i.id = ad.institution_id
                     join student s on s.id = ad.student_id
                     join classroom c on c.id = ad.classroom_id
                    where ad.date = :d and ad.status = 'SCHEDULED'
                      and i.missing_alert_minutes > 0 and i.service_status = 'ACTIVE' and not i.is_sample
                      and c.deleted_at is null
                      and not exists (select 1 from attendance_missing_alert x where x.attendance_day_id = ad.id)
                      and not exists (select 1 from absence_request ar where ar.student_id = ad.student_id and ar.date = ad.date
                                        and ar.kind = 'ABSENT' and ar.status <> 'CANCELLED')
               )
               select * from base
                where class_start + make_interval(mins => minutes) <= :now
                  and class_start + make_interval(mins => minutes) >= :ws
                order by class_start""",
            params("d" to today, "now" to now, "ws" to windowStart),
        ).map { rs ->
            MissingCandidate(
                rs.uuid("id"), rs.uuid("institution_id"), rs.str("institution_name"), rs.uuid("student_id"), rs.str("student_name"),
                rs.uuidOrNull("child_id"), rs.uuid("classroom_id"), rs.str("classroom_name"), rs.instant("class_start"),
                rs.int("minutes"), rs.bool("notify_parent"), rs.timeOrNull("late_expected"),
            )
        }

    override fun previousCheckout(childId: UUID, excludeInstitution: UUID, date: LocalDate, before: Instant): Pair<String, Instant>? =
        q.jpqlRows(
            """select i.name, ad.checkOutAt
                 from AttendanceDayEntity ad
                 join StudentEntity s on s.id = ad.studentId
                 join InstitutionEntity i on i.id = ad.institutionId
                where s.childId = :c and ad.institutionId <> :x and ad.date = :d
                  and ad.checkOutAt is not null and ad.checkOutAt <= :b
                order by ad.checkOutAt desc""",
            params("c" to childId, "x" to excludeInstitution, "d" to date, "b" to before), limit = 1,
        ).firstOrNull()?.let { (it[0] as String) to (it[1] as Instant) }

    override fun record(c: MissingCandidate, previous: Pair<String, Instant>?, notifiedParent: Boolean, now: Instant): Boolean =
        q.update(
            """insert into attendance_missing_alert (attendance_day_id, institution_id, student_id, classroom_id,
                   previous_institution_name, previous_checkout_at, notified_parent, sent_at)
               values (:id, :i, :s, :c, :pn, :pa, :np, :now)
               on conflict (attendance_day_id) do nothing""",
            params(
                "id" to c.attendanceDayId, "i" to c.institutionId, "s" to c.studentId, "c" to c.classroomId,
                "pn" to typed(previous?.first), "pa" to typed(previous?.second), "np" to notifiedParent, "now" to now,
            ),
        ) == 1

    override fun list(institutionId: InstitutionId, date: LocalDate, classroomIds: Collection<UUID>?): List<MissingAlertView> {
        if (classroomIds != null && classroomIds.isEmpty()) return emptyList()
        val p = params("i" to institutionId.value, "d" to date)
        val classFilter = if (classroomIds != null) {
            p["cs"] = classroomIds
            " and x.classroomId in (:cs)"
        } else ""
        return q.jpqlRows(
            """select x, s.name, c.name, c.startTime, ad.status
                 from AttendanceMissingAlertEntity x
                 join AttendanceDayEntity ad on ad.id = x.attendanceDayId
                 join StudentEntity s on s.id = x.studentId
                 join ClassroomEntity c on c.id = x.classroomId
                where x.institutionId = :i and ad.date = :d$classFilter
                order by x.sentAt desc""",
            p,
        ).map { r ->
            val x = r[0] as AttendanceMissingAlertEntity
            MissingAlertView(
                x.attendanceDayId, x.studentId, r[1] as String, x.classroomId,
                r[2] as String, (r[3] as LocalTime).format(HHMM), x.previousInstitutionName,
                x.previousCheckoutAt, x.notifiedParent, x.sentAt, r[4] as String,
            )
        }
    }

    /** 원장·실장 + 그 반 담당 교사(기관 소속) */
    override fun staffRecipients(institutionId: UUID, classroomId: UUID): List<UserId> {
        val managers = memberships.findByInstitutionIdAndRoleIn(institutionId, listOf("OWNER", "ADMIN")).map { it.userId }
        val teachers = q.jpql(
            """select ct.userId from ClassroomTeacherEntity ct, MembershipEntity m
                where m.userId = ct.userId and m.institutionId = :i and ct.classroomId = :c""",
            UUID::class.java, params("i" to institutionId, "c" to classroomId),
        )
        return (managers + teachers).distinct().map(::UserId)
    }

    override fun guardians(studentId: UUID): List<UserId> = guardians.findLinkedUserIds(studentId).map(::UserId)
}

// ───────── STAT-007 · STF-005 ─────────
@Component
/** 열람률·응답 시간 같은 업무 분석 집계 (with·lateral·filter) — 읽기 전용 네이티브 쿼리 */
class WorkAnalyticsJpaAdapter(private val q: JpaQueries) : WorkAnalyticsPort {

    private companion object {
        /** 기간 안 발송된 알림장의 (알림장, 원생) 단위 열람 여부. 앱 연결 보호자가 있는 원생만 대상 */
        const val PER_STUDENT = """
            with ns as (
                select n.id as notice_id, n.author_id, r.student_id, bool_or(r.read_at is not null) as is_read
                  from notice n
                  join notice_recipient r on r.notice_id = n.id
                 where n.institution_id = :i and n.status = 'SENT' and n.sent_at >= :a and n.sent_at < :b
                   and r.guardian_user_id is not null
                 group by n.id, n.author_id, r.student_id
            )
        """
        fun rank(rs: Row): ReadRankRow {
            val targets = rs.int("targets")
            val read = rs.int("read_cnt")
            return ReadRankRow(
                rs.uuid("id"), rs.str("name"), rs.int("notices"), targets, read,
                if (targets > 0) read.toDouble() / targets else null, false,
            )
        }
    }

    private fun range(i: InstitutionId, a: Instant, b: Instant) = params("i" to i.value, "a" to a, "b" to b)

    override fun readByClassroom(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow> =
        q.rows(
            """$PER_STUDENT
               select c.id, c.name, count(distinct ns.notice_id) as notices, count(*) as targets, count(*) filter (where ns.is_read) as read_cnt
                 from ns
                 join enrollment e on e.student_id = ns.student_id and e.to_date is null
                 join classroom c on c.id = e.classroom_id and c.deleted_at is null
                group by c.id, c.name""",
            range(institutionId, from, to),
        ).map(::rank)

    override fun readByAuthor(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow> =
        q.rows(
            """$PER_STUDENT
               select u.id, u.name, count(distinct ns.notice_id) as notices, count(*) as targets, count(*) filter (where ns.is_read) as read_cnt
                 from ns join app_user u on u.id = ns.author_id
                group by u.id, u.name""",
            range(institutionId, from, to),
        ).map(::rank)

    override fun readOverall(institutionId: InstitutionId, from: Instant, to: Instant): ReadAgg =
        q.row(
            """$PER_STUDENT
               select count(*) as targets, count(*) filter (where is_read) as read_cnt from ns""",
            range(institutionId, from, to),
        )!!.let { rs -> ReadAgg(rs.int("targets"), rs.int("read_cnt")) }

    override fun staff(institutionId: InstitutionId): List<WorkStaff> =
        q.rows(
            """select u.id, u.name, m.role from membership m join app_user u on u.id = m.user_id
                where m.institution_id = :i and m.role in ('OWNER', 'ADMIN', 'TEACHER')
                order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, u.name""",
            params("i" to institutionId.value),
        ).map { rs -> WorkStaff(rs.uuid("id"), rs.str("name"), rs.str("role")) }

    private fun countBy(sql: String, p: Map<String, Any?>): Map<UUID, Int> =
        q.rows(sql, p).associate { rs -> rs.uuid("uid") to rs.int("cnt") }

    private fun avgBy(sql: String, p: Map<String, Any?>): Map<UUID, Double> =
        q.rows(sql, p).mapNotNull { rs -> rs.doubleOrNull("avg_min")?.let { rs.uuid("uid") to it } }.toMap()

    override fun noticesSent(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int> =
        countBy(
            """select author_id as uid, count(*) as cnt from notice
                where institution_id = :i and status = 'SENT' and sent_at >= :a and sent_at < :b group by author_id""",
            range(institutionId, from, to),
        )

    override fun repliesSent(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int> =
        countBy(
            """select m.sender_id as uid, count(*) as cnt from message m join conversation cv on cv.id = m.conversation_id
                where cv.institution_id = :i and m.sender_side = 'STAFF' and m.created_at >= :a and m.created_at < :b
                group by m.sender_id""",
            range(institutionId, from, to),
        )

    override fun avgReplyMinutes(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Double> =
        avgBy(
            """select r.sender_id as uid, avg(extract(epoch from (r.created_at - pm.created_at)) / 60.0) as avg_min
                 from message pm
                 join conversation cv on cv.id = pm.conversation_id
                 join lateral (
                     select sm.sender_id, sm.created_at from message sm
                      where sm.conversation_id = pm.conversation_id and sm.sender_side = 'STAFF' and sm.created_at > pm.created_at
                      order by sm.created_at limit 1
                 ) r on true
                where cv.institution_id = :i and pm.sender_side = 'PARENT' and pm.created_at >= :a and pm.created_at < :b
                  -- 연속된 학부모 메시지는 대화의 첫 메시지만 센다 (직전 메시지가 교직원이거나 없을 때)
                  and coalesce((select pv.sender_side from message pv
                                 where pv.conversation_id = pm.conversation_id and pv.created_at < pm.created_at
                                 order by pv.created_at desc limit 1), 'STAFF') = 'STAFF'
                group by r.sender_id""",
            range(institutionId, from, to),
        )

    override fun unansweredMessages(institutionId: InstitutionId, from: Instant, to: Instant): Int =
        q.count(
            """select count(*) from message pm join conversation cv on cv.id = pm.conversation_id
                where cv.institution_id = :i and pm.sender_side = 'PARENT' and pm.created_at >= :a and pm.created_at < :b
                  and not exists (select 1 from message sm where sm.conversation_id = pm.conversation_id
                                    and sm.sender_side = 'STAFF' and sm.created_at > pm.created_at)""",
            range(institutionId, from, to),
        ).toInt()

    override fun attendanceActions(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int> =
        countBy(
            """select actor_id as uid, count(*) as cnt from attendance_event
                where institution_id = :i and source in ('TEACHER_APP', 'ADMIN_WEB') and occurred_at >= :a and occurred_at < :b
                group by actor_id""",
            range(institutionId, from, to),
        )

    override fun avgCheckInOffset(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Double> =
        avgBy(
            """select ev.actor_id as uid,
                      avg(extract(epoch from (ev.occurred_at - ((ad.date + c.start_time) at time zone 'Asia/Seoul'))) / 60.0) as avg_min
                 from attendance_event ev
                 join attendance_day ad on ad.id = ev.attendance_day_id
                 join classroom c on c.id = ad.classroom_id
                where ev.institution_id = :i and ev.type = 'CHECK_IN' and ev.source in ('TEACHER_APP', 'ADMIN_WEB')
                  and ev.occurred_at >= :a and ev.occurred_at < :b
                group by ev.actor_id""",
            range(institutionId, from, to),
        )

    override fun consultsDone(institutionId: InstitutionId, from: Instant, to: Instant): Map<UUID, Int> =
        countBy(
            """select teacher_id as uid, count(*) as cnt from consult_slot
                where institution_id = :i and status = 'DONE' and starts_at >= :a and starts_at < :b group by teacher_id""",
            range(institutionId, from, to),
        )
}

// ───────── CLS-005 교재·진도 ─────────
@Component
class CurriculumJpaAdapter(
    private val textbooks: ClassroomTextbookJpaRepository,
    private val progresses: ClassroomProgressJpaRepository,
    private val q: JpaQueries,
) : CurriculumPort {

    private companion object {
        const val PROGRESS = """
            select p, c.name, t.name, u.name
              from ClassroomProgressEntity p
              join ClassroomEntity c on c.id = p.classroomId
              join UserEntity u on u.id = p.authorId
              left join ClassroomTextbookEntity t on t.id = p.textbookId
        """

        fun textbook(t: ClassroomTextbookEntity) = t.institutionId to TextbookView(t.id, t.classroomId, t.name, t.publisher, t.memo, t.archived)

        fun progressView(r: Array<Any?>): Pair<UUID, ProgressView> {
            val p = r[0] as ClassroomProgressEntity
            return p.institutionId to ProgressView(
                p.id, p.classroomId, r[1] as String, p.date,
                p.textbookId, r[2] as String?, p.content, p.homework,
                r[3] as String, p.updatedAt,
            )
        }
    }

    override fun textbooks(classroomId: UUID, includeArchived: Boolean): List<TextbookView> =
        (if (includeArchived) textbooks.findByClassroomIdOrderByArchivedAscCreatedAtAsc(classroomId)
        else textbooks.findByClassroomIdAndArchivedFalseOrderByCreatedAt(classroomId)).map { textbook(it).second }

    override fun findTextbook(id: UUID): Pair<UUID, TextbookView>? = textbooks.findByIdOrNull(id)?.let(::textbook)

    override fun insertTextbook(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveTextbook, now: Instant): UUID {
        val id = Uuid7.next()
        textbooks.save(ClassroomTextbookEntity(id, institutionId.value, classroomId, command.name, command.publisher, command.memo, command.archived, now))
        return id
    }

    override fun updateTextbook(id: UUID, command: CurriculumUseCase.SaveTextbook) {
        val t = textbooks.findByIdOrNull(id) ?: return
        t.name = command.name
        t.publisher = command.publisher
        t.memo = command.memo
        t.archived = command.archived
        textbooks.save(t)
    }

    override fun progress(classroomIds: Collection<UUID>, from: LocalDate?, to: LocalDate?, limit: Int): List<ProgressView> {
        if (classroomIds.isEmpty()) return emptyList()
        val p = params("cs" to classroomIds)
        val where = buildString {
            append(" where p.classroomId in (:cs)")
            if (from != null) { append(" and p.date >= :f"); p["f"] = from }
            if (to != null) { append(" and p.date <= :t"); p["t"] = to }
        }
        return q.jpqlRows("$PROGRESS$where order by p.date desc, p.createdAt desc", p, limit = limit).map { progressView(it).second }
    }

    override fun findProgress(id: UUID): Pair<UUID, ProgressView>? = q.jpqlRows("$PROGRESS where p.id = :id", params("id" to id)).firstOrNull()?.let(::progressView)

    override fun insertProgress(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveProgress, author: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        progresses.save(
            ClassroomProgressEntity(
                id, institutionId.value, classroomId, command.date, command.textbookId, command.content, command.homework, author.value, now, now,
            ),
        )
        return id
    }

    override fun updateProgress(id: UUID, command: CurriculumUseCase.SaveProgress, now: Instant) {
        val p = progresses.findByIdOrNull(id) ?: return
        p.date = command.date
        p.textbookId = command.textbookId
        p.content = command.content
        p.homework = command.homework
        p.updatedAt = now
        progresses.save(p)
    }

    override fun deleteProgress(id: UUID) {
        if (progresses.existsById(id)) progresses.deleteById(id)
    }
}

// ───────── EVT-009 행사 후기·사진 ─────────
@Component
class EventFollowUpJpaAdapter(
    private val events: SchoolEventJpaRepository,
    private val targets: SchoolEventTargetJpaRepository,
    private val albums: AlbumJpaRepository,
    private val reviews: EventReviewJpaRepository,
    private val q: JpaQueries,
) : EventFollowUpPort {

    override fun event(id: UUID): EventBrief? = events.findByIdOrNull(id)?.let { EventBrief(it.id, it.institutionId, it.title, it.startsAt) }

    override fun albums(eventId: UUID, visibleToStudents: Collection<UUID>?): List<EventAlbumLink> {
        if (visibleToStudents != null && visibleToStudents.isEmpty()) return emptyList()
        val p = params("e" to eventId)
        val vis = if (visibleToStudents != null) {
            p["ss"] = visibleToStudents
            " and exists (select 1 from AlbumRecipientEntity ar where ar.albumId = a.id and ar.studentId in (:ss))"
        } else ""
        return q.jpqlRows(
            """select a.id, a.title, a.createdAt, (select count(ai) from AlbumItemEntity ai where ai.albumId = a.id)
                 from AlbumEntity a where a.eventId = :e$vis order by a.createdAt desc""",
            p,
        ).map { EventAlbumLink(it[0] as UUID, it[1] as String, (it[3] as Number).toInt(), it[2] as Instant) }
    }

    override fun albumInstitution(albumId: UUID): UUID? = albums.findByIdOrNull(albumId)?.institutionId

    override fun setAlbumEvent(albumId: UUID, eventId: UUID?) {
        val a = albums.findByIdOrNull(albumId) ?: return
        a.eventId = eventId
        albums.save(a)
    }

    override fun targetStudents(eventId: UUID): Set<UUID> = targets.findByEventId(eventId).map { it.studentId }.toSet()

    private val reviewSelect = """
        select r, s.name, u.name
          from EventReviewEntity r join StudentEntity s on s.id = r.studentId join UserEntity u on u.id = r.userId
    """

    private fun review(r: Array<Any?>, viewer: UserId?): Pair<UUID, EventReviewView> {
        val e = r[0] as EventReviewEntity
        return e.eventId to EventReviewView(
            e.id, e.eventId, r[1] as String, r[2] as String, e.body,
            e.hidden, viewer != null && e.userId == viewer.value, e.createdAt,
        )
    }

    override fun reviews(eventId: UUID, viewer: UserId?, includeHidden: Boolean): List<EventReviewView> {
        val p = params("e" to eventId)
        // 숨김 후기는 교직원과 작성자 본인만 본다
        val hiddenFilter = when {
            includeHidden -> ""
            viewer != null -> { p["v"] = viewer.value; " and (r.hidden = false or r.userId = :v)" }
            else -> " and r.hidden = false"
        }
        return q.jpqlRows("$reviewSelect where r.eventId = :e$hiddenFilter order by r.createdAt desc", p).map { review(it, viewer).second }
    }

    override fun findReview(id: UUID): Pair<UUID, EventReviewView>? =
        q.jpqlRows("$reviewSelect where r.id = :id", params("id" to id)).firstOrNull()?.let { review(it, null) }

    /** (행사, 작성자) 당 1건 — 있으면 원생·본문만 바꾼다 */
    override fun upsertReview(eventId: UUID, studentId: UUID, user: UserId, body: String, now: Instant) {
        val r = reviews.findByEventIdAndUserId(eventId, user.value)
            ?: EventReviewEntity(Uuid7.next(), eventId, studentId, user.value, body, createdAt = now, updatedAt = now)
        r.studentId = studentId
        r.body = body
        r.updatedAt = now
        reviews.save(r)
    }

    override fun setHidden(reviewId: UUID, hidden: Boolean, now: Instant) {
        val r = reviews.findByIdOrNull(reviewId) ?: return
        r.hidden = hidden
        r.updatedAt = now
        reviews.save(r)
    }

    override fun deleteReview(eventId: UUID, user: UserId) {
        reviews.deleteByEventAndUser(eventId, user.value)
    }
}
