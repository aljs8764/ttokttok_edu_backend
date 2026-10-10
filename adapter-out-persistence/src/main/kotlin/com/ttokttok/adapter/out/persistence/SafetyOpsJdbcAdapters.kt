package com.ttokttok.adapter.out.persistence

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun ResultSet.soId(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.soTs(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.soDbl(col: String): Double? = getDouble(col).takeUnless { wasNull() }
private fun soTime(i: Instant?) = i?.let { Timestamp.from(it) }
private fun soDate(d: LocalDate?) = d?.let { Date.valueOf(it) }
private fun soP(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

// ───────── ATT-010 미등원 초과 경고 ─────────
@Component
class MissingAlertJdbcAdapter(jdbc: JdbcTemplate) : MissingAlertPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun policy(institutionId: InstitutionId): MissingAlertPolicy =
        named.queryForObject(
            "select missing_alert_minutes, missing_alert_parent from institution where id = :i", soP("i" to institutionId.value),
        ) { rs, _ -> MissingAlertPolicy(rs.getInt("missing_alert_minutes"), rs.getBoolean("missing_alert_parent")) }!!

    override fun setPolicy(institutionId: InstitutionId, policy: MissingAlertPolicy) {
        named.update(
            "update institution set missing_alert_minutes = :m, missing_alert_parent = :p, updated_at = now() where id = :i",
            soP("m" to policy.minutes, "p" to policy.notifyParent, "i" to institutionId.value),
        )
    }

    override fun candidates(today: LocalDate, now: Instant, windowStart: Instant): List<MissingCandidate> =
        named.query(
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
            soP("d" to soDate(today), "now" to soTime(now), "ws" to soTime(windowStart)),
        ) { rs, _ ->
            MissingCandidate(
                rs.soId("id")!!, rs.soId("institution_id")!!, rs.getString("institution_name"), rs.soId("student_id")!!, rs.getString("student_name"),
                rs.soId("child_id"), rs.soId("classroom_id")!!, rs.getString("classroom_name"), rs.soTs("class_start")!!,
                rs.getInt("minutes"), rs.getBoolean("notify_parent"), rs.getTime("late_expected")?.toLocalTime(),
            )
        }

    override fun previousCheckout(childId: UUID, excludeInstitution: UUID, date: LocalDate, before: Instant): Pair<String, Instant>? =
        named.query(
            """select i.name, ad.check_out_at
                 from attendance_day ad
                 join student s on s.id = ad.student_id
                 join institution i on i.id = ad.institution_id
                where s.child_id = :c and ad.institution_id <> :x and ad.date = :d
                  and ad.check_out_at is not null and ad.check_out_at <= :b
                order by ad.check_out_at desc limit 1""",
            soP("c" to childId, "x" to excludeInstitution, "d" to soDate(date), "b" to soTime(before)),
        ) { rs, _ -> rs.getString("name") to rs.soTs("check_out_at")!! }.firstOrNull()

    override fun record(c: MissingCandidate, previous: Pair<String, Instant>?, notifiedParent: Boolean, now: Instant): Boolean =
        named.update(
            """insert into attendance_missing_alert (attendance_day_id, institution_id, student_id, classroom_id,
                   previous_institution_name, previous_checkout_at, notified_parent, sent_at)
               values (:id, :i, :s, :c, :pn, :pa, :np, :now)
               on conflict (attendance_day_id) do nothing""",
            soP(
                "id" to c.attendanceDayId, "i" to c.institutionId, "s" to c.studentId, "c" to c.classroomId,
                "pn" to previous?.first, "pa" to soTime(previous?.second), "np" to notifiedParent, "now" to soTime(now),
            ),
        ) == 1

    override fun list(institutionId: InstitutionId, date: LocalDate, classroomIds: Collection<UUID>?): List<MissingAlertView> {
        if (classroomIds != null && classroomIds.isEmpty()) return emptyList()
        val classFilter = if (classroomIds != null) "and x.classroom_id in (:cs)" else ""
        return named.query(
            """select x.attendance_day_id, x.student_id, s.name as student_name, x.classroom_id, c.name as classroom_name,
                      to_char(c.start_time, 'HH24:MI') as class_start, x.previous_institution_name, x.previous_checkout_at,
                      x.notified_parent, x.sent_at, ad.status
                 from attendance_missing_alert x
                 join attendance_day ad on ad.id = x.attendance_day_id
                 join student s on s.id = x.student_id
                 join classroom c on c.id = x.classroom_id
                where x.institution_id = :i and ad.date = :d $classFilter
                order by x.sent_at desc""",
            soP("i" to institutionId.value, "d" to soDate(date), "cs" to classroomIds),
        ) { rs, _ ->
            MissingAlertView(
                rs.soId("attendance_day_id")!!, rs.soId("student_id")!!, rs.getString("student_name"), rs.soId("classroom_id")!!,
                rs.getString("classroom_name"), rs.getString("class_start"), rs.getString("previous_institution_name"),
                rs.soTs("previous_checkout_at"), rs.getBoolean("notified_parent"), rs.soTs("sent_at")!!, rs.getString("status"),
            )
        }
    }

    override fun staffRecipients(institutionId: UUID, classroomId: UUID): List<UserId> =
        named.query(
            """select m.user_id from membership m where m.institution_id = :i and m.role in ('OWNER', 'ADMIN')
               union
               select ct.user_id from classroom_teacher ct
                 join membership m on m.user_id = ct.user_id and m.institution_id = :i
                where ct.classroom_id = :c""",
            soP("i" to institutionId, "c" to classroomId),
        ) { rs, _ -> UserId(rs.soId("user_id")!!) }

    override fun guardians(studentId: UUID): List<UserId> =
        named.query(
            "select distinct user_id from guardian where student_id = :s and user_id is not null and link_status = 'LINKED'",
            soP("s" to studentId),
        ) { rs, _ -> UserId(rs.soId("user_id")!!) }
}

// ───────── STAT-007 · STF-005 ─────────
@Component
class WorkAnalyticsJdbcAdapter(jdbc: JdbcTemplate) : WorkAnalyticsPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

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
        fun rankMapper() = RowMapper<ReadRankRow> { rs, _ ->
            val targets = rs.getInt("targets")
            val read = rs.getInt("read_cnt")
            ReadRankRow(
                rs.soId("id")!!, rs.getString("name"), rs.getInt("notices"), targets, read,
                if (targets > 0) read.toDouble() / targets else null, false,
            )
        }
    }

    private fun range(i: InstitutionId, a: Instant, b: Instant) = soP("i" to i.value, "a" to soTime(a), "b" to soTime(b))

    override fun readByClassroom(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow> =
        named.query(
            """$PER_STUDENT
               select c.id, c.name, count(distinct ns.notice_id) as notices, count(*) as targets, count(*) filter (where ns.is_read) as read_cnt
                 from ns
                 join enrollment e on e.student_id = ns.student_id and e.to_date is null
                 join classroom c on c.id = e.classroom_id and c.deleted_at is null
                group by c.id, c.name""",
            range(institutionId, from, to), rankMapper(),
        )

    override fun readByAuthor(institutionId: InstitutionId, from: Instant, to: Instant): List<ReadRankRow> =
        named.query(
            """$PER_STUDENT
               select u.id, u.name, count(distinct ns.notice_id) as notices, count(*) as targets, count(*) filter (where ns.is_read) as read_cnt
                 from ns join app_user u on u.id = ns.author_id
                group by u.id, u.name""",
            range(institutionId, from, to), rankMapper(),
        )

    override fun readOverall(institutionId: InstitutionId, from: Instant, to: Instant): ReadAgg =
        named.queryForObject(
            """$PER_STUDENT
               select count(*) as targets, count(*) filter (where is_read) as read_cnt from ns""",
            range(institutionId, from, to),
        ) { rs, _ -> ReadAgg(rs.getInt("targets"), rs.getInt("read_cnt")) }!!

    override fun staff(institutionId: InstitutionId): List<WorkStaff> =
        named.query(
            """select u.id, u.name, m.role from membership m join app_user u on u.id = m.user_id
                where m.institution_id = :i and m.role in ('OWNER', 'ADMIN', 'TEACHER')
                order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, u.name""",
            soP("i" to institutionId.value),
        ) { rs, _ -> WorkStaff(rs.soId("id")!!, rs.getString("name"), rs.getString("role")) }

    private fun countBy(sql: String, p: MapSqlParameterSource): Map<UUID, Int> =
        named.query(sql, p) { rs, _ -> rs.soId("uid")!! to rs.getInt("cnt") }.toMap()

    private fun avgBy(sql: String, p: MapSqlParameterSource): Map<UUID, Double> =
        named.query(sql, p) { rs, _ -> rs.soId("uid")!! to rs.soDbl("avg_min") }
            .mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

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
        named.queryForObject(
            """select count(*) from message pm join conversation cv on cv.id = pm.conversation_id
                where cv.institution_id = :i and pm.sender_side = 'PARENT' and pm.created_at >= :a and pm.created_at < :b
                  and not exists (select 1 from message sm where sm.conversation_id = pm.conversation_id
                                    and sm.sender_side = 'STAFF' and sm.created_at > pm.created_at)""",
            range(institutionId, from, to), Int::class.java,
        ) ?: 0

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
class CurriculumJdbcAdapter(jdbc: JdbcTemplate) : CurriculumPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        val TB = RowMapper<Pair<UUID, TextbookView>> { rs, _ ->
            rs.soId("institution_id")!! to TextbookView(
                rs.soId("id")!!, rs.soId("classroom_id")!!, rs.getString("name"), rs.getString("publisher"), rs.getString("memo"), rs.getBoolean("archived"),
            )
        }
        const val PROGRESS = """
            select p.id, p.institution_id, p.classroom_id, c.name as classroom_name, p.date, p.textbook_id, t.name as textbook_name,
                   p.content, p.homework, u.name as author_name, p.updated_at
              from classroom_progress p
              join classroom c on c.id = p.classroom_id
              join app_user u on u.id = p.author_id
              left join classroom_textbook t on t.id = p.textbook_id
        """
        val PG = RowMapper<Pair<UUID, ProgressView>> { rs, _ ->
            rs.soId("institution_id")!! to ProgressView(
                rs.soId("id")!!, rs.soId("classroom_id")!!, rs.getString("classroom_name"), rs.getDate("date").toLocalDate(),
                rs.soId("textbook_id"), rs.getString("textbook_name"), rs.getString("content"), rs.getString("homework"),
                rs.getString("author_name"), rs.soTs("updated_at")!!,
            )
        }
    }

    override fun textbooks(classroomId: UUID, includeArchived: Boolean): List<TextbookView> =
        named.query(
            "select * from classroom_textbook where classroom_id = :c ${if (includeArchived) "" else "and not archived"} order by archived, created_at",
            soP("c" to classroomId), TB,
        ).map { it.second }

    override fun findTextbook(id: UUID): Pair<UUID, TextbookView>? =
        named.query("select * from classroom_textbook where id = :id", soP("id" to id), TB).firstOrNull()

    override fun insertTextbook(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveTextbook, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into classroom_textbook (id, institution_id, classroom_id, name, publisher, memo, archived, created_at)
               values (:id, :i, :c, :n, :p, :m, :a, :now)""",
            soP("id" to id, "i" to institutionId.value, "c" to classroomId, "n" to command.name, "p" to command.publisher,
                "m" to command.memo, "a" to command.archived, "now" to soTime(now)),
        )
        return id
    }

    override fun updateTextbook(id: UUID, command: CurriculumUseCase.SaveTextbook) {
        named.update(
            "update classroom_textbook set name = :n, publisher = :p, memo = :m, archived = :a where id = :id",
            soP("id" to id, "n" to command.name, "p" to command.publisher, "m" to command.memo, "a" to command.archived),
        )
    }

    override fun progress(classroomIds: Collection<UUID>, from: LocalDate?, to: LocalDate?, limit: Int): List<ProgressView> {
        if (classroomIds.isEmpty()) return emptyList()
        val where = buildString {
            append("where p.classroom_id in (:cs)")
            if (from != null) append(" and p.date >= :f")
            if (to != null) append(" and p.date <= :t")
        }
        return named.query(
            "$PROGRESS $where order by p.date desc, p.created_at desc limit :lim",
            soP("cs" to classroomIds, "f" to soDate(from), "t" to soDate(to), "lim" to limit), PG,
        ).map { it.second }
    }

    override fun findProgress(id: UUID): Pair<UUID, ProgressView>? =
        named.query("$PROGRESS where p.id = :id", soP("id" to id), PG).firstOrNull()

    override fun insertProgress(institutionId: InstitutionId, classroomId: UUID, command: CurriculumUseCase.SaveProgress, author: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into classroom_progress (id, institution_id, classroom_id, date, textbook_id, content, homework, author_id, created_at, updated_at)
               values (:id, :i, :c, :d, :t, :content, :hw, :a, :now, :now)""",
            soP("id" to id, "i" to institutionId.value, "c" to classroomId, "d" to soDate(command.date), "t" to command.textbookId,
                "content" to command.content, "hw" to command.homework, "a" to author.value, "now" to soTime(now)),
        )
        return id
    }

    override fun updateProgress(id: UUID, command: CurriculumUseCase.SaveProgress, now: Instant) {
        named.update(
            "update classroom_progress set date = :d, textbook_id = :t, content = :content, homework = :hw, updated_at = :now where id = :id",
            soP("id" to id, "d" to soDate(command.date), "t" to command.textbookId, "content" to command.content, "hw" to command.homework, "now" to soTime(now)),
        )
    }

    override fun deleteProgress(id: UUID) {
        named.update("delete from classroom_progress where id = :id", soP("id" to id))
    }
}

