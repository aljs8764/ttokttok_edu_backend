package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.AuditRecord
import com.ttokttok.application.port.out.AuditSearchCriteria
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import com.ttokttok.application.port.out.DailyAttendanceStat
import com.ttokttok.application.port.out.DailyAttendanceStatPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * 감사 로그. 쓰기 전용·추가 전용이라 JPA 엔티티 대신 JdbcTemplate로 바로 넣는다.
 * 호출한 유스케이스의 트랜잭션에 함께 묶인다 (변경이 롤백되면 감사 기록도 남지 않음).
 */
@Component
class AuditLogJdbcAdapter(private val jdbc: JdbcTemplate) : AuditLogPort {
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun search(criteria: AuditSearchCriteria): PageResult<AuditRecord> {
        val where = StringBuilder("institution_id = :inst")
        val params = MapSqlParameterSource("inst", criteria.institutionId.value)
        criteria.action?.let { where.append(" and action = :action"); params.addValue("action", it) }
        criteria.actorId?.let { where.append(" and actor_id = :actor"); params.addValue("actor", it.value) }
        criteria.from?.let { where.append(" and at >= :from"); params.addValue("from", Timestamp.from(it)) }
        criteria.to?.let { where.append(" and at < :to"); params.addValue("to", Timestamp.from(it)) }
        val total = named.queryForObject("select count(*) from audit_log where $where", params, java.lang.Long::class.java)?.toLong() ?: 0L
        params.addValue("limit", criteria.size).addValue("offset", criteria.page.toLong() * criteria.size)
        val items = named.query(
            "select * from audit_log where $where order by at desc, id desc limit :limit offset :offset", params,
        ) { rs, _ ->
            AuditRecord(
                rs.getLong("id"),
                AuditEntry(
                    institutionId = InstitutionId(rs.getObject("institution_id", UUID::class.java)),
                    actorId = UserId(rs.getObject("actor_id", UUID::class.java)),
                    action = rs.getString("action"), resource = rs.getString("resource"), resourceId = rs.getString("resource_id"),
                    diff = json.readValue(rs.getString("diff")),
                    at = rs.getTimestamp("at").toInstant(),
                ),
            )
        }
        return PageResult(items, criteria.page, criteria.size, total)
    }

    override fun record(entry: AuditEntry) {
        jdbc.update(
            """insert into audit_log (institution_id, actor_id, action, resource, resource_id, diff, at)
               values (?, ?, ?, ?, ?, cast(? as jsonb), ?)""",
            entry.institutionId.value, entry.actorId.value, entry.action, entry.resource, entry.resourceId,
            json.writeValueAsString(entry.diff), Timestamp.from(entry.at),
        )
    }
}

/** 일별 집계 upsert — 배치를 다시 돌려도 같은 값으로 덮어쓴다 */
@Component
class DailyAttendanceStatJdbcAdapter(private val jdbc: JdbcTemplate) : DailyAttendanceStatPort {
    override fun upsert(stats: List<DailyAttendanceStat>) {
        if (stats.isEmpty()) return
        jdbc.batchUpdate(
            """insert into daily_attendance_stat
                   (institution_id, classroom_id, date, scheduled, present, late, early_leave, absent, updated_at)
               values (?, ?, ?, ?, ?, ?, ?, ?, now())
               on conflict (classroom_id, date) do update set
                   scheduled = excluded.scheduled, present = excluded.present, late = excluded.late,
                   early_leave = excluded.early_leave, absent = excluded.absent, updated_at = now()""",
            stats.map {
                arrayOf<Any>(
                    it.institutionId.value, it.classroomId.value, java.sql.Date.valueOf(it.date),
                    it.scheduled, it.present, it.late, it.earlyLeave, it.absent,
                )
            },
        )
    }
}
