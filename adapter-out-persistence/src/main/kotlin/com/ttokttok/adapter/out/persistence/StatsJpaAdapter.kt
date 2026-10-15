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
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 통계 집계 쿼리 (STAT-001~007). 전부 institution_id 로 좁힌 읽기 전용 집계.
 * 출결은 23:50 배치가 채우는 daily_attendance_stat 을 쓰되, 아직 집계 전인 오늘은 attendance_day 에서 같은 기준으로 바로 센다.
 * 시간대는 Asia/Seoul 고정 (요일·시간대·월 경계).
 * union·filter 집계·at time zone 을 쓰는 읽기 전용 집계라 JPA 네이티브 쿼리([JpaQueries])로 실행한다.
 */
@Component
class StatsJpaAdapter(private val q: JpaQueries) : StatsReadPort {

    private fun instParams(institutionId: InstitutionId) = params("inst" to institutionId.value)

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
        val p = instParams(institutionId).apply { put("from", from); put("to", to); put("today", today) }
        val filter = if (classroomId != null) { p["cls"] = classroomId.value; "and classroom_id = :cls" } else ""
        return q.rows(attendanceUnion("date", filter) + " order by date", p).map { rs ->
            DailyCounts(
                rs.date("date"), rs.int("scheduled"), rs.int("present"),
                rs.int("late"), rs.int("early_leave"), rs.int("absent"),
            )
        }
    }

    override fun classroomAttendance(institutionId: InstitutionId, from: LocalDate, to: LocalDate, today: LocalDate): List<ClassroomCounts> {
        val p = instParams(institutionId).apply { put("from", from); put("to", to); put("today", today) }
        return q.rows(attendanceUnion("classroom_id", ""), p).map { rs ->
            ClassroomCounts(
                ClassroomId(rs.uuid("classroom_id")), rs.int("scheduled"), rs.int("present"),
                rs.int("late"), rs.int("absent"),
            )
        }
    }

    override fun noticeReads(institutionId: InstitutionId, from: Instant, to: Instant): List<NoticeReadRow> =
        q.rows(
            """select (n.sent_at at time zone 'Asia/Seoul')::date as d, n.id as notice_id,
                      count(distinct r.student_id) as targets,
                      count(distinct r.student_id) filter (where r.read_at is not null) as read_cnt
               from notice n join notice_recipient r on r.notice_id = n.id
               where n.institution_id = :inst and n.status = 'SENT' and n.sent_at >= :from and n.sent_at < :to
               group by 1, n.id
               order by 1""",
            instParams(institutionId).apply { put("from", from); put("to", to) },
        ).map { rs ->
            NoticeReadRow(rs.date("d"), rs.uuid("notice_id"), rs.int("targets"), rs.int("read_cnt"))
        }

    override fun classroomNoticeReads(institutionId: InstitutionId, from: Instant, to: Instant): Map<ClassroomId, ReadCounts> =
        q.rows(
            """select e.classroom_id,
                      count(distinct (r.notice_id, r.student_id)) as targets,
                      count(distinct (r.notice_id, r.student_id)) filter (where r.read_at is not null) as read_cnt
               from notice n
               join notice_recipient r on r.notice_id = n.id
               join enrollment e on e.student_id = r.student_id and e.to_date is null
               where n.institution_id = :inst and n.status = 'SENT' and n.sent_at >= :from and n.sent_at < :to
               group by e.classroom_id""",
            instParams(institutionId).apply { put("from", from); put("to", to) },
        ).map { rs ->
            ClassroomId(rs.uuid("classroom_id")) to ReadCounts(rs.int("targets"), rs.int("read_cnt"))
        }.toMap()

    override fun congestion(institutionId: InstitutionId, from: Instant, to: Instant): List<CongestionRow> =
        q.rows(
            """select extract(isodow from occurred_at at time zone 'Asia/Seoul')::int as weekday,
                      extract(hour from occurred_at at time zone 'Asia/Seoul')::int as hour,
                      count(*) filter (where type = 'CHECK_IN') as check_ins,
                      count(*) filter (where type = 'CHECK_OUT') as check_outs
               from attendance_event
               where institution_id = :inst and occurred_at >= :from and occurred_at < :to and type in ('CHECK_IN', 'CHECK_OUT')
               group by 1, 2""",
            instParams(institutionId).apply { put("from", from); put("to", to) },
        ).map { rs -> CongestionRow(rs.int("weekday"), rs.int("hour"), rs.int("check_ins"), rs.int("check_outs")) }

    override fun studentStatusCounts(institutionId: InstitutionId): Map<StudentStatus, Int> =
        q.rows(
            "select status, count(*) as c from student where institution_id = :inst group by status", instParams(institutionId),
        ).map { rs -> StudentStatus.valueOf(rs.str("status")) to rs.int("c") }.toMap()

    override fun activeStudentsByGrade(institutionId: InstitutionId): List<Pair<String, Int>> =
        q.rows(
            """select coalesce(nullif(trim(grade), ''), '미지정') as g, count(*) as c
               from student where institution_id = :inst and status = 'ACTIVE'
               group by 1 order by 1""",
            instParams(institutionId),
        ).map { rs -> rs.str("g") to rs.int("c") }

    override fun activeStudentsByClassroom(institutionId: InstitutionId): Map<ClassroomId, Int> =
        q.rows(
            """select e.classroom_id, count(distinct s.id) as c
               from enrollment e join student s on s.id = e.student_id
               where s.institution_id = :inst and s.status = 'ACTIVE' and e.to_date is null
               group by e.classroom_id""",
            instParams(institutionId),
        ).map { rs -> ClassroomId(rs.uuid("classroom_id")) to rs.int("c") }.toMap()

    override fun joinedByMonth(institutionId: InstitutionId, from: Instant, to: Instant): Map<YearMonth, Int> =
        q.rows(
            """select to_char(created_at at time zone 'Asia/Seoul', 'YYYY-MM') as m, count(*) as c
               from student where institution_id = :inst and created_at >= :from and created_at < :to
               group by 1""",
            instParams(institutionId).apply { put("from", from); put("to", to) },
        ).map { rs -> YearMonth.parse(rs.str("m")) to rs.int("c") }.toMap()

    override fun withdrawals(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<WithdrawalRow> =
        q.rows(
            """select to_char(effective_date, 'YYYY-MM') as m, coalesce(reason, 'OTHER') as reason, count(*) as c
               from student_status_history
               where institution_id = :inst and to_status = 'WITHDRAWN' and effective_date between :from and :to
               group by 1, 2""",
            instParams(institutionId).apply { put("from", from); put("to", to) },
        ).map { rs -> WithdrawalRow(YearMonth.parse(rs.str("m")), rs.str("reason"), rs.int("c")) }
}
