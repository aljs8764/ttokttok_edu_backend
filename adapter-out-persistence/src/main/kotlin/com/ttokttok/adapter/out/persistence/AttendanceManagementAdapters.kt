package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.AuditLogEntity
import com.ttokttok.adapter.out.persistence.entity.DailyAttendanceStatEntity
import com.ttokttok.adapter.out.persistence.repository.AuditLogJpaRepository
import com.ttokttok.adapter.out.persistence.repository.DailyAttendanceStatJpaRepository
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.AuditRecord
import com.ttokttok.application.port.out.AuditSearchCriteria
import com.ttokttok.application.port.out.DailyAttendanceStat
import com.ttokttok.application.port.out.DailyAttendanceStatPort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 감사 로그. 추가 전용.
 * 호출한 유스케이스의 트랜잭션에 함께 묶인다 (변경이 롤백되면 감사 기록도 남지 않음).
 */
@Component
class AuditLogJpaAdapter(private val repo: AuditLogJpaRepository, private val q: JpaQueries) : AuditLogPort {
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    override fun search(criteria: AuditSearchCriteria): PageResult<AuditRecord> {
        val where = StringBuilder("a.institutionId = :inst")
        val p = params("inst" to criteria.institutionId.value)
        criteria.action?.let { where.append(" and a.action = :action"); p["action"] = it }
        criteria.actorId?.let { where.append(" and a.actorId = :actor"); p["actor"] = it.value }
        criteria.from?.let { where.append(" and a.at >= :from"); p["from"] = it }
        criteria.to?.let { where.append(" and a.at < :to"); p["to"] = it }
        val total = q.jpqlCount("select count(a) from AuditLogEntity a where $where", p)
        val items = q.jpql(
            "select a from AuditLogEntity a where $where order by a.at desc, a.id desc", AuditLogEntity::class.java, p,
            limit = criteria.size, offset = criteria.page * criteria.size,
        ).map {
            AuditRecord(
                it.id!!,
                AuditEntry(
                    institutionId = InstitutionId(it.institutionId), actorId = UserId(it.actorId),
                    action = it.action, resource = it.resource, resourceId = it.resourceId,
                    diff = json.readValue(it.diff), at = it.at,
                ),
            )
        }
        return PageResult(items, criteria.page, criteria.size, total)
    }

    override fun record(entry: AuditEntry) {
        repo.save(
            AuditLogEntity(
                null, entry.institutionId.value, entry.actorId.value, entry.action, entry.resource, entry.resourceId,
                json.writeValueAsString(entry.diff), entry.at,
            ),
        )
    }
}

/** 일별 집계 upsert — 배치를 다시 돌려도 같은 값으로 덮어쓴다 ((반, 날짜) 키로 merge) */
@Component
class DailyAttendanceStatJpaAdapter(private val repo: DailyAttendanceStatJpaRepository) : DailyAttendanceStatPort {
    override fun upsert(stats: List<DailyAttendanceStat>) {
        if (stats.isEmpty()) return
        val now = Instant.now()
        repo.saveAll(
            stats.map {
                DailyAttendanceStatEntity(
                    it.classroomId.value, it.date, it.institutionId.value, it.scheduled, it.present, it.late, it.earlyLeave, it.absent, now,
                )
            },
        )
    }
}