// ───────── EVT-009 행사 후기·사진 ─────────
@Component
class EventFollowUpJdbcAdapter(jdbc: JdbcTemplate) : EventFollowUpPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun event(id: UUID): EventBrief? =
        named.query("select id, institution_id, title, starts_at from school_event where id = :id", soP("id" to id)) { rs, _ ->
            EventBrief(rs.soId("id")!!, rs.soId("institution_id")!!, rs.getString("title"), rs.soTs("starts_at")!!)
        }.firstOrNull()

    override fun albums(eventId: UUID, visibleToStudents: Collection<UUID>?): List<EventAlbumLink> {
        if (visibleToStudents != null && visibleToStudents.isEmpty()) return emptyList()
        val vis = if (visibleToStudents != null) "and exists (select 1 from album_recipient ar where ar.album_id = a.id and ar.student_id in (:ss))" else ""
        return named.query(
            """select a.id, a.title, a.created_at, (select count(*) from album_item ai where ai.album_id = a.id) as items
                 from album a where a.event_id = :e $vis order by a.created_at desc""",
            soP("e" to eventId, "ss" to visibleToStudents),
        ) { rs, _ -> EventAlbumLink(rs.soId("id")!!, rs.getString("title"), rs.getInt("items"), rs.soTs("created_at")!!) }
    }

    override fun albumInstitution(albumId: UUID): UUID? =
        named.query("select institution_id from album where id = :id", soP("id" to albumId)) { rs, _ -> rs.soId("institution_id")!! }.firstOrNull()

    override fun setAlbumEvent(albumId: UUID, eventId: UUID?) {
        named.update("update album set event_id = :e where id = :id", soP("id" to albumId, "e" to eventId))
    }

    override fun targetStudents(eventId: UUID): Set<UUID> =
        named.query("select student_id from school_event_target where event_id = :e", soP("e" to eventId)) { rs, _ -> rs.soId("student_id")!! }.toSet()

    private fun reviewMapper(viewer: UserId?) = RowMapper<Pair<UUID, EventReviewView>> { rs, _ ->
        rs.soId("event_id")!! to EventReviewView(
            rs.soId("id")!!, rs.soId("event_id")!!, rs.getString("student_name"), rs.getString("author_name"), rs.getString("body"),
            rs.getBoolean("hidden"), viewer != null && rs.soId("user_id") == viewer.value, rs.soTs("created_at")!!,
        )
    }

    private val reviewSelect = """
        select r.id, r.event_id, r.user_id, s.name as student_name, u.name as author_name, r.body, r.hidden, r.created_at
          from event_review r join student s on s.id = r.student_id join app_user u on u.id = r.user_id
    """

    override fun reviews(eventId: UUID, viewer: UserId?, includeHidden: Boolean): List<EventReviewView> =
        named.query(
            // 숨김 후기는 교직원과 작성자 본인만 본다
            "$reviewSelect where r.event_id = :e ${if (includeHidden) "" else "and (not r.hidden or r.user_id = :v)"} order by r.created_at desc",
            soP("e" to eventId, "v" to viewer?.value), reviewMapper(viewer),
        ).map { it.second }

    override fun findReview(id: UUID): Pair<UUID, EventReviewView>? =
        named.query("$reviewSelect where r.id = :id", soP("id" to id), reviewMapper(null)).firstOrNull()

    override fun upsertReview(eventId: UUID, studentId: UUID, user: UserId, body: String, now: Instant) {
        named.update(
            """insert into event_review (id, event_id, student_id, user_id, body, hidden, created_at, updated_at)
               values (:id, :e, :s, :u, :b, false, :now, :now)
               on conflict (event_id, user_id) do update set student_id = excluded.student_id, body = excluded.body, updated_at = excluded.updated_at""",
            soP("id" to Uuid7.next(), "e" to eventId, "s" to studentId, "u" to user.value, "b" to body, "now" to soTime(now)),
        )
    }

    override fun setHidden(reviewId: UUID, hidden: Boolean, now: Instant) {
        named.update("update event_review set hidden = :h, updated_at = :now where id = :id", soP("id" to reviewId, "h" to hidden, "now" to soTime(now)))
    }

    override fun deleteReview(eventId: UUID, user: UserId) {
        named.update("delete from event_review where event_id = :e and user_id = :u", soP("e" to eventId, "u" to user.value))
    }
}
