package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.notice.NoticeTarget
import java.time.Instant
import java.util.UUID

data class AlbumView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val title: String,
    val description: String?,
    val authorId: UUID,
    val authorName: String,
    val createdAt: Instant,
    val itemCount: Int,
    /** 목록에서는 앞 4개(썸네일용), 상세에서는 전체. 상세·목록 모두 5분 다운로드 URL 포함 */
    val items: List<FileRef>,
    /** 직원 화면에서만 */
    val recipients: Int?,
)

/** NTC-011 앨범 — 교직원 (교사는 담당 반 대상만, 전체는 원장·실장) */
interface StaffAlbumUseCase {
    /** 올린 사진·영상(purpose=ALBUM_MEDIA, 업로드 완료)을 대상에게 공개 + 푸시 */
    fun create(actor: UserId, institutionId: InstitutionId, command: Create): AlbumView
    fun list(actor: UserId, institutionId: InstitutionId, page: Int, size: Int): List<AlbumView>
    fun detail(actor: UserId, institutionId: InstitutionId, id: UUID): AlbumView
    /** 만든 사람 또는 원장·실장 */
    fun delete(actor: UserId, institutionId: InstitutionId, id: UUID)

    data class Create(val title: String, val description: String?, val files: List<FileId>, val targets: List<NoticeTarget>)
}

/** NTC-011 앨범 — 학부모 앱 */
interface ParentAlbumUseCase {
    fun list(parent: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<AlbumView>
    fun detail(parent: UserId, id: UUID): AlbumView
}
