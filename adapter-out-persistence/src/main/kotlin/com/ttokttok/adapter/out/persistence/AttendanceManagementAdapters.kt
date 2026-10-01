package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
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
