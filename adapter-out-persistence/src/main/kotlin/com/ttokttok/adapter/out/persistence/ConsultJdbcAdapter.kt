package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.ConsultMode
import com.ttokttok.application.port.`in`.ConsultSlotView
import com.ttokttok.application.port.`in`.ConsultStatus
import com.ttokttok.application.port.out.ConsultSlotPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.cid(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.cts(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ctime(i: Instant?) = i?.let { Timestamp.from(it) }
private fun cp(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

/** NTC-008 상담 예약 슬롯 */
@Component
class ConsultSlotJdbcAdapter(jdbc: JdbcTemplate) : ConsultSlotPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select cs.id, cs.institution_id, i.name as institution_name, cs.teacher_id, t.name as teacher_name,
                   cs.starts_at, cs.ends_at, cs.mode, cs.location, cs.status,
                   cs.student_id, s.name as student_name, cs.booked_by, b.name as booked_by_name, cs.topic, cs.booked_at, cs.cancel_reason
              from consult_slot cs
              join institution i on i.id = cs.institution_id
              join app_user t on t.id = cs.teacher_id
              left join student s on s.id = cs.student_id
              left join app_user b on b.id = cs.booked_by
        """
        /** 원생의 현재 반 담당 교사이거나, 그 기관의 원장·실장이 연 슬롯 */
        const val ELIGIBLE = """
            (exists (select 1 from classroom_teacher ct join enrollment e on e.classroom_id = ct.classroom_id
                      where ct.user_id = cs.teacher_id and e.to_date is null and e.student_id in (:ids))
             or exists (select 1 from membership m where m.user_id = cs.teacher_id and m.institution_id = cs.institution_id and m.role in ('OWNER', 'ADMIN')))
        """
        val MAPPER = RowMapper<ConsultSlotView> { rs, _ ->
            ConsultSlotView(
                rs.cid("id")!!, rs.cid("institution_id")!!, rs.getString("institution_name"), rs.cid("teacher_id")!!, rs.getString("teacher_name"),
                rs.cts("starts_at")!!, rs.cts("ends_at")!!, ConsultMode.valueOf(rs.getString("mode")), rs.getString("location"),
                ConsultStatus.valueOf(rs.getString("status")), rs.cid("student_id"), rs.getString("student_name"),
                rs.cid("booked_by"), rs.getString("booked_by_name"), rs.getString("topic"), rs.cts("booked_at"), rs.getString("cancel_reason"),
            )
        }
    }

    override fun insert(institutionId: InstitutionId, teacherId: UUID, startsAt: Instant, endsAt: Instant, mode: ConsultMode, location: String?, createdBy: UserId, now: Instant): UUID? {
        val id = Uuid7.next()
        return try {
            named.update(
                """insert into consult_slot (id, institution_id, teacher_id, starts_at, ends_at, mode, location, created_by, created_at, updated_at)
                   values (:id, :i, :t, :s, :e, :m, :loc, :by, :now, :now)""",
                cp("id" to id, "i" to institutionId.value, "t" to teacherId, "s" to ctime(startsAt), "e" to ctime(endsAt), "m" to mode.name,
                    "loc" to location, "by" to createdBy.value, "now" to ctime(now)),
            )
            id
        } catch (e: DuplicateKeyException) {
            null
        }
    }

    override fun find(id: UUID): ConsultSlotView? = named.query("$SELECT where cs.id = :id", cp("id" to id), MAPPER).firstOrNull()

    override fun findAll(ids: Collection<UUID>): List<ConsultSlotView> =
        if (ids.isEmpty()) emptyList() else named.query("$SELECT where cs.id in (:ids)", cp("ids" to ids), MAPPER)

    override fun list(institutionId: InstitutionId, from: Instant, to: Instant, teacherId: UUID?): List<ConsultSlotView> =
        named.query(
            "$SELECT where cs.institution_id = :i and cs.starts_at >= :from and cs.starts_at < :to " +
                "and (cast(:t as uuid) is null or cs.teacher_id = cast(:t as uuid)) order by cs.starts_at, t.name",
            cp("i" to institutionId.value, "from" to ctime(from), "to" to ctime(to), "t" to teacherId), MAPPER,
        )

    override fun availableFor(studentIds: Collection<UUID>, after: Instant): List<ConsultSlotView> {
        if (studentIds.isEmpty()) return emptyList()
        return named.query(
            """$SELECT where cs.status = 'OPEN' and cs.starts_at > :after
                 and cs.institution_id in (select st.institution_id from student st where st.id in (:ids))
                 and $ELIGIBLE
               order by cs.starts_at limit 300""",
            cp("ids" to studentIds, "after" to ctime(after)), MAPPER,
        )
    }

    override fun bookedBy(parent: UserId, studentIds: Collection<UUID>?, after: Instant): List<ConsultSlotView> {
        val params = cp("u" to parent.value, "after" to ctime(after))
        val filter = if (studentIds != null) {
            params.addValue("ids", studentIds.ifEmpty { setOf(UUID(0, 0)) })
            " and cs.student_id in (:ids)"
        } else ""
        return named.query(
            "$SELECT where cs.booked_by = :u and cs.status in ('BOOKED', 'DONE', 'CANCELED') and cs.starts_at > :after$filter order by cs.starts_at desc limit 100",
            params, MAPPER,
        )
    }

    override fun book(slotId: UUID, studentId: UUID, parent: UserId, topic: String?, now: Instant): Boolean =
        named.update(
            """update consult_slot set status = 'BOOKED', student_id = :s, booked_by = :u, topic = :topic, booked_at = :now, updated_at = :now
                where id = :id and status = 'OPEN'""",
            cp("id" to slotId, "s" to studentId, "u" to parent.value, "topic" to topic, "now" to ctime(now)),
        ) > 0

    override fun release(slotId: UUID, parent: UserId, now: Instant): Boolean =
        named.update(
            """update consult_slot set status = 'OPEN', student_id = null, booked_by = null, topic = null, booked_at = null, updated_at = :now
                where id = :id and status = 'BOOKED' and booked_by = :u""",
            cp("id" to slotId, "u" to parent.value, "now" to ctime(now)),
        ) > 0

    override fun setStatus(slotId: UUID, status: String, cancelReason: String?, now: Instant) {
        named.update(
            "update consult_slot set status = :st, cancel_reason = coalesce(:r, cancel_reason), updated_at = :now where id = :id",
            cp("id" to slotId, "st" to status, "r" to cancelReason, "now" to ctime(now)),
        )
    }

    override fun deleteOpen(slotId: UUID): Boolean =
        named.update("delete from consult_slot where id = :id and status in ('OPEN', 'CANCELED') and booked_by is null", cp("id" to slotId)) > 0

    override fun eligible(slotId: UUID, studentId: UUID): Boolean =
        named.queryForObject(
            """select exists(select 1 from consult_slot cs join student st on st.id = :sid and st.institution_id = cs.institution_id
                              where cs.id = :id and ${ELIGIBLE.replace(":ids", ":sid")})""",
            cp("id" to slotId, "sid" to studentId), Boolean::class.java,
        ) ?: false

    override fun isStaff(institutionId: InstitutionId, userId: UUID): Boolean =
        named.queryForObject(
            "select exists(select 1 from membership where institution_id = :i and user_id = :u and role <> 'PARENT')",
            cp("i" to institutionId.value, "u" to userId), Boolean::class.java,
        ) ?: false
}
