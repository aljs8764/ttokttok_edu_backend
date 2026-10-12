package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.application.port.`in`.ConsultToday
import com.ttokttok.application.port.`in`.CoverNeeded
import com.ttokttok.application.port.`in`.DashboardWidget
import com.ttokttok.application.port.`in`.EventFollowUp
import com.ttokttok.application.port.`in`.InboxItem
import com.ttokttok.application.port.`in`.InstitutionCard
import com.ttokttok.application.port.`in`.InvoiceBrief
import com.ttokttok.application.port.`in`.NoticeFollowUp
import com.ttokttok.application.port.`in`.OwnerBilling
import com.ttokttok.application.port.`in`.OwnerClassRow
import com.ttokttok.application.port.`in`.OwnerKpi
import com.ttokttok.application.port.`in`.OwnerStaff
import com.ttokttok.application.port.`in`.ProgressMissing
import com.ttokttok.application.port.`in`.StaffMemoAck
import com.ttokttok.application.port.`in`.StaffMemoView
import com.ttokttok.application.port.`in`.StaffTodayRow
import com.ttokttok.application.port.`in`.StudentCaution
import com.ttokttok.application.port.`in`.TeacherClassToday
import com.ttokttok.application.port.`in`.TeacherWork
import com.ttokttok.application.port.`in`.TodoAbsence
import com.ttokttok.application.port.`in`.TodoStudent
import com.ttokttok.application.port.`in`.UnansweredConversation
import com.ttokttok.application.port.out.ChurnSignal
import com.ttokttok.application.port.out.DashboardLayoutPort
import com.ttokttok.application.port.out.RoleDashboardPort
import com.ttokttok.application.port.out.StaffMemoPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.util.UUID

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private fun ResultSet.rdId(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.rdTs(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.rdDate(col: String): LocalDate? = getDate(col)?.toLocalDate()
@Suppress("UNCHECKED_CAST")
private fun ResultSet.rdNames(col: String): List<String> = (getArray(col)?.array as? Array<Any?>)?.mapNotNull { it?.toString() } ?: emptyList()
private fun rdP(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }
private fun rdT(i: Instant) = Timestamp.from(i)
private fun rdD(d: LocalDate) = Date.valueOf(d)
/** 서울 시각 기준 '날짜 + 시각' 비교용 (timestamp without time zone) */
private fun rdLocal(now: Instant) = Timestamp.valueOf(LocalDateTime.ofInstant(now, SEOUL))
/** 반 요일 비트 (월=1 … 일=64) */
private fun dayBit(d: LocalDate) = 1 shl (d.dayOfWeek.value - 1)
private fun rate(num: Int, den: Int): Double? = if (den > 0) num.toDouble() / den else null
private fun rateL(num: Long, den: Long): Double? = if (den > 0) num.toDouble() / den else null

/** 담당 반 원생 조건 (teacher 담당 반에 현재 배정된 원생) */
private const val TAUGHT_STUDENT = """exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                                         join classroom cc on cc.id = e.classroom_id and cc.deleted_at is null
                                        where e.student_id = %s and e.to_date is null and ct.user_id = :t)"""

@Component
class RoleDashboardJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : RoleDashboardPort {
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val log = LoggerFactory.getLogger(javaClass)

    private fun dec(v: String?): String? = v?.let { runCatching { crypto.decrypt(it) }.onFailure { e -> log.warn("복호화 실패: {}", e.message) }.getOrNull() }
    private fun phone(enc: String?): String? = dec(enc)?.let { d ->
        if (d.length == 11) "${d.take(3)}-${d.substring(3, 7)}-${d.takeLast(4)}" else d
    }

    // ───────── 원장 ─────────

    override fun ownerKpi(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerKpi {
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        return named.queryForObject(
            """select
                 (select count(*) from student where institution_id = :i and status = 'ACTIVE') as active,
                 (select count(*) from student where institution_id = :i and created_at >= :ms_ts) as new_cnt,
                 (select count(*) from student_status_history where institution_id = :i and to_status = 'WITHDRAWN' and effective_date >= :ms) as wd_cnt,
                 (select coalesce(sum(capacity), 0) from classroom where institution_id = :i and deleted_at is null) as cap,
                 (select count(*) from enrollment e join classroom c on c.id = e.classroom_id and c.deleted_at is null
                    join student s on s.id = e.student_id and s.status <> 'WITHDRAWN'
                   where c.institution_id = :i and e.to_date is null) as seated,
                 (select coalesce(sum(amount), 0) from invoice where institution_id = :i and status <> 'CANCELED' and due_date between :ms and :me) as billed,
                 (select coalesce(sum(amount), 0) from invoice where institution_id = :i and status = 'PAID' and due_date between :ms and :me) as paid,
                 (select coalesce(sum(amount), 0) from invoice where institution_id = :i and status = 'UNPAID') as unpaid_amt,
                 (select count(*) from invoice where institution_id = :i and status = 'UNPAID') as unpaid_cnt,
                 (select count(*) from attendance_day where institution_id = :i and date = :d) as sched,
                 (select count(*) from attendance_day where institution_id = :i and date = :d and status in ('IN', 'OUT')) as present""",
            rdP(
                "i" to institutionId.value, "ms" to rdD(monthStart), "me" to rdD(monthEnd), "d" to rdD(today),
                "ms_ts" to rdT(monthStart.atStartOfDay(SEOUL).toInstant()),
            ),
        ) { rs, _ ->
            val active = rs.getInt("active")
            val newCnt = rs.getInt("new_cnt")
            val wd = rs.getInt("wd_cnt")
            val cap = rs.getInt("cap")
            val billed = rs.getLong("billed")
            val paid = rs.getLong("paid")
            val sched = rs.getInt("sched")
            val present = rs.getInt("present")
            OwnerKpi(
                asOf = now, activeStudents = active, activeStudentsLastMonth = maxOf(0, active - newCnt + wd),
                newThisMonth = newCnt, withdrawnThisMonth = wd, capacity = cap, fillRate = rate(rs.getInt("seated"), cap),
                billedThisMonth = billed, paidThisMonth = paid, collectionRate = rateL(paid, billed),
                unpaidAmount = rs.getLong("unpaid_amt"), unpaidCount = rs.getInt("unpaid_cnt"),
                todayScheduled = sched, todayPresent = present, todayAttendanceRate = rate(present, sched),
            )
        }!!
    }

    override fun ownerClasses(institutionId: InstitutionId, today: LocalDate, now: Instant): List<OwnerClassRow> =
        named.query(
            """select c.id, c.name, c.capacity, c.days_mask,
                      to_char(c.start_time, 'HH24:MI') as st, to_char(c.end_time, 'HH24:MI') as et,
                      coalesce((select array_agg(u.name order by u.name) from classroom_teacher ct join app_user u on u.id = ct.user_id
                                 where ct.classroom_id = c.id), '{}') as teachers,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d) as sched,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status in ('IN', 'OUT')) as present,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status = 'ABSENT') as absent,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status = 'SCHEDULED'
                          and (a.date + c.start_time) <= :local_now) as not_arrived,
                      (select count(*) from enrollment e join student s on s.id = e.student_id and s.status <> 'WITHDRAWN'
                        where e.classroom_id = c.id and e.to_date is null) as headcount,
                      (select count(*) from classroom_waitlist w where w.classroom_id = c.id and w.status = 'WAITING') as waiting,
                      (select count(*) from attendance_missing_alert m join attendance_day a on a.id = m.attendance_day_id
                        where m.classroom_id = c.id and a.date = :d) as missing,
                      rr.targets, rr.read_cnt
                 from classroom c
                 left join lateral (
                      select count(*) as targets, count(*) filter (where x.is_read) as read_cnt
                        from (select n.id, r.student_id, bool_or(r.read_at is not null) as is_read
                                from notice n
                                join notice_recipient r on r.notice_id = n.id and r.guardian_user_id is not null
                                join enrollment e on e.student_id = r.student_id and e.to_date is null and e.classroom_id = c.id
                               where n.institution_id = :i and n.status = 'SENT' and n.sent_at >= :since
                               group by n.id, r.student_id) x
                 ) rr on true
                where c.institution_id = :i and c.deleted_at is null
                order by c.start_time, c.name""",
            rdP(
                "i" to institutionId.value, "d" to rdD(today), "local_now" to rdLocal(now),
                "since" to rdT(now.minus(Duration.ofDays(7))),
            ),
        ) { rs, _ ->
            OwnerClassRow(
                classroomId = rs.rdId("id")!!, name = rs.getString("name"), teachers = rs.rdNames("teachers"),
                heldToday = rs.getInt("days_mask") and dayBit(today) != 0,
                startTime = rs.getString("st"), endTime = rs.getString("et"),
                scheduled = rs.getInt("sched"), present = rs.getInt("present"), absent = rs.getInt("absent"), notArrived = rs.getInt("not_arrived"),
                capacity = rs.getInt("capacity"), headcount = rs.getInt("headcount"), waiting = rs.getInt("waiting"),
                readRate7d = rate(rs.getInt("read_cnt"), rs.getInt("targets")), missingAlertsToday = rs.getInt("missing"), flags = emptyList(),
            )
        }

    override fun inbox(institutionId: InstitutionId, today: LocalDate, now: Instant): List<InboxItem> {
        val p = rdP(
            "i" to institutionId.value, "d" to rdD(today), "day_start" to rdT(today.atStartOfDay(SEOUL).toInstant()),
            "stale" to rdT(now.minus(Duration.ofHours(24))),
        )
        fun item(key: String, sql: String) = named.queryForObject(sql, p) { rs, _ -> InboxItem(key, rs.getInt("cnt"), rs.rdTs("oldest")) }!!
        return listOf(
            item("JOIN_REQUEST", "select count(*) as cnt, min(submitted_at) as oldest from join_request where institution_id = :i and status = 'PENDING'"),
            item(
                "ABSENCE_REQUEST",
                "select count(*) as cnt, min(created_at) as oldest from absence_request where institution_id = :i and status = 'SUBMITTED' and date >= :d",
            ),
            item(
                "UNANSWERED_MESSAGE",
                """select count(*) as cnt, min(last_message_at) as oldest from conversation
                    where institution_id = :i and staff_unread > 0 and last_message_at < :stale""",
            ),
            item(
                "NEW_CONSULT",
                "select count(*) as cnt, min(booked_at) as oldest from consult_slot where institution_id = :i and status = 'BOOKED' and booked_at >= :day_start",
            ),
            item(
                "SUPPORT_REPLY",
                """select count(*) as cnt, min(t.last_message_at) as oldest from support_ticket t
                    where t.institution_id = :i and t.status <> 'CLOSED'
                      and (select m.from_operator from support_message m where m.ticket_id = t.id order by m.created_at desc limit 1)""",
            ),
        )
    }

    override fun billing(institutionId: InstitutionId, today: LocalDate): OwnerBilling {
        val p = rdP("i" to institutionId.value, "d" to rdD(today), "soon" to rdD(today.plusDays(3)))
        val brief = { rs: ResultSet, _: Int ->
            InvoiceBrief(rs.rdId("id")!!, rs.getString("student_name"), rs.getString("title"), rs.getLong("amount"), rs.rdDate("due_date")!!, rs.rdDate("paid_on"))
        }
        val head = named.queryForObject(
            """select count(*) filter (where status = 'UNPAID') as unpaid_cnt,
                      coalesce(sum(amount) filter (where status = 'UNPAID'), 0) as unpaid_amt,
                      count(*) filter (where status = 'UNPAID' and due_date < :d) as overdue_cnt,
                      coalesce(sum(amount) filter (where status = 'UNPAID' and due_date < :d), 0) as overdue_amt
                 from invoice where institution_id = :i""",
            p,
        ) { rs, _ -> listOf(rs.getLong("unpaid_cnt"), rs.getLong("unpaid_amt"), rs.getLong("overdue_cnt"), rs.getLong("overdue_amt")) }!!
        val dueSoon = named.query(
            """select v.id, s.name as student_name, v.title, v.amount, v.due_date, v.paid_on
                 from invoice v join student s on s.id = v.student_id
                where v.institution_id = :i and v.status = 'UNPAID' and v.due_date between :d and :soon
                order by v.due_date, s.name limit 10""",
            p, brief,
        )
        val recent = named.query(
            """select v.id, s.name as student_name, v.title, v.amount, v.due_date, v.paid_on
                 from invoice v join student s on s.id = v.student_id
                where v.institution_id = :i and v.status = 'PAID'
                order by v.paid_on desc nulls last, v.updated_at desc limit 5""",
            p, brief,
        )
        return OwnerBilling(head[0].toInt(), head[1], head[2].toInt(), head[3], dueSoon, recent)
    }

    override fun churnSignals(institutionId: InstitutionId, today: LocalDate): List<ChurnSignal> =
        named.query(
            """select s.id, s.name,
                      coalesce((select array_agg(c.name order by c.name) from enrollment e join classroom c on c.id = e.classroom_id and c.deleted_at is null
                                 where e.student_id = s.id and e.to_date is null), '{}') as classes,
                      (select count(*) from attendance_day a where a.student_id = s.id and a.date between :r0 and :d) as sched_r,
                      (select count(*) from attendance_day a where a.student_id = s.id and a.date between :r0 and :d and a.status = 'ABSENT') as abs_r,
                      (select count(*) from attendance_day a where a.student_id = s.id and a.date between :b0 and :b1) as sched_b,
                      (select count(*) from attendance_day a where a.student_id = s.id and a.date between :b0 and :b1 and a.status = 'ABSENT') as abs_b,
                      nt.total as notices, nt.unread as unread,
                      exists (select 1 from student_status_history h where h.student_id = s.id and h.from_status = 'PAUSED'
                                 and h.to_status = 'ACTIVE' and h.effective_date >= :d90) as paused,
                      (select count(*) from invoice v where v.student_id = s.id and v.status = 'UNPAID' and v.due_date < :d) as overdue,
                      (select max(cn.occurred_on) from student_counsel_note cn where cn.student_id = s.id) as last_counsel,
                      exists (select 1 from student_counsel_note cn where cn.student_id = s.id and cn.occurred_on >= :d60
                                 and cn.body ~ '(퇴원|그만|옮기|이사|중단|휴원)') as counsel_wd
                 from student s
                 left join lateral (
                      select count(*) as total, count(*) filter (where not x.rd) as unread
                        from (select n.id, bool_or(r.read_at is not null) as rd
                                from notice n join notice_recipient r on r.notice_id = n.id and r.guardian_user_id is not null
                               where r.student_id = s.id and n.status = 'SENT' and n.sent_at >= :n30
                               group by n.id) x
                 ) nt on true
                where s.institution_id = :i and s.status = 'ACTIVE'""",
            rdP(
                "i" to institutionId.value, "d" to rdD(today), "r0" to rdD(today.minusDays(27)),
                "b0" to rdD(today.minusDays(55)), "b1" to rdD(today.minusDays(28)),
                "d90" to rdD(today.minusDays(90)), "d60" to rdD(today.minusDays(60)),
                "n30" to rdT(today.minusDays(30).atStartOfDay(SEOUL).toInstant()),
            ),
        ) { rs, _ ->
            ChurnSignal(
                rs.rdId("id")!!, rs.getString("name"), rs.rdNames("classes"),
                rs.getInt("sched_r"), rs.getInt("abs_r"), rs.getInt("sched_b"), rs.getInt("abs_b"),
                rs.getInt("notices"), rs.getInt("unread"), rs.getBoolean("paused"), rs.getInt("overdue"),
                rs.rdDate("last_counsel"), rs.getBoolean("counsel_wd"),
            )
        }

    override fun staffToday(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerStaff {
        val p = rdP("i" to institutionId.value, "d" to rdD(today), "bit" to dayBit(today), "local_now" to rdLocal(now))
        val staff = named.query(
            """select u.id, u.name, m.role, sa.check_in, sa.check_out,
                      coalesce((select array_agg(c.name order by c.start_time) from classroom_teacher ct
                                  join classroom c on c.id = ct.classroom_id and c.deleted_at is null
                                 where ct.user_id = u.id and c.institution_id = :i and (c.days_mask & :bit) <> 0), '{}') as classes,
                      (select coalesce(sum(cv.staff_unread), 0) from conversation cv
                        where cv.institution_id = :i and cv.staff_unread > 0
                          and ${TAUGHT_STUDENT.format("cv.student_id").replace(":t", "u.id")}) as unanswered
                 from membership m
                 join app_user u on u.id = m.user_id
                 left join staff_attendance sa on sa.institution_id = :i and sa.user_id = u.id and sa.work_date = :d
                where m.institution_id = :i and m.role in ('OWNER', 'ADMIN', 'TEACHER')
                order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, u.name""",
            p,
        ) { rs, _ ->
            StaffTodayRow(
                rs.rdId("id")!!, rs.getString("name"), rs.getString("role"), rs.rdTs("check_in"), rs.rdTs("check_out"),
                rs.rdNames("classes"), rs.getInt("unanswered"),
            )
        }
        // 담당 교사 누구도 출근 기록이 없는데 수업 30분 전 ~ 수업 중인 반
        val cover = named.query(
            """select c.id, c.name, to_char(c.start_time, 'HH24:MI') as st, array_agg(u.name order by u.name) as teachers
                 from classroom c
                 join classroom_teacher ct on ct.classroom_id = c.id
                 join app_user u on u.id = ct.user_id
                where c.institution_id = :i and c.deleted_at is null and (c.days_mask & :bit) <> 0
                  and (:d + c.start_time - interval '30 minutes') <= :local_now and (:d + c.end_time) > :local_now
                group by c.id, c.name, c.start_time
               having bool_and(not exists (select 1 from staff_attendance sa
                                            where sa.institution_id = :i and sa.user_id = ct.user_id and sa.work_date = :d))
                order by c.start_time""",
            p,
        ) { rs, _ -> CoverNeeded(rs.rdId("id")!!, rs.getString("name"), rs.getString("st"), rs.rdNames("teachers")) }
        return OwnerStaff(staff, cover)
    }

    override fun institutionCards(userId: UserId, today: LocalDate, now: Instant): List<InstitutionCard> {
        val monthStart = today.withDayOfMonth(1)
        return named.query(
            """select i.id, i.name, m.role,
                      (select count(*) from student s where s.institution_id = i.id and s.status = 'ACTIVE') as active,
                      (select count(*) from attendance_day a where a.institution_id = i.id and a.date = :d) as sched,
                      (select count(*) from attendance_day a where a.institution_id = i.id and a.date = :d and a.status in ('IN', 'OUT')) as present,
                      (select coalesce(sum(amount), 0) from invoice v where v.institution_id = i.id and v.status <> 'CANCELED' and v.due_date between :ms and :me) as billed,
                      (select coalesce(sum(amount), 0) from invoice v where v.institution_id = i.id and v.status = 'PAID' and v.due_date between :ms and :me) as paid,
                      (select count(*) from join_request j where j.institution_id = i.id and j.status = 'PENDING')
                    + (select count(*) from absence_request ar where ar.institution_id = i.id and ar.status = 'SUBMITTED' and ar.date >= :d)
                    + (select count(*) from conversation cv where cv.institution_id = i.id and cv.staff_unread > 0) as pending
                 from membership m join institution i on i.id = m.institution_id
                where m.user_id = :u and m.role in ('OWNER', 'ADMIN')
                order by i.is_sample, i.name""",
            rdP("u" to userId.value, "d" to rdD(today), "ms" to rdD(monthStart), "me" to rdD(today.withDayOfMonth(today.lengthOfMonth()))),
        ) { rs, _ ->
            InstitutionCard(
                rs.rdId("id")!!, rs.getString("name"), rs.getString("role"), rs.getInt("active"),
                rate(rs.getInt("present"), rs.getInt("sched")), rateL(rs.getLong("paid"), rs.getLong("billed")), rs.getInt("pending"),
            )
        }
    }

    // ───────── 교사 ─────────

    override fun teacherName(teacherId: UUID): String? =
        named.query("select name from app_user where id = :t", rdP("t" to teacherId)) { rs, _ -> rs.getString("name") }.firstOrNull()

    override fun teacherClasses(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TeacherClassToday> {
        val nowTime = LocalTime.ofInstant(now, SEOUL)
        return named.query(
            """select c.id, c.name, c.start_time, c.end_time, to_char(c.start_time, 'HH24:MI') as st, to_char(c.end_time, 'HH24:MI') as et,
                      count(a.id) as sched,
                      count(a.id) filter (where a.status in ('IN', 'OUT')) as present,
                      count(a.id) filter (where a.status = 'OUT') as out_cnt,
                      count(a.id) filter (where a.status = 'ABSENT') as absent
                 from classroom c
                 join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
                 left join attendance_day a on a.classroom_id = c.id and a.date = :d
                where c.institution_id = :i and c.deleted_at is null and (c.days_mask & :bit) <> 0
                group by c.id, c.name, c.start_time, c.end_time
                order by c.start_time, c.name""",
            rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today), "bit" to dayBit(today)),
        ) { rs, _ ->
            val start = rs.getTime("start_time").toLocalTime()
            val end = rs.getTime("end_time").toLocalTime()
            val phase = when {
                nowTime.isBefore(start) -> "UPCOMING"
                nowTime.isBefore(end) -> "NOW"
                else -> "DONE"
            }
            TeacherClassToday(
                rs.rdId("id")!!, rs.getString("name"), rs.getString("st"), rs.getString("et"), phase,
                rs.getInt("sched"), rs.getInt("present"), rs.getInt("out_cnt"), rs.getInt("absent"),
            )
        }
    }

    private fun todo(sql: String, institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> =
        named.query(sql, rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today), "local_now" to rdLocal(now))) { rs, _ ->
            TodoStudent(
                rs.rdId("sid")!!, rs.getString("sname"), rs.rdId("cid")!!, rs.getString("cname"), rs.rdId("ad"),
                rs.getLong("mins"), phone(rs.getString("phone_enc")),
            )
        }

    private val guardianPhone =
        "(select g.phone_enc from guardian g where g.student_id = s.id order by g.is_primary desc, g.created_at limit 1) as phone_enc"

    override fun notArrived(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> = todo(
        """select a.id as ad, s.id as sid, s.name as sname, c.id as cid, c.name as cname,
                  floor(extract(epoch from (:local_now - (a.date + c.start_time))) / 60) as mins, $guardianPhone
             from attendance_day a
             join classroom c on c.id = a.classroom_id
             join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
             join student s on s.id = a.student_id
             join institution i on i.id = a.institution_id
            where a.institution_id = :i and a.date = :d and a.status = 'SCHEDULED'
              and (a.date + c.start_time + make_interval(mins => i.late_threshold_minutes)) <= :local_now
              and not exists (select 1 from absence_request ar where ar.student_id = a.student_id and ar.date = a.date
                                and ar.kind = 'ABSENT' and ar.status <> 'CANCELLED')
            order by mins desc, s.name""",
        institutionId, teacherId, today, now,
    )

    override fun notCheckedOut(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> = todo(
        """select a.id as ad, s.id as sid, s.name as sname, c.id as cid, c.name as cname,
                  floor(extract(epoch from (:local_now - (a.date + c.end_time))) / 60) as mins, $guardianPhone
             from attendance_day a
             join classroom c on c.id = a.classroom_id
             join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
             join student s on s.id = a.student_id
            where a.institution_id = :i and a.date = :d and a.status = 'IN'
              and (a.date + c.end_time + interval '30 minutes') <= :local_now
            order by mins desc, s.name""",
        institutionId, teacherId, today, now,
    )

    override fun pendingAbsences(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<TodoAbsence> =
        named.query(
            """select r.id, s.name, r.date, r.kind, to_char(r.expected_time, 'HH24:MI') as et, r.reason
                 from absence_request r join student s on s.id = r.student_id
                where r.institution_id = :i and r.status = 'SUBMITTED' and r.date >= :d
                  and ${TAUGHT_STUDENT.format("r.student_id")}
                order by r.date, r.created_at limit 20""",
            rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today)),
        ) { rs, _ -> TodoAbsence(rs.rdId("id")!!, rs.getString("name"), rs.rdDate("date")!!, rs.getString("kind"), rs.getString("et"), rs.getString("reason")) }

    /** 오늘 출결 대상(담당 반)인 원생의 건강·생일·사전 신청·최근 상담 */
    override fun cautions(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<StudentCaution> {
        data class Row(
            val id: UUID, val name: String, val classroom: String, val birthEnc: String?, val allergiesEnc: String?, val conditionsEnc: String?,
            val absKind: String?, val absTime: String?, val absReason: String?, val counselOn: LocalDate?, val counselBody: String?,
        )
        val rows = named.query(
            """select distinct on (s.id) s.id, s.name, c.name as cname, s.birth_enc, h.allergies_enc, h.conditions_enc,
                      ar.kind as abs_kind, to_char(ar.expected_time, 'HH24:MI') as abs_time, ar.reason as abs_reason,
                      cn.occurred_on as counsel_on, cn.body as counsel_body
                 from attendance_day a
                 join classroom c on c.id = a.classroom_id
                 join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
                 join student s on s.id = a.student_id
                 left join student_health h on h.student_id = s.id
                 left join lateral (select kind, expected_time, reason from absence_request x
                                     where x.student_id = s.id and x.date = :d and x.status <> 'CANCELLED'
                                     order by x.created_at desc limit 1) ar on true
                 left join lateral (select occurred_on, body from student_counsel_note n
                                     where n.student_id = s.id and n.occurred_on >= :d7
                                     order by n.occurred_on desc, n.created_at desc limit 1) cn on true
                where a.institution_id = :i and a.date = :d
                order by s.id, c.start_time""",
            rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today), "d7" to rdD(today.minusDays(7))),
        ) { rs, _ ->
            Row(
                rs.rdId("id")!!, rs.getString("name"), rs.getString("cname"), rs.getString("birth_enc"),
                rs.getString("allergies_enc"), rs.getString("conditions_enc"),
                rs.getString("abs_kind"), rs.getString("abs_time"), rs.getString("abs_reason"),
                rs.rdDate("counsel_on"), rs.getString("counsel_body"),
            )
        }
        val out = mutableListOf<StudentCaution>()
        rows.sortedBy { it.name }.forEach { r ->
            val allergies = dec(r.allergiesEnc)?.trim()?.takeIf { it.isNotEmpty() }
            val conditions = dec(r.conditionsEnc)?.trim()?.takeIf { it.isNotEmpty() }
            if (allergies != null || conditions != null) out += StudentCaution(r.id, r.name, r.classroom, "HEALTH", allergies, conditions)
            dec(r.birthEnc)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { b ->
                if (b.month == today.month && b.dayOfMonth == today.dayOfMonth) {
                    out += StudentCaution(r.id, r.name, r.classroom, "BIRTHDAY", Period.between(b, today).years.toString(), null)
                }
            }
            if (r.absKind != null) {
                out += StudentCaution(r.id, r.name, r.classroom, "ABSENCE_NOTE", r.absReason, listOfNotNull(r.absKind, r.absTime).joinToString(" "))
            }
            if (r.counselOn != null) {
                out += StudentCaution(r.id, r.name, r.classroom, "COUNSEL", r.counselBody?.take(80), r.counselOn.toString())
            }
        }
        val order = listOf("HEALTH", "ABSENCE_NOTE", "BIRTHDAY", "COUNSEL")
        return out.sortedBy { order.indexOf(it.kind) }
    }

    override fun unansweredConversations(institutionId: InstitutionId, teacherId: UUID, limit: Int): List<UnansweredConversation> =
        named.query(
            """select cv.id, s.name, cv.last_message_preview, cv.staff_unread, cv.last_message_at
                 from conversation cv join student s on s.id = cv.student_id
                where cv.institution_id = :i and cv.staff_unread > 0 and ${TAUGHT_STUDENT.format("cv.student_id")}
                order by cv.last_message_at limit :lim""",
            rdP("i" to institutionId.value, "t" to teacherId, "lim" to limit),
        ) { rs, _ -> UnansweredConversation(rs.rdId("id")!!, rs.getString("name"), rs.getString("last_message_preview"), rs.getInt("staff_unread"), rs.rdTs("last_message_at")!!) }

    override fun noticeFollowUps(institutionId: InstitutionId, teacherId: UUID, since: Instant): List<NoticeFollowUp> =
        named.query(
            """select n.id, n.title, n.sent_at, n.last_resent_at,
                      count(*) as targets, count(*) filter (where not x.rd) as unread
                 from notice n
                 join (select notice_id, student_id, bool_or(read_at is not null) as rd
                         from notice_recipient where guardian_user_id is not null
                        group by notice_id, student_id) x on x.notice_id = n.id
                where n.institution_id = :i and n.author_id = :t and n.status = 'SENT' and n.sent_at >= :since
                group by n.id, n.title, n.sent_at, n.last_resent_at
               having count(*) filter (where not x.rd) > 0
                order by n.sent_at desc limit 10""",
            rdP("i" to institutionId.value, "t" to teacherId, "since" to rdT(since)),
        ) { rs, _ ->
            NoticeFollowUp(
                rs.rdId("id")!!, rs.getString("title"), rs.rdTs("sent_at")!!, rs.getInt("targets"), rs.getInt("unread"),
                rs.rdTs("last_resent_at")?.plus(Duration.ofMinutes(30)),
            )
        }

    override fun eventFollowUps(institutionId: InstitutionId, teacherId: UUID, now: Instant, until: Instant): List<EventFollowUp> =
        named.query(
            """select e.id, e.title, e.rsvp_deadline,
                      (select count(*) from school_event_target t where t.event_id = e.id)
                    - (select count(*) from event_response r where r.event_id = e.id) as no_response
                 from school_event e
                where e.institution_id = :i and e.author_id = :t and e.status = 'ACTIVE' and e.rsvp_enabled
                  and e.rsvp_deadline > :now and e.rsvp_deadline <= :until
                order by e.rsvp_deadline""",
            rdP("i" to institutionId.value, "t" to teacherId, "now" to rdT(now), "until" to rdT(until)),
        ) { rs, _ -> EventFollowUp(rs.rdId("id")!!, rs.getString("title"), rs.rdTs("rsvp_deadline")!!, maxOf(0, rs.getInt("no_response"))) }
            .filter { it.noResponse > 0 }

    override fun consultsToday(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<ConsultToday> =
        named.query(
            """select cs.id, cs.starts_at, s.name, cs.mode, cs.topic
                 from consult_slot cs left join student s on s.id = cs.student_id
                where cs.institution_id = :i and cs.teacher_id = :t and cs.status = 'BOOKED'
                  and cs.starts_at >= :a and cs.starts_at < :b
                order by cs.starts_at""",
            rdP(
                "i" to institutionId.value, "t" to teacherId,
                "a" to rdT(today.atStartOfDay(SEOUL).toInstant()), "b" to rdT(today.plusDays(1).atStartOfDay(SEOUL).toInstant()),
            ),
        ) { rs, _ -> ConsultToday(rs.rdId("id")!!, rs.rdTs("starts_at")!!, rs.getString("name"), rs.getString("mode"), rs.getString("topic")) }

    override fun progressMissing(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<ProgressMissing> =
        named.query(
            """select c.id, c.name, to_char(c.end_time, 'HH24:MI') as et
                 from classroom c join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
                where c.institution_id = :i and c.deleted_at is null and (c.days_mask & :bit) <> 0
                  and (:d + c.end_time) <= :local_now
                  and not exists (select 1 from classroom_progress p where p.classroom_id = c.id and p.date = :d)
                order by c.end_time""",
            rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today), "bit" to dayBit(today), "local_now" to rdLocal(now)),
        ) { rs, _ -> ProgressMissing(rs.rdId("id")!!, rs.getString("name"), rs.getString("et")) }

    override fun work(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, weekStart: LocalDate): TeacherWork =
        named.queryForObject(
            """select (select check_in from staff_attendance where institution_id = :i and user_id = :t and work_date = :d) as cin,
                      (select check_out from staff_attendance where institution_id = :i and user_id = :t and work_date = :d) as cout,
                      (select coalesce(sum(extract(epoch from (coalesce(check_out, case when work_date = :d then now() else check_in end) - check_in))), 0)
                         from staff_attendance where institution_id = :i and user_id = :t and work_date between :w and :d) as secs,
                      (select tr.id from shuttle_trip tr where tr.institution_id = :i and tr.driver_id = :t and tr.status = 'RUNNING' limit 1) as trip_id,
                      (select r.name from shuttle_trip tr join shuttle_route r on r.id = tr.route_id
                        where tr.institution_id = :i and tr.driver_id = :t and tr.status = 'RUNNING' limit 1) as route_name,
                      exists (select 1 from shuttle_route r where r.institution_id = :i and r.active) as has_routes""",
            rdP("i" to institutionId.value, "t" to teacherId, "d" to rdD(today), "w" to rdD(weekStart)),
        ) { rs, _ ->
            TeacherWork(
                rs.rdTs("cin"), rs.rdTs("cout"), (rs.getDouble("secs") / 60).toLong(),
                rs.rdId("trip_id"), rs.getString("route_name"), rs.getBoolean("has_routes"),
            )
        }!!
}

// ───────── DASH-015·023 교직원 공지 ─────────
@Component
class StaffMemoJdbcAdapter(jdbc: JdbcTemplate) : StaffMemoPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val STAFF_ROLES = "('OWNER', 'ADMIN', 'TEACHER')"
        const val TARGETS = """case when m.to_all then (select count(*) from membership ms where ms.institution_id = m.institution_id
                                                          and ms.role in $STAFF_ROLES and ms.user_id <> m.author_id)
                                    else (select count(*) from staff_memo_target t where t.memo_id = m.id) end"""
        /** :u 가 받는 공지인지 */
        const val RECEIVES = """((m.to_all and m.author_id <> :u
                                  and exists (select 1 from membership ms where ms.institution_id = m.institution_id and ms.user_id = :u and ms.role in $STAFF_ROLES))
                                 or exists (select 1 from staff_memo_target t where t.memo_id = m.id and t.user_id = :u))"""
    }

    private fun view(rs: ResultSet) = StaffMemoView(
        id = rs.rdId("id")!!, title = rs.getString("title"), body = rs.getString("body"), dueDate = rs.rdDate("due_date"),
        authorName = rs.getString("author_name"), createdAt = rs.rdTs("created_at")!!, toAll = rs.getBoolean("to_all"),
        canceled = rs.getTimestamp("canceled_at") != null, readAt = rs.rdTs("read_at"), doneAt = rs.rdTs("done_at"),
        targets = rs.getInt("targets"), readCount = rs.getInt("read_cnt"), doneCount = rs.getInt("done_cnt"),
    )

    private val summarySelect = """
        select m.id, m.title, m.body, m.due_date, m.created_at, m.to_all, m.canceled_at, u.name as author_name,
               null::timestamptz as read_at, null::timestamptz as done_at,
               $TARGETS as targets,
               (select count(*) from staff_memo_ack a where a.memo_id = m.id) as read_cnt,
               (select count(*) from staff_memo_ack a where a.memo_id = m.id and a.done_at is not null) as done_cnt
          from staff_memo m join app_user u on u.id = m.author_id"""

    override fun insert(institutionId: InstitutionId, author: UserId, title: String, body: String?, dueDate: LocalDate?, targets: List<UUID>, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into staff_memo (id, institution_id, author_id, title, body, due_date, to_all, created_at)
               values (:id, :i, :a, :title, :body, :due, :all, :now)""",
            rdP(
                "id" to id, "i" to institutionId.value, "a" to author.value, "title" to title, "body" to body,
                "due" to dueDate?.let { rdD(it) }, "all" to targets.isEmpty(), "now" to rdT(now),
            ),
        )
        if (targets.isNotEmpty()) {
            named.batchUpdate(
                "insert into staff_memo_target (memo_id, user_id) values (:m, :u)",
                targets.map { rdP("m" to id, "u" to it) }.toTypedArray(),
            )
        }
        return id
    }

    override fun list(institutionId: InstitutionId, includeCanceled: Boolean): List<StaffMemoView> =
        named.query(
            "$summarySelect where m.institution_id = :i ${if (includeCanceled) "" else "and m.canceled_at is null"} order by m.created_at desc limit 100",
            rdP("i" to institutionId.value),
        ) { rs, _ -> view(rs) }

    override fun find(institutionId: InstitutionId, memoId: UUID): StaffMemoView? =
        named.query("$summarySelect where m.institution_id = :i and m.id = :m", rdP("i" to institutionId.value, "m" to memoId)) { rs, _ -> view(rs) }.firstOrNull()

    override fun acks(institutionId: InstitutionId, memoId: UUID): List<StaffMemoAck> =
        named.query(
            """with r as (
                   select ms.user_id from staff_memo m join membership ms on ms.institution_id = m.institution_id
                    where m.id = :m and m.to_all and ms.role in $STAFF_ROLES and ms.user_id <> m.author_id
                   union
                   select t.user_id from staff_memo_target t where t.memo_id = :m
               )
               select u.id, u.name, a.read_at, a.done_at
                 from r join app_user u on u.id = r.user_id
                 left join staff_memo_ack a on a.memo_id = :m and a.user_id = r.user_id
                order by a.done_at nulls first, a.read_at nulls first, u.name""",
            rdP("m" to memoId),
        ) { rs, _ -> StaffMemoAck(rs.rdId("id")!!, rs.getString("name"), rs.rdTs("read_at"), rs.rdTs("done_at")) }

    override fun cancel(memoId: UUID, now: Instant) {
        named.update("update staff_memo set canceled_at = :now where id = :m and canceled_at is null", rdP("m" to memoId, "now" to rdT(now)))
    }

    override fun mine(institutionId: InstitutionId, user: UserId, includeDone: Boolean): List<StaffMemoView> =
        named.query(
            """select m.id, m.title, m.body, m.due_date, m.created_at, m.to_all, m.canceled_at, u.name as author_name,
                      a.read_at, a.done_at, 0 as targets, 0 as read_cnt, 0 as done_cnt
                 from staff_memo m
                 join app_user u on u.id = m.author_id
                 left join staff_memo_ack a on a.memo_id = m.id and a.user_id = :u
                where m.institution_id = :i and m.canceled_at is null and $RECEIVES
                  ${if (includeDone) "" else "and a.done_at is null"}
                order by (a.read_at is null) desc, m.due_date nulls last, m.created_at desc limit 50""",
            rdP("i" to institutionId.value, "u" to user.value),
        ) { rs, _ -> view(rs) }

    override fun isRecipient(memoId: UUID, user: UserId): Boolean =
        named.queryForObject(
            "select exists (select 1 from staff_memo m where m.id = :m and $RECEIVES)",
            rdP("m" to memoId, "u" to user.value), Boolean::class.java,
        ) == true

    override fun ack(memoId: UUID, user: UserId, done: Boolean?, now: Instant) {
        val p = rdP("m" to memoId, "u" to user.value, "now" to rdT(now))
        when (done) {
            null -> named.update(
                "insert into staff_memo_ack (memo_id, user_id, read_at) values (:m, :u, :now) on conflict (memo_id, user_id) do nothing", p,
            )
            true -> named.update(
                """insert into staff_memo_ack (memo_id, user_id, read_at, done_at) values (:m, :u, :now, :now)
                   on conflict (memo_id, user_id) do update set done_at = coalesce(staff_memo_ack.done_at, excluded.done_at)""",
                p,
            )
            false -> named.update(
                """insert into staff_memo_ack (memo_id, user_id, read_at) values (:m, :u, :now)
                   on conflict (memo_id, user_id) do update set done_at = null""",
                p,
            )
        }
    }

    override fun recipients(institutionId: InstitutionId, memoId: UUID): List<UserId> =
        acks(institutionId, memoId).map { UserId(it.userId) }

    override fun staffIds(institutionId: InstitutionId): Set<UUID> =
        named.query(
            "select user_id from membership where institution_id = :i and role in $STAFF_ROLES", rdP("i" to institutionId.value),
        ) { rs, _ -> rs.rdId("user_id")!! }.toSet()
}

// ───────── DASH-017 위젯 배치 ─────────
@Component
class DashboardLayoutJdbcAdapter(jdbc: JdbcTemplate) : DashboardLayoutPort {
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val json = jacksonObjectMapper()

    override fun find(user: UserId, kind: String): List<DashboardWidget>? =
        named.query(
            "select widgets::text as w from user_dashboard_layout where user_id = :u and kind = :k", rdP("u" to user.value, "k" to kind),
        ) { rs, _ -> json.readValue<List<DashboardWidget>>(rs.getString("w")) }.firstOrNull()

    override fun save(user: UserId, kind: String, widgets: List<DashboardWidget>, now: Instant) {
        named.update(
            """insert into user_dashboard_layout (user_id, kind, widgets, updated_at) values (:u, :k, cast(:w as jsonb), :now)
               on conflict (user_id, kind) do update set widgets = excluded.widgets, updated_at = excluded.updated_at""",
            rdP("u" to user.value, "k" to kind, "w" to json.writeValueAsString(widgets), "now" to rdT(now)),
        )
    }

    override fun delete(user: UserId, kind: String) {
        named.update("delete from user_dashboard_layout where user_id = :u and kind = :k", rdP("u" to user.value, "k" to kind))
    }
}
