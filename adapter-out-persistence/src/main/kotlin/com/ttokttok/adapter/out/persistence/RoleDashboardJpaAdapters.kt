package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.StaffMemoEntity
import com.ttokttok.adapter.out.persistence.entity.StaffMemoTargetEntity
import com.ttokttok.adapter.out.persistence.entity.UserDashboardLayoutEntity
import com.ttokttok.adapter.out.persistence.entity.UserDashboardLayoutKey
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StaffMemoJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UserDashboardLayoutJpaRepository
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.util.UUID

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
/**
 * 서울 시각 기준 '날짜 + 시각' 비교용 (timestamp without time zone).
 * LocalDateTime 을 바로 바인딩하면 hibernate.jdbc.time_zone(UTC) 변환으로 벽시계가 밀리므로 문자열로 넘겨 SQL 에서 cast 한다.
 */
private fun rdLocal(now: Instant): String = LocalDateTime.ofInstant(now, SEOUL).withNano(0).toString().replace('T', ' ')
/** 반 요일 비트 (월=1 … 일=64) */
private fun dayBit(d: LocalDate) = 1 shl (d.dayOfWeek.value - 1)
private fun rate(num: Int, den: Int): Double? = if (den > 0) num.toDouble() / den else null
private fun rateL(num: Long, den: Long): Double? = if (den > 0) num.toDouble() / den else null

/** 담당 반 원생 조건 (teacher 담당 반에 현재 배정된 원생) */
private const val TAUGHT_STUDENT = """exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                                         join classroom cc on cc.id = e.classroom_id and cc.deleted_at is null
                                        where e.student_id = %s and e.to_date is null and ct.user_id = :t)"""

/**
 * 역할별 대시보드 집계. 하위 쿼리·lateral 조인·filter 집계·정규식 등 PostgreSQL 전용 구문이 많아
 * 모든 조회를 JPA 네이티브 쿼리([JpaQueries])로 실행한다 (쓰기 없음).
 */
