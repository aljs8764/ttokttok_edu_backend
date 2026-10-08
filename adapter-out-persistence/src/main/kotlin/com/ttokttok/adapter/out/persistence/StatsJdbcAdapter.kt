package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.ClassroomCounts
import com.ttokttok.application.port.out.CongestionRow
import com.ttokttok.application.port.out.DailyCounts
import com.ttokttok.application.port.out.NoticeReadRow
import com.ttokttok.application.port.out.ReadCounts
import com.ttokttok.application.port.out.StatsReadPort
import com.ttokttok.application.port.out.WithdrawalRow
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.student.StudentStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 통계 집계 쿼리 (STAT-001~007). 전부 institution_id 로 좁힌 읽기 전용 집계.
 * 출결은 23:50 배치가 채우는 daily_attendance_stat 을 쓰되, 아직 집계 전인 오늘은 attendance_day 에서 같은 기준으로 바로 센다.
 * 시간대는 Asia/Seoul 고정 (요일·시간대·월 경계).
 */
@Component
class StatsJdbcAdapter(jdbc: JdbcTemplate) : StatsReadPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private fun params(institutionId: InstitutionId) = MapSqlParameterSource("inst", institutionId.value)

    /** 지난 날(집계 테이블) + 오늘(원본) 합집합. [select] 의 첫 열들이 그룹 키 */
    private fun attendanceUnion(keyColumn: String, classroomFilter: String): String = """
        select $keyColumn, sum(scheduled) as scheduled, sum(present) as present, sum(late) as late,
               sum(early_leave) as early_leave, sum(absent) as absent
        from (
            select $keyColumn, scheduled, present, late, early_leave, absent
            from daily_attendance_stat
            where institution_id = :inst and date between :from and :to and date < :today $classroomFilter
            union all
            select $keyColumn, 1,
                   case when status in ('IN', 'OUT') then 1 else 0 end,
                   case when status in ('IN', 'OUT') and is_late then 1 else 0 end,
                   case when status in ('IN', 'OUT') and is_early_leave then 1 else 0 end,
                   case when status = 'ABSENT' then 1 else 0 end
            from attendance_day
            where institution_id = :inst and date = :today and date between :from and :to $classroomFilter
        ) t
        group by $keyColumn
    """.trimIndent()

    override fun dailyAttendance(institutionId: InstitutionId, from: LocalDate, to: LocalDate, today: LocalDate, classroomId: ClassroomId?): List<DailyCounts> {
        val p = params(institutionId).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)).addValue("today", Date.valueOf(today))
        val filter = if (classroomId != null) { p.addValue("cls", classroomId.value); "and classroom_id = :cls" } else ""
        return named.query(attendanceUnion("date", filter) + " order by date", p) { rs, _ ->
            DailyCounts(
                rs.getDate("date").toLocalDate(), rs.getInt("scheduled"), rs.getInt("present"),
                rs.getInt("late"), rs.getInt("early_leave"), rs.getInt("absent"),
            )
        }
    }

    override fun classroomAttendance(institutionId: InstitutionId, from: LocalDate, to: LocalDate, today: LocalDate): List<ClassroomCounts> {
        val p = params(institutionId).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)).addValue("today", Date.valueOf(today))
        return named.query(attendanceUnion("classroom_id", ""), p) { rs, _ ->
            ClassroomCounts(
                ClassroomId(rs.getObject("classroom_id", UUID::class.java)), rs.getInt("scheduled"), rs.getInt("present"),
                rs.getInt("late"), rs.getInt("absent"),
            )
        }
    }

    override fun noticeReads(institutionId: InstitutionId, from: Instant, to: Instant): List<NoticeReadRow> =
        named.query(
            """select (n.sent_at at time zone 'Asia/Seoul')::date as d, n.id as notice_id,
                      count(distinct r.student_id) as targets,
                      count(distinct r.student_id) filter (where r.read_at is not null) as read_cnt
               from notice n join notice_recipient r on r.notice_id = n.id
               where n.institution_id = :inst and n.status = 'SENT' and n.sent_at >= :from and n.sent_at < :to
               group by 1, n.id
               order by 1""",
            params(institutionId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
        ) { rs, _ ->
            NoticeReadRow(rs.getDate("d").toLocalDate(), rs.getObject("notice_id", UUID::class.java), rs.getInt("targets"), rs.getInt("read_cnt"))
        }

    override fun classroomNoticeReads(institutionId: InstitutionId, from: Instant, to: Instant): Map<ClassroomId, ReadCounts> =
        named.query(
            """select e.classroom_id,
                      count(distinct (r.notice_id, r.student_id)) as targets,
                      count(distinct (r.notice_id, r.student_id)) filter (where r.read_at is not null) as read_cnt
               from notice n
               join notice_recipient r on r.notice_id = n.id
               join enrollment e on e.student_id = r.student_id and e.to_date is null
               where n.institution_id = :inst and n.status = 'SENT' and n.sent_at >= :from and n.sent_at < :to
               group by e.classroom_id""",
            params(institutionId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
        ) { rs, _ ->
            ClassroomId(rs.getObject("classroom_id", UUID::class.java)) to ReadCounts(rs.getInt("targets"), rs.getInt("read_cnt"))
        }.toMap()

    override fun congestion(institutionId: InstitutionId, from: Instant, to: Instant): List<CongestionRow> =
        named.query(
            """select extract(isodow from occurred_at at time zone 'Asia/Seoul')::int as weekday,
                      extract(hour from occurred_at at time zone 'Asia/Seoul')::int as hour,
                      count(*) filter (where type = 'CHECK_IN') as check_ins,
                      count(*) filter (where type = 'CHECK_OUT') as check_outs
               from attendance_event
               where institution_id = :inst and occurred_at >= :from and occurred_at < :to and type in ('CHECK_IN', 'CHECK_OUT')
               group by 1, 2""",
            params(institutionId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
        ) { rs, _ -> CongestionRow(rs.getInt("weekday"), rs.getInt("hour"), rs.getInt("check_ins"), rs.getInt("check_outs")) }

    override fun studentStatusCounts(institutionId: InstitutionId): Map<StudentStatus, Int> =
        named.query(
            "select status, count(*) as c from student where institution_id = :inst group by status", params(institutionId),
        ) { rs, _ -> StudentStatus.valueOf(rs.getString("status")) to rs.getInt("c") }.toMap()

    override fun activeStudentsByGrade(institutionId: InstitutionId): List<Pair<String, Int>> =
        named.query(
            """select coalesce(nullif(trim(grade), ''), '미지정') as g, count(*) as c
               from student where institution_id = :inst and status = 'ACTIVE'
               group by 1 order by 1""",
            params(institutionId),
        ) { rs, _ -> rs.getString("g") to rs.getInt("c") }

    override fun activeStudentsByClassroom(institutionId: InstitutionId): Map<ClassroomId, Int> =
        named.query(
            """select e.classroom_id, count(distinct s.id) as c
               from enrollment e join student s on s.id = e.student_id
               where s.institution_id = :inst and s.status = 'ACTIVE' and e.to_date is null
               group by e.classroom_id""",
            params(institutionId),
        ) { rs, _ -> ClassroomId(rs.getObject("classroom_id", UUID::class.java)) to rs.getInt("c") }.toMap()

    override fun joinedByMonth(institutionId: InstitutionId, from: Instant, to: Instant): Map<YearMonth, Int> =
        named.query(
            """select to_char(created_at at time zone 'Asia/Seoul', 'YYYY-MM') as m, count(*) as c
               from student where institution_id = :inst and created_at >= :from and created_at < :to
               group by 1""",
            params(institutionId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
        ) { rs, _ -> YearMonth.parse(rs.getString("m")) to rs.getInt("c") }.toMap()

    override fun withdrawals(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<WithdrawalRow> =
        named.query(
            """select to_char(effective_date, 'YYYY-MM') as m, coalesce(reason, 'OTHER') as reason, count(*) as c
               from student_status_history
               where institution_id = :inst and to_status = 'WITHDRAWN' and effective_date between :from and :to
               group by 1, 2""",
            params(institutionId).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
        ) { rs, _ -> WithdrawalRow(YearMonth.parse(rs.getString("m")), rs.getString("reason"), rs.getInt("c")) }
}
