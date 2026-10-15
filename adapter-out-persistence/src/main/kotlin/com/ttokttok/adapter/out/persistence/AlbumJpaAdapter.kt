package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.AlbumEntity
import com.ttokttok.adapter.out.persistence.entity.AlbumItemEntity
import com.ttokttok.adapter.out.persistence.entity.AlbumRecipientEntity
import com.ttokttok.adapter.out.persistence.repository.AlbumItemJpaRepository
import com.ttokttok.adapter.out.persistence.repository.AlbumJpaRepository
import com.ttokttok.adapter.out.persistence.repository.AlbumRecipientJpaRepository
import com.ttokttok.application.port.out.AlbumPort
import com.ttokttok.application.port.out.AlbumRow
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.file.FileId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 앨범 (NTC-011) */
@Component
class AlbumJpaAdapter(
    private val albums: AlbumJpaRepository,
    private val items: AlbumItemJpaRepository,
    private val recipients: AlbumRecipientJpaRepository,
    private val q: JpaQueries,
) : AlbumPort {

    private companion object {
        const val SELECT = """
            select a, i.name, u.name,
                   (select count(x) from AlbumItemEntity x where x.albumId = a.id),
                   (select count(r) from AlbumRecipientEntity r where r.albumId = a.id)
              from AlbumEntity a join InstitutionEntity i on i.id = a.institutionId join UserEntity u on u.id = a.authorId
        """

        fun row(r: Array<Any?>): AlbumRow {
            val a = r[0] as AlbumEntity
            return AlbumRow(
                a.id, a.institutionId, r[1] as String, a.title, a.description, a.authorId, r[2] as String, a.createdAt,
                (r[3] as Number).toInt(), (r[4] as Number).toInt(),
            )
        }
    }

    override fun insert(institutionId: InstitutionId, author: UserId, title: String, description: String?, now: Instant, files: List<FileId>, studentIds: Collection<UUID>): UUID {
        val id = Uuid7.next()
        albums.save(AlbumEntity(id, institutionId.value, author.value, title, description, now))
        items.saveAll(files.mapIndexed { i, f -> AlbumItemEntity(id, i, f.value) })
        recipients.saveAll(studentIds.distinct().map { AlbumRecipientEntity(id, it) })
        return id
    }

    override fun find(id: UUID): AlbumRow? = albums.findView(id).firstOrNull()?.let(::row)

    override fun fileIds(albumId: UUID): List<FileId> = items.findByAlbumIdOrderByPosition(albumId).map { FileId(it.fileId) }

    override fun firstFileIds(albumIds: Collection<UUID>, perAlbum: Int): Map<UUID, List<FileId>> {
        if (albumIds.isEmpty()) return emptyMap()
        return items.findByAlbumIdInAndPositionLessThanOrderByAlbumIdAscPositionAsc(albumIds, perAlbum)
            .groupBy({ it.albumId }, { FileId(it.fileId) })
    }

    override fun listForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<AlbumRow> {
        val p = params("inst" to institutionId.value)
        val author = if (authorId != null) {
            p["author"] = authorId.value
            " and a.authorId = :author"
        } else ""
        return q.jpqlRows("$SELECT where a.institutionId = :inst$author order by a.createdAt desc", p, limit = limit, offset = offset).map(::row)
    }

    override fun listForStudents(studentIds: Collection<UUID>, before: Instant?, limit: Int): List<AlbumRow> {
        if (studentIds.isEmpty()) return emptyList()
        val p = params("students" to studentIds)
        val beforeClause = if (before != null) {
            p["before"] = before
            " and a.createdAt < :before"
        } else ""
        return q.jpqlRows(
            "$SELECT where a.id in (select r.albumId from AlbumRecipientEntity r where r.studentId in (:students))$beforeClause order by a.createdAt desc",
            p, limit = limit,
        ).map(::row)
    }

    override fun isRecipient(albumId: UUID, studentIds: Collection<UUID>): Boolean =
        studentIds.isNotEmpty() && recipients.existsByAlbumIdAndStudentIdIn(albumId, studentIds)

    override fun delete(id: UUID) {
        albums.deleteAlbum(id)
    }
}
