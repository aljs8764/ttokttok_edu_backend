package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** NTC-011 사진/영상 앨범 (V14, V22 event_id) */
@Entity @Table(name = "album")
class AlbumEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var authorId: UUID,
    var title: String,
    var description: String?,
    var createdAt: Instant,
    var eventId: UUID? = null,
)

@Entity @Table(name = "album_item") @IdClass(AlbumItemKey::class)
class AlbumItemEntity(
    @Id var albumId: UUID,
    @Id var position: Int,
    var fileId: UUID,
)

data class AlbumItemKey(var albumId: UUID? = null, var position: Int? = null) : Serializable

/** 공개 대상 원생 스냅샷 */
@Entity @Table(name = "album_recipient") @IdClass(AlbumRecipientKey::class)
class AlbumRecipientEntity(
    @Id var albumId: UUID,
    @Id var studentId: UUID,
)

data class AlbumRecipientKey(var albumId: UUID? = null, var studentId: UUID? = null) : Serializable