@Component
class RoleDashboardJpaAdapter(private val q: JpaQueries, private val crypto: FieldCrypto) : RoleDashboardPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun dec(v: String?): String? = v?.let { runCatching { crypto.decrypt(it) }.onFailure { e -> log.warn("복호화 실패: {}", e.message) }.getOrNull() }
    private fun phone(enc: String?): String? = dec(enc)?.let { d ->
        if (d.length == 11) "${d.take(3)}-${d.substring(3, 7)}-${d.takeLast(4)}" else d
    }

    // ───────── 원장 ─────────

    override fun ownerKpi(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerKpi {
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        return q.row(
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
            params(
                "i" to institutionId.value, "ms" to monthStart, "me" to monthEnd, "d" to today,
                "ms_ts" to monthStart.atStartOfDay(SEOUL).toInstant(),
            ),
        )!!.let { rs ->
            val active = rs.int("active")
            val newCnt = rs.int("new_cnt")
            val wd = rs.int("wd_cnt")
            val cap = rs.int("cap")
            val billed = rs.long("billed")
            val paid = rs.long("paid")
            val sched = rs.int("sched")
            val present = rs.int("present")
            OwnerKpi(
                asOf = now, activeStudents = active, activeStudentsLastMonth = maxOf(0, active - newCnt + wd),
                newThisMonth = newCnt, withdrawnThisMonth = wd, capacity = cap, fillRate = rate(rs.int("seated"), cap),
                billedThisMonth = billed, paidThisMonth = paid, collectionRate = rateL(paid, billed),
                unpaidAmount = rs.long("unpaid_amt"), unpaidCount = rs.int("unpaid_cnt"),
                todayScheduled = sched, todayPresent = present, todayAttendanceRate = rate(present, sched),
            )
        }
    }

    override fun ownerClasses(institutionId: InstitutionId, today: LocalDate, now: Instant): List<OwnerClassRow> =
        q.rows(
            """select c.id, c.name, c.capacity, c.days_mask,
                      to_char(c.start_time, 'HH24:MI') as st, to_char(c.end_time, 'HH24:MI') as et,
                      (select string_agg(u.name, chr(31) order by u.name) from classroom_teacher ct join app_user u on u.id = ct.user_id
                                 where ct.classroom_id = c.id) as teachers,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d) as sched,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status in ('IN', 'OUT')) as present,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status = 'ABSENT') as absent,
                      (select count(*) from attendance_day a where a.classroom_id = c.id and a.date = :d and a.status = 'SCHEDULED'
                          and (a.date + c.start_time) <= cast(:local_now as timestamp)) as not_arrived,
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
            params(
                "i" to institutionId.value, "d" to today, "local_now" to rdLocal(now),
                "since" to now.minus(Duration.ofDays(7)),
            ),
        ).map { rs ->
            OwnerClassRow(
                classroomId = rs.uuid("id"), name = rs.str("name"), teachers = rs.names("teachers"),
                heldToday = rs.int("days_mask") and dayBit(today) != 0,
                startTime = rs.str("st"), endTime = rs.str("et"),
                scheduled = rs.int("sched"), present = rs.int("present"), absent = rs.int("absent"), notArrived = rs.int("not_arrived"),
                capacity = rs.int("capacity"), headcount = rs.int("headcount"), waiting = rs.int("waiting"),
                readRate7d = rate(rs.int("read_cnt"), rs.int("targets")), missingAlertsToday = rs.int("missing"), flags = emptyList(),
            )
        }

    override fun inbox(institutionId: InstitutionId, today: LocalDate, now: Instant): List<InboxItem> {
        val p = params(
            "i" to institutionId.value, "d" to today, "day_start" to today.atStartOfDay(SEOUL).toInstant(),
            "stale" to now.minus(Duration.ofHours(24)),
        )
        fun item(key: String, sql: String) = q.row(sql, p)!!.let { rs -> InboxItem(key, rs.int("cnt"), rs.instantOrNull("oldest")) }
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
        val p = params("i" to institutionId.value, "d" to today, "soon" to today.plusDays(3))
        val brief = { rs: Row ->
            InvoiceBrief(rs.uuid("id"), rs.str("student_name"), rs.str("title"), rs.long("amount"), rs.date("due_date"), rs.dateOrNull("paid_on"))
        }
        val head = q.row(
            """select count(*) filter (where status = 'UNPAID') as unpaid_cnt,
                      coalesce(sum(amount) filter (where status = 'UNPAID'), 0) as unpaid_amt,
                      count(*) filter (where status = 'UNPAID' and due_date < :d) as overdue_cnt,
                      coalesce(sum(amount) filter (where status = 'UNPAID' and due_date < :d), 0) as overdue_amt
                 from invoice where institution_id = :i""",
            p,
        )!!.let { rs -> listOf(rs.long("unpaid_cnt"), rs.long("unpaid_amt"), rs.long("overdue_cnt"), rs.long("overdue_amt")) }
        val dueSoon = q.rows(
            """select v.id, s.name as student_name, v.title, v.amount, v.due_date, v.paid_on
                 from invoice v join student s on s.id = v.student_id
                where v.institution_id = :i and v.status = 'UNPAID' and v.due_date between :d and :soon
                order by v.due_date, s.name limit 10""",
            p,
        ).map(brief)
        val recent = q.rows(
            """select v.id, s.name as student_name, v.title, v.amount, v.due_date, v.paid_on
                 from invoice v join student s on s.id = v.student_id
                where v.institution_id = :i and v.status = 'PAID'
                order by v.paid_on desc nulls last, v.updated_at desc limit 5""",
            p,
        ).map(brief)
        return OwnerBilling(head[0].toInt(), head[1], head[2].toInt(), head[3], dueSoon, recent)
    }

    override fun churnSignals(institutionId: InstitutionId, today: LocalDate): List<ChurnSignal> =
        q.rows(
            """select s.id, s.name,
                      (select string_agg(c.name, chr(31) order by c.name) from enrollment e join classroom c on c.id = e.classroom_id and c.deleted_at is null
                                 where e.student_id = s.id and e.to_date is null) as classes,
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
            params(
                "i" to institutionId.value, "d" to today, "r0" to today.minusDays(27),
                "b0" to today.minusDays(55), "b1" to today.minusDays(28),
                "d90" to today.minusDays(90), "d60" to today.minusDays(60),
                "n30" to today.minusDays(30).atStartOfDay(SEOUL).toInstant(),
            ),
        ).map { rs ->
            ChurnSignal(
                rs.uuid("id"), rs.str("name"), rs.names("classes"),
                rs.int("sched_r"), rs.int("abs_r"), rs.int("sched_b"), rs.int("abs_b"),
                rs.int("notices"), rs.int("unread"), rs.bool("paused"), rs.int("overdue"),
                rs.dateOrNull("last_counsel"), rs.bool("counsel_wd"),
            )
        }

    override fun staffToday(institutionId: InstitutionId, today: LocalDate, now: Instant): OwnerStaff {
        val p = params("i" to institutionId.value, "d" to today, "bit" to dayBit(today), "local_now" to rdLocal(now))
        val staff = q.rows(
            """select u.id, u.name, m.role, sa.check_in, sa.check_out,
                      (select string_agg(c.name, chr(31) order by c.start_time) from classroom_teacher ct
                                  join classroom c on c.id = ct.classroom_id and c.deleted_at is null
                                 where ct.user_id = u.id and c.institution_id = :i and (c.days_mask & :bit) <> 0) as classes,
                      (select coalesce(sum(cv.staff_unread), 0) from conversation cv
                        where cv.institution_id = :i and cv.staff_unread > 0
                          and ${TAUGHT_STUDENT.format("cv.student_id").replace(":t", "u.id")}) as unanswered
                 from membership m
                 join app_user u on u.id = m.user_id
                 left join staff_attendance sa on sa.institution_id = :i and sa.user_id = u.id and sa.work_date = :d
                where m.institution_id = :i and m.role in ('OWNER', 'ADMIN', 'TEACHER')
                order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, u.name""",
            p,
        ).map { rs ->
            StaffTodayRow(
                rs.uuid("id"), rs.str("name"), rs.str("role"), rs.instantOrNull("check_in"), rs.instantOrNull("check_out"),
                rs.names("classes"), rs.int("unanswered"),
            )
        }
        // 담당 교사 누구도 출근 기록이 없는데 수업 30분 전 ~ 수업 중인 반
        val cover = q.rows(
            """select c.id, c.name, to_char(c.start_time, 'HH24:MI') as st, string_agg(u.name, chr(31) order by u.name) as teachers
                 from classroom c
                 join classroom_teacher ct on ct.classroom_id = c.id
                 join app_user u on u.id = ct.user_id
                where c.institution_id = :i and c.deleted_at is null and (c.days_mask & :bit) <> 0
                  and (:d + c.start_time - interval '30 minutes') <= cast(:local_now as timestamp) and (:d + c.end_time) > cast(:local_now as timestamp)
                group by c.id, c.name, c.start_time
               having bool_and(not exists (select 1 from staff_attendance sa
                                            where sa.institution_id = :i and sa.user_id = ct.user_id and sa.work_date = :d))
                order by c.start_time""",
            p,
        ).map { rs -> CoverNeeded(rs.uuid("id"), rs.str("name"), rs.str("st"), rs.names("teachers")) }
        return OwnerStaff(staff, cover)
    }

    override fun institutionCards(userId: UserId, today: LocalDate, now: Instant): List<InstitutionCard> {
        val monthStart = today.withDayOfMonth(1)
        return q.rows(
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
            params("u" to userId.value, "d" to today, "ms" to monthStart, "me" to today.withDayOfMonth(today.lengthOfMonth())),
        ).map { rs ->
            InstitutionCard(
                rs.uuid("id"), rs.str("name"), rs.str("role"), rs.int("active"),
                rate(rs.int("present"), rs.int("sched")), rateL(rs.long("paid"), rs.long("billed")), rs.int("pending"),
            )
        }
    }

    // ───────── 교사 ─────────

    override fun teacherName(teacherId: UUID): String? =
        q.rows("select name from app_user where id = :t", params("t" to teacherId)).map { rs -> rs.str("name") }.firstOrNull()

    override fun teacherClasses(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TeacherClassToday> {
        val nowTime = LocalTime.ofInstant(now, SEOUL)
        return q.rows(
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
            params("i" to institutionId.value, "t" to teacherId, "d" to today, "bit" to dayBit(today)),
        ).map { rs ->
            val start = rs.time("start_time")
            val end = rs.time("end_time")
            val phase = when {
                nowTime.isBefore(start) -> "UPCOMING"
                nowTime.isBefore(end) -> "NOW"
                else -> "DONE"
            }
            TeacherClassToday(
                rs.uuid("id"), rs.str("name"), rs.str("st"), rs.str("et"), phase,
                rs.int("sched"), rs.int("present"), rs.int("out_cnt"), rs.int("absent"),
            )
        }
    }

    private fun todo(sql: String, institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> =
        q.rows(sql, params("i" to institutionId.value, "t" to teacherId, "d" to today, "local_now" to rdLocal(now))).map { rs ->
            TodoStudent(
                rs.uuid("sid"), rs.str("sname"), rs.uuid("cid"), rs.str("cname"), rs.uuidOrNull("ad"),
                rs.long("mins"), phone(rs.strOrNull("phone_enc")),
            )
        }

    private val guardianPhone =
        "(select g.phone_enc from guardian g where g.student_id = s.id order by g.is_primary desc, g.created_at limit 1) as phone_enc"

    override fun notArrived(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> = todo(
        """select a.id as ad, s.id as sid, s.name as sname, c.id as cid, c.name as cname,
                  floor(extract(epoch from (cast(:local_now as timestamp) - (a.date + c.start_time))) / 60) as mins, $guardianPhone
             from attendance_day a
             join classroom c on c.id = a.classroom_id
             join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
             join student s on s.id = a.student_id
             join institution i on i.id = a.institution_id
            where a.institution_id = :i and a.date = :d and a.status = 'SCHEDULED'
              and (a.date + c.start_time + make_interval(mins => i.late_threshold_minutes)) <= cast(:local_now as timestamp)
              and not exists (select 1 from absence_request ar where ar.student_id = a.student_id and ar.date = a.date
                                and ar.kind = 'ABSENT' and ar.status <> 'CANCELLED')
            order by mins desc, s.name""",
        institutionId, teacherId, today, now,
    )

    override fun notCheckedOut(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<TodoStudent> = todo(
        """select a.id as ad, s.id as sid, s.name as sname, c.id as cid, c.name as cname,
                  floor(extract(epoch from (cast(:local_now as timestamp) - (a.date + c.end_time))) / 60) as mins, $guardianPhone
             from attendance_day a
             join classroom c on c.id = a.classroom_id
             join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
             join student s on s.id = a.student_id
            where a.institution_id = :i and a.date = :d and a.status = 'IN'
              and (a.date + c.end_time + interval '30 minutes') <= cast(:local_now as timestamp)
            order by mins desc, s.name""",
        institutionId, teacherId, today, now,
    )

    override fun pendingAbsences(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<TodoAbsence> =
        q.rows(
            """select r.id, s.name, r.date, r.kind, to_char(r.expected_time, 'HH24:MI') as et, r.reason
                 from absence_request r join student s on s.id = r.student_id
                where r.institution_id = :i and r.status = 'SUBMITTED' and r.date >= :d
                  and ${TAUGHT_STUDENT.format("r.student_id")}
                order by r.date, r.created_at limit 20""",
            params("i" to institutionId.value, "t" to teacherId, "d" to today),
        ).map { rs -> TodoAbsence(rs.uuid("id"), rs.str("name"), rs.date("date"), rs.str("kind"), rs.strOrNull("et"), rs.strOrNull("reason")) }

    /** 오늘 출결 대상(담당 반)인 원생의 건강·생일·사전 신청·최근 상담 */
    override fun cautions(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<StudentCaution> {
        data class CautionRow(
            val id: UUID, val name: String, val classroom: String, val birthEnc: String?, val allergiesEnc: String?, val conditionsEnc: String?,
            val absKind: String?, val absTime: String?, val absReason: String?, val counselOn: LocalDate?, val counselBody: String?,
        )
        val rows = q.rows(
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
            params("i" to institutionId.value, "t" to teacherId, "d" to today, "d7" to today.minusDays(7)),
        ).map { rs ->
            CautionRow(
                rs.uuid("id"), rs.str("name"), rs.str("cname"), rs.strOrNull("birth_enc"),
                rs.strOrNull("allergies_enc"), rs.strOrNull("conditions_enc"),
                rs.strOrNull("abs_kind"), rs.strOrNull("abs_time"), rs.strOrNull("abs_reason"),
                rs.dateOrNull("counsel_on"), rs.strOrNull("counsel_body"),
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
        q.rows(
            """select cv.id, s.name, cv.last_message_preview, cv.staff_unread, cv.last_message_at
                 from conversation cv join student s on s.id = cv.student_id
                where cv.institution_id = :i and cv.staff_unread > 0 and ${TAUGHT_STUDENT.format("cv.student_id")}
                order by cv.last_message_at limit :lim""",
            params("i" to institutionId.value, "t" to teacherId, "lim" to limit),
        ).map { rs -> UnansweredConversation(rs.uuid("id"), rs.str("name"), rs.strOrNull("last_message_preview"), rs.int("staff_unread"), rs.instant("last_message_at")) }

    override fun noticeFollowUps(institutionId: InstitutionId, teacherId: UUID, since: Instant): List<NoticeFollowUp> =
        q.rows(
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
            params("i" to institutionId.value, "t" to teacherId, "since" to since),
        ).map { rs ->
            NoticeFollowUp(
                rs.uuid("id"), rs.str("title"), rs.instant("sent_at"), rs.int("targets"), rs.int("unread"),
                rs.instantOrNull("last_resent_at")?.plus(Duration.ofMinutes(30)),
            )
        }

    override fun eventFollowUps(institutionId: InstitutionId, teacherId: UUID, now: Instant, until: Instant): List<EventFollowUp> =
        q.rows(
            """select e.id, e.title, e.rsvp_deadline,
                      (select count(*) from school_event_target t where t.event_id = e.id)
                    - (select count(*) from event_response r where r.event_id = e.id) as no_response
                 from school_event e
                where e.institution_id = :i and e.author_id = :t and e.status = 'ACTIVE' and e.rsvp_enabled
                  and e.rsvp_deadline > :now and e.rsvp_deadline <= :until
                order by e.rsvp_deadline""",
            params("i" to institutionId.value, "t" to teacherId, "now" to now, "until" to until),
        ).map { rs -> EventFollowUp(rs.uuid("id"), rs.str("title"), rs.instant("rsvp_deadline"), maxOf(0, rs.int("no_response"))) }
            .filter { it.noResponse > 0 }

    override fun consultsToday(institutionId: InstitutionId, teacherId: UUID, today: LocalDate): List<ConsultToday> =
        q.rows(
            """select cs.id, cs.starts_at, s.name, cs.mode, cs.topic
                 from consult_slot cs left join student s on s.id = cs.student_id
                where cs.institution_id = :i and cs.teacher_id = :t and cs.status = 'BOOKED'
                  and cs.starts_at >= :a and cs.starts_at < :b
                order by cs.starts_at""",
            params(
                "i" to institutionId.value, "t" to teacherId,
                "a" to today.atStartOfDay(SEOUL).toInstant(), "b" to today.plusDays(1).atStartOfDay(SEOUL).toInstant(),
            ),
        ).map { rs -> ConsultToday(rs.uuid("id"), rs.instant("starts_at"), rs.strOrNull("name"), rs.str("mode"), rs.strOrNull("topic")) }

    override fun progressMissing(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, now: Instant): List<ProgressMissing> =
        q.rows(
            """select c.id, c.name, to_char(c.end_time, 'HH24:MI') as et
                 from classroom c join classroom_teacher ct on ct.classroom_id = c.id and ct.user_id = :t
                where c.institution_id = :i and c.deleted_at is null and (c.days_mask & :bit) <> 0
                  and (:d + c.end_time) <= cast(:local_now as timestamp)
                  and not exists (select 1 from classroom_progress p where p.classroom_id = c.id and p.date = :d)
                order by c.end_time""",
            params("i" to institutionId.value, "t" to teacherId, "d" to today, "bit" to dayBit(today), "local_now" to rdLocal(now)),
        ).map { rs -> ProgressMissing(rs.uuid("id"), rs.str("name"), rs.str("et")) }

    override fun work(institutionId: InstitutionId, teacherId: UUID, today: LocalDate, weekStart: LocalDate): TeacherWork =
        q.row(
            """select (select check_in from staff_attendance where institution_id = :i and user_id = :t and work_date = :d) as cin,
                      (select check_out from staff_attendance where institution_id = :i and user_id = :t and work_date = :d) as cout,
                      (select coalesce(sum(extract(epoch from (coalesce(check_out, case when work_date = :d then now() else check_in end) - check_in))), 0)
                         from staff_attendance where institution_id = :i and user_id = :t and work_date between :w and :d) as secs,
                      (select tr.id from shuttle_trip tr where tr.institution_id = :i and tr.driver_id = :t and tr.status = 'RUNNING' limit 1) as trip_id,
                      (select r.name from shuttle_trip tr join shuttle_route r on r.id = tr.route_id
                        where tr.institution_id = :i and tr.driver_id = :t and tr.status = 'RUNNING' limit 1) as route_name,
                      exists (select 1 from shuttle_route r where r.institution_id = :i and r.active) as has_routes""",
            params("i" to institutionId.value, "t" to teacherId, "d" to today, "w" to weekStart),
        )!!.let { rs ->
            TeacherWork(
                rs.instantOrNull("cin"), rs.instantOrNull("cout"), (rs.double("secs") / 60).toLong(),
                rs.uuidOrNull("trip_id"), rs.strOrNull("route_name"), rs.bool("has_routes"),
            )
        }
}

// ───────── DASH-015·023 교직원 공지 ─────────
@Component
class StaffMemoJpaAdapter(
    private val memos: StaffMemoJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
) : StaffMemoPort {

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

    /** 대상 수·확인 수 같은 집계를 하위 쿼리로 함께 읽는 화면 모델이라 조회는 네이티브 쿼리 */
    private fun view(rs: Row) = StaffMemoView(
        id = rs.uuid("id"), title = rs.str("title"), body = rs.strOrNull("body"), dueDate = rs.dateOrNull("due_date"),
        authorName = rs.str("author_name"), createdAt = rs.instant("created_at"), toAll = rs.bool("to_all"),
        canceled = rs.instantOrNull("canceled_at") != null, readAt = rs.instantOrNull("read_at"), doneAt = rs.instantOrNull("done_at"),
        targets = rs.int("targets"), readCount = rs.int("read_cnt"), doneCount = rs.int("done_cnt"),
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
        memos.save(StaffMemoEntity(id, institutionId.value, author.value, title, body, dueDate, targets.isEmpty(), createdAt = now))
        targets.distinct().forEach { q.persist(StaffMemoTargetEntity(id, it)) }
        return id
    }

    override fun list(institutionId: InstitutionId, includeCanceled: Boolean): List<StaffMemoView> =
        q.rows(
            "$summarySelect where m.institution_id = :i ${if (includeCanceled) "" else "and m.canceled_at is null"} order by m.created_at desc limit 100",
            params("i" to institutionId.value),
        ).map(::view)

    override fun find(institutionId: InstitutionId, memoId: UUID): StaffMemoView? =
        q.row("$summarySelect where m.institution_id = :i and m.id = :m", params("i" to institutionId.value, "m" to memoId))?.let(::view)

    override fun acks(institutionId: InstitutionId, memoId: UUID): List<StaffMemoAck> =
        q.rows(
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
            params("m" to memoId),
        ).map { rs -> StaffMemoAck(rs.uuid("id"), rs.str("name"), rs.instantOrNull("read_at"), rs.instantOrNull("done_at")) }

    override fun cancel(memoId: UUID, now: Instant) {
        memos.cancel(memoId, now)
    }

    override fun mine(institutionId: InstitutionId, user: UserId, includeDone: Boolean): List<StaffMemoView> =
        q.rows(
            """select m.id, m.title, m.body, m.due_date, m.created_at, m.to_all, m.canceled_at, u.name as author_name,
                      a.read_at, a.done_at, 0 as targets, 0 as read_cnt, 0 as done_cnt
                 from staff_memo m
                 join app_user u on u.id = m.author_id
                 left join staff_memo_ack a on a.memo_id = m.id and a.user_id = :u
                where m.institution_id = :i and m.canceled_at is null and $RECEIVES
                  ${if (includeDone) "" else "and a.done_at is null"}
                order by (a.read_at is null) desc, m.due_date nulls last, m.created_at desc limit 50""",
            params("i" to institutionId.value, "u" to user.value),
        ).map(::view)

    override fun isRecipient(memoId: UUID, user: UserId): Boolean =
        q.exists(
            "select exists (select 1 from staff_memo m where m.id = :m and $RECEIVES)",
            params("m" to memoId, "u" to user.value),
        )

    override fun ack(memoId: UUID, user: UserId, done: Boolean?, now: Instant) {
        val p = params("m" to memoId, "u" to user.value, "now" to now)
        // 읽음·완료는 (공지, 사람) 키로 upsert — on conflict 는 네이티브
        when (done) {
            null -> q.update(
                "insert into staff_memo_ack (memo_id, user_id, read_at) values (:m, :u, :now) on conflict (memo_id, user_id) do nothing", p,
            )
            true -> q.update(
                """insert into staff_memo_ack (memo_id, user_id, read_at, done_at) values (:m, :u, :now, :now)
                   on conflict (memo_id, user_id) do update set done_at = coalesce(staff_memo_ack.done_at, excluded.done_at)""",
                p,
            )
            false -> q.update(
                """insert into staff_memo_ack (memo_id, user_id, read_at) values (:m, :u, :now)
                   on conflict (memo_id, user_id) do update set done_at = null""",
                p,
            )
        }
    }

    override fun recipients(institutionId: InstitutionId, memoId: UUID): List<UserId> =
        acks(institutionId, memoId).map { UserId(it.userId) }

    override fun staffIds(institutionId: InstitutionId): Set<UUID> =
        memberships.findByInstitutionIdAndRoleIn(institutionId.value, listOf("OWNER", "ADMIN", "TEACHER")).map { it.userId }.toSet()
}

// ───────── DASH-017 위젯 배치 ─────────
@Component
class DashboardLayoutJpaAdapter(private val repo: UserDashboardLayoutJpaRepository) : DashboardLayoutPort {
    private val json = jacksonObjectMapper()

    override fun find(user: UserId, kind: String): List<DashboardWidget>? =
        repo.findByIdOrNull(UserDashboardLayoutKey(user.value, kind))?.let { json.readValue<List<DashboardWidget>>(it.widgets) }

    override fun save(user: UserId, kind: String, widgets: List<DashboardWidget>, now: Instant) {
        repo.save(UserDashboardLayoutEntity(user.value, kind, json.writeValueAsString(widgets), now))
    }

    override fun delete(user: UserId, kind: String) {
        val key = UserDashboardLayoutKey(user.value, kind)
        if (repo.existsById(key)) repo.deleteById(key)
    }
}
