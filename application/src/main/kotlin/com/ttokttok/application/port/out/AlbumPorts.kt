package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import java.time.Instant
import java.util.UUID

data class AlbumRow(
    val id: UUID, val institutionId: UUID, val institutionName: String, val title: String, val description: String?,
    val authorId: UUID, val authorName: String, val createdAt: Instant, val itemCount: Int, val recipients: Int,
)

/** NTC-011 저장소 (JPA) */
interface AlbumPort {
    fun insert(institutionId: InstitutionId, author: UserId, title: String, description: String?, now: Instant, files: List<FileId>, studentIds: Collection<UUID>): UUID
    fun find(id: UUID): AlbumRow?
    fun fileIds(albumId: UUID): List<FileId>
    /** 목록 썸네일: 앨범별 앞 [perAlbum]개 */
    fun firstFileIds(albumIds: Collection<UUID>, perAlbum: Int): Map<UUID, List<FileId>>
    fun listForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<AlbumRow>
    fun listForStudents(studentIds: Collection<UUID>, before: Instant?, limit: Int): List<AlbumRow>
    fun isRecipient(albumId: UUID, studentIds: Collection<UUID>): Boolean
    fun delete(id: UUID)
}
