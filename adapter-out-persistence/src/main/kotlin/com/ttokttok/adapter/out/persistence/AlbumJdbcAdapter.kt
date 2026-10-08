package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.AlbumPort
import com.ttokttok.application.port.out.AlbumRow
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.file.FileId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 앨범 (NTC-011) */
@Component
class AlbumJdbcAdapter(jdbc: JdbcTemplate) : AlbumPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select a.id, a.institution_id, i.name as institution_name, a.title, a.description, a.author_id, u.name as author_name, a.created_at,
                   (select count(*) from album_item x where x.album_id = a.id) as item_count,
                   (select count(*) from album_recipient r where r.album_id = a.id) as recipients
            from album a join institution i on i.id = a.institution_id join app_user u on u.id = a.author_id
        """

        val MAPPER = RowMapper<AlbumRow> { rs, _ ->
            AlbumRow(
                rs.getObject("id", UUID::class.java), rs.getObject("institution_id", UUID::class.java), rs.getString("institution_name"),
                rs.getString("title"), rs.getString("description"), rs.getObject("author_id", UUID::class.java), rs.getString("author_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("item_count"), rs.getInt("recipients"),
            )
        }
    }

    override fun insert(institutionId: InstitutionId, author: UserId, title: String, description: String?, now: Instant, files: List<FileId>, studentIds: Collection<UUID>): UUID {
        val id = Uuid7.next()
        named.update(
            "insert into album (id, institution_id, author_id, title, description, created_at) values (:id, :inst, :author, :title, :description, :now)",
            MapSqlParameterSource("id", id).addValue("inst", institutionId.value).addValue("author", author.value).addValue("title", title)
                .addValue("description", description).addValue("now", Timestamp.from(now)),
        )
        named.batchUpdate(
            "insert into album_item (album_id, file_id, position) values (:album, :file, :pos)",
            files.mapIndexed { i, f -> MapSqlParameterSource("album", id).addValue("file", f.value).addValue("pos", i) }.toTypedArray(),
        )
        named.batchUpdate(
            "insert into album_recipient (album_id, student_id) values (:album, :student)",
            studentIds.distinct().map { MapSqlParameterSource("album", id).addValue("student", it) }.toTypedArray(),
        )
        return id
    }

    override fun find(id: UUID): AlbumRow? = named.query("$SELECT where a.id = :id", MapSqlParameterSource("id", id), MAPPER).firstOrNull()

    override fun fileIds(albumId: UUID): List<FileId> =
        named.query("select file_id from album_item where album_id = :id order by position", MapSqlParameterSource("id", albumId)) { rs, _ -> FileId(rs.getObject("file_id", UUID::class.java)) }

    override fun firstFileIds(albumIds: Collection<UUID>, perAlbum: Int): Map<UUID, List<FileId>> {
        if (albumIds.isEmpty()) return emptyMap()
        return named.query(
            "select album_id, file_id from album_item where album_id in (:ids) and position < :n order by album_id, position",
            MapSqlParameterSource("ids", albumIds).addValue("n", perAlbum),
        ) { rs, _ -> rs.getObject("album_id", UUID::class.java) to FileId(rs.getObject("file_id", UUID::class.java)) }
            .groupBy({ it.first }, { it.second })
    }

    override fun listForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<AlbumRow> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("limit", limit).addValue("offset", offset)
        val author = if (authorId != null) {
            p.addValue("author", authorId.value)
            " and a.author_id = :author"
        } else ""
        return named.query("$SELECT where a.institution_id = :inst$author order by a.created_at desc limit :limit offset :offset", p, MAPPER)
    }

    override fun listForStudents(studentIds: Collection<UUID>, before: Instant?, limit: Int): List<AlbumRow> {
        val p = MapSqlParameterSource("students", studentIds).addValue("limit", limit)
        val beforeClause = if (before != null) {
            p.addValue("before", Timestamp.from(before))
            " and a.created_at < :before"
        } else ""
        return named.query(
            "$SELECT where a.id in (select album_id from album_recipient where student_id in (:students))$beforeClause order by a.created_at desc limit :limit",
            p, MAPPER,
        )
    }

    override fun isRecipient(albumId: UUID, studentIds: Collection<UUID>): Boolean {
        if (studentIds.isEmpty()) return false
        return named.queryForObject(
            "select exists (select 1 from album_recipient where album_id = :a and student_id in (:students))",
            MapSqlParameterSource("a", albumId).addValue("students", studentIds), Boolean::class.java,
        ) ?: false
    }

    override fun delete(id: UUID) {
        named.update("delete from album where id = :id", MapSqlParameterSource("id", id))
    }
}
