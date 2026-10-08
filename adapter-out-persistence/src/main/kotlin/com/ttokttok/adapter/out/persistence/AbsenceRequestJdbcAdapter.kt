package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.AbsenceKind
import com.ttokttok.application.port.`in`.AbsenceRequestStatus
import com.ttokttok.application.port.`in`.AbsenceRequestView
import com.ttokttok.application.port.out.AbsenceRequestPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 결석·지각·조퇴 사전 신청 (ATT-007) */
@Component
class AbsenceRequestJdbcAdapter(jdbc: JdbcTemplate) : AbsenceRequestPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select r.id, r.institution_id, i.name as institution_name, r.student_id, s.name as student_name,
                   g.name as guardian_name, r.date, r.kind, r.expected_time, r.reason, r.status, r.acknowledged_at, r.created_at
            from absence_request r
            join institution i on i.id = r.institution_id
            join student s on s.id = r.student_id
            join app_user g on g.id = r.guardian_user_id
        """

        const val TEACHES = """
            exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                    where e.student_id = r.student_id and e.to_date is null and ct.user_id = :teacher)
        """

        val MAPPER = RowMapper<AbsenceRequestView> { rs, _ ->
            AbsenceRequestView(
                id = rs.uuid("id"), institutionId = rs.uuid("institution_id"), institutionName = rs.getString("institution_name"),
                studentId = rs.uuid("student_id"), studentName = rs.getString("student_name"), guardianName = rs.getString("guardian_name"),
                date = rs.getDate("date").toLocalDate(), kind = AbsenceKind.valueOf(rs.getString("kind")),
                expectedTime = rs.getTime("expected_time")?.toLocalTime(), reason = rs.getString("reason"),
                status = AbsenceRequestStatus.valueOf(rs.getString("status")),
                acknowledgedAt = rs.getTimestamp("acknowledged_at")?.toInstant(), createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

        fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
    }

    override fun insert(
        institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, date: LocalDate, kind: AbsenceKind,
        expectedTime: LocalTime?, reason: String, now: Instant,
    ): UUID? {
        val id = com.ttokttok.domain.common.Uuid7.next()
        val n = named.update(
            """
            insert into absence_request (id, institution_id, student_id, guardian_user_id, date, kind, expected_time, reason, status, created_at)
            values (:id, :inst, :student, :guardian, :date, :kind, :time, :reason, 'SUBMITTED', :now)
            on conflict (student_id, date, kind) where status <> 'CANCELLED' do nothing
            """.trimIndent(),
            MapSqlParameterSource("id", id).addValue("inst", institutionId.value).addValue("student", studentId)
                .addValue("guardian", guardianUserId.value).addValue("date", Date.valueOf(date)).addValue("kind", kind.name)
                .addValue("time", expectedTime?.let { Time.valueOf(it) }, java.sql.Types.TIME).addValue("reason", reason).addValue("now", Timestamp.from(now)),
        )
        return if (n == 1) id else null
    }

    override fun find(id: UUID): AbsenceRequestView? =
        named.query("$SELECT where r.id = :id", MapSqlParameterSource("id", id), MAPPER).firstOrNull()

    override fun guardianOf(id: UUID): UUID? =
        named.query("select guardian_user_id from absence_request where id = :id", MapSqlParameterSource("id", id)) { rs, _ -> rs.uuid("guardian_user_id") }.firstOrNull()

    override fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, fromDate: LocalDate?, limit: Int): List<AbsenceRequestView> {
        val p = MapSqlParameterSource("guardian", guardianUserId.value).addValue("limit", limit)
        val where = StringBuilder("where r.guardian_user_id = :guardian")
        if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            where.append(" and r.student_id in (:students)")
            p.addValue("students", studentIds)
        }
        if (fromDate != null) {
            where.append(" and r.date >= :from")
            p.addValue("from", Date.valueOf(fromDate))
        }
        return named.query("$SELECT $where order by r.date desc, r.created_at desc limit :limit", p, MAPPER)
    }

    override fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, from: LocalDate, to: LocalDate, status: AbsenceRequestStatus?): List<AbsenceRequestView> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to))
        val where = StringBuilder("where r.institution_id = :inst and r.date between :from and :to and r.status <> 'CANCELLED'")
        if (teacherId != null) {
            where.append(" and $TEACHES")
            p.addValue("teacher", teacherId.value)
        }
        if (status != null) {
            where.append(" and r.status = :status")
            p.addValue("status", status.name)
        }
        return named.query("$SELECT $where order by r.date, r.created_at", p, MAPPER)
    }

    override fun teaches(id: UUID, teacherId: UserId): Boolean =
        named.queryForObject(
            "select $TEACHES from absence_request r where r.id = :id",
            MapSqlParameterSource("id", id).addValue("teacher", teacherId.value), Boolean::class.java,
        ) ?: false

    override fun setStatus(id: UUID, status: AbsenceRequestStatus, actor: UserId?, now: Instant) {
        named.update(
            """
            update absence_request set status = :status, acknowledged_by = :actor,
                   acknowledged_at = case when :status = 'ACKNOWLEDGED' then :now else acknowledged_at end
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource("id", id).addValue("status", status.name).addValue("actor", actor?.value).addValue("now", Timestamp.from(now)),
        )
    }

    override fun staffRecipients(institutionId: InstitutionId, studentId: UUID): List<UserId> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("student", studentId)
        val teachers = named.query(
            """
            select distinct ct.user_id from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
            where e.student_id = :student and e.to_date is null
            """.trimIndent(),
            p,
        ) { rs, _ -> UserId(rs.uuid("user_id")) }
        if (teachers.isNotEmpty()) return teachers
        return named.query("select user_id from membership where institution_id = :inst and role in ('OWNER', 'ADMIN')", p) { rs, _ -> UserId(rs.uuid("user_id")) }
    }
}
