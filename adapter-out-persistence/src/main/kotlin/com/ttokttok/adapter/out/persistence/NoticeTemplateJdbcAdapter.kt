package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.NoticeTemplateView
import com.ttokttok.application.port.out.NoticeTemplatePort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class NoticeTemplateJdbcAdapter(jdbc: JdbcTemplate) : NoticeTemplatePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select t.id, t.kind, t.name, t.title, t.body, t.created_by, u.name as created_by_name, t.use_count, t.updated_at
            from notice_template t join app_user u on u.id = t.created_by
        """

        val MAPPER = RowMapper<NoticeTemplateView> { rs, _ ->
            NoticeTemplateView(
                id = rs.getObject("id", UUID::class.java), kind = NoticeKind.valueOf(rs.getString("kind")),
                name = rs.getString("name"), title = rs.getString("title"), body = rs.getString("body"),
                createdById = rs.getObject("created_by", UUID::class.java), createdByName = rs.getString("created_by_name"),
                useCount = rs.getInt("use_count"), updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }

    override fun list(institutionId: InstitutionId, kind: NoticeKind?): List<NoticeTemplateView> {
        val p = MapSqlParameterSource("inst", institutionId.value)
        val kindClause = if (kind != null) {
            p.addValue("kind", kind.name)
            " and t.kind = :kind"
        } else ""
        return named.query("$SELECT where t.institution_id = :inst$kindClause order by t.use_count desc, t.name", p, MAPPER)
    }

    override fun find(id: UUID, institutionId: InstitutionId): NoticeTemplateView? =
        named.query("$SELECT where t.id = :id and t.institution_id = :inst", MapSqlParameterSource("id", id).addValue("inst", institutionId.value), MAPPER).firstOrNull()

    override fun insert(institutionId: InstitutionId, creator: UserId, kind: NoticeKind, name: String, title: String, body: String, now: Instant): UUID {
        val id = com.ttokttok.domain.common.Uuid7.next()
        named.update(
            """
            insert into notice_template (id, institution_id, kind, name, title, body, created_by, created_at, updated_at)
            values (:id, :inst, :kind, :name, :title, :body, :creator, :now, :now)
            """.trimIndent(),
            MapSqlParameterSource("id", id).addValue("inst", institutionId.value).addValue("kind", kind.name).addValue("name", name)
                .addValue("title", title).addValue("body", body).addValue("creator", creator.value).addValue("now", Timestamp.from(now)),
        )
        return id
    }

    override fun update(id: UUID, kind: NoticeKind, name: String, title: String, body: String, now: Instant) {
        named.update(
            "update notice_template set kind = :kind, name = :name, title = :title, body = :body, updated_at = :now where id = :id",
            MapSqlParameterSource("id", id).addValue("kind", kind.name).addValue("name", name).addValue("title", title).addValue("body", body).addValue("now", Timestamp.from(now)),
        )
    }

    override fun delete(id: UUID) {
        named.update("delete from notice_template where id = :id", MapSqlParameterSource("id", id))
    }

    override fun incrementUse(id: UUID) {
        named.update("update notice_template set use_count = use_count + 1 where id = :id", MapSqlParameterSource("id", id))
    }

    override fun count(institutionId: InstitutionId): Int =
        named.queryForObject("select count(*) from notice_template where institution_id = :inst", MapSqlParameterSource("inst", institutionId.value), Int::class.java) ?: 0
}
