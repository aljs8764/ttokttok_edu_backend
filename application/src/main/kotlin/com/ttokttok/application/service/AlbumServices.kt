package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AlbumView
import com.ttokttok.application.port.`in`.ParentAlbumUseCase
import com.ttokttok.application.port.`in`.StaffAlbumUseCase
import com.ttokttok.application.port.out.AlbumPort
import com.ttokttok.application.port.out.AlbumRow
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.student.GuardianLinkStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private const val MAX_ITEMS = 30
private const val THUMBS = 4

private fun AlbumRow.toView(items: List<com.ttokttok.application.port.`in`.FileRef>, showRecipients: Boolean) = AlbumView(
    id, institutionId, institutionName, title, description, authorId, authorName, createdAt, itemCount, items, if (showRecipients) recipients else null,
)

@Service
class StaffAlbumService(
    private val albums: AlbumPort,
    private val audience: NoticeAudience,
    private val guard: AccessGuard,
    private val guardians: GuardianPort,
    private val fileRefs: FileRefResolver,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : StaffAlbumUseCase {

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: StaffAlbumUseCase.Create): AlbumView {
        audience.authorize(actor, institutionId, NoticeKind.NOTE, command.targets)
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "제목은 1~100자입니다")
        if ((command.description?.length ?: 0) > 1000) throw InvalidInputException("INVALID_DESCRIPTION", "설명은 1000자까지입니다")
        if (command.files.isEmpty() || command.files.size > MAX_ITEMS) throw InvalidInputException("INVALID_ITEMS", "사진·영상은 1~${MAX_ITEMS}개입니다")
        if (command.files.distinct().size != command.files.size) throw InvalidInputException("INVALID_ITEMS", "같은 파일이 두 번 들어 있습니다")
        if (command.targets.isEmpty()) throw InvalidInputException("TARGET_REQUIRED", "공개할 대상을 지정하세요")
        fileRefs.requireUsable(command.files, institutionId, FilePurpose.ALBUM_MEDIA)
        val students = audience.expand(institutionId, command.targets)
        if (students.isEmpty()) throw InvalidInputException("NO_RECIPIENTS", "공개할 원생이 없습니다")
        val now = clock.now()
        val id = albums.insert(institutionId, actor, title, command.description?.trim()?.ifEmpty { null }, now, command.files, students.map { it.id.value })
        val row = albums.find(id) ?: throw NotFoundException("앨범")

        val users = guardians.findByStudents(students.map { it.id })
            .filter { it.linkStatus == GuardianLinkStatus.LINKED }.mapNotNull { it.userId }.distinct()
        if (users.isNotEmpty()) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "ALBUM_NEW", "[${row.institutionName}] 새 앨범", "$title · 사진·영상 ${row.itemCount}개",
                    mapOf("type" to "album", "albumId" to id.toString()), users, now,
                ),
            )
            realtime.userEvents(users, mapOf("type" to "album.new", "albumId" to id.toString()))
        }
        return row.toView(fileRefs.refs(command.files, withUrl = true), showRecipients = true)
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, page: Int, size: Int): List<AlbumView> {
        val m = guard.requireStaff(actor, institutionId)
        if (page < 0 || size !in 1..50) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~50입니다")
        val rows = albums.listForStaff(institutionId, if (m.role.isManager) null else actor, size, page * size)
        val thumbs = albums.firstFileIds(rows.map { it.id }, THUMBS)
        return rows.map { it.toView(fileRefs.refs(thumbs[it.id].orEmpty(), withUrl = true), showRecipients = true) }
    }

    @Transactional(readOnly = true)
    override fun detail(actor: UserId, institutionId: InstitutionId, id: UUID): AlbumView {
        val row = access(actor, institutionId, id)
        return row.toView(fileRefs.refs(albums.fileIds(id), withUrl = true), showRecipients = true)
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: UUID) {
        access(actor, institutionId, id)
        albums.delete(id)
    }

    private fun access(actor: UserId, institutionId: InstitutionId, id: UUID): AlbumRow {
        val m = guard.requireStaff(actor, institutionId)
        val row = albums.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("앨범")
        if (!m.role.isManager && row.authorId != actor.value) throw ForbiddenException("본인이 올린 앨범만 다룰 수 있습니다")
        return row
    }
}

@Service
class ParentAlbumService(
    private val albums: AlbumPort,
    private val guardians: GuardianPort,
    private val fileRefs: FileRefResolver,
) : ParentAlbumUseCase {

    private fun mine(parent: UserId) = guardians.findLinkedByUser(parent).filter { it.linkStatus == GuardianLinkStatus.LINKED }.map { it.studentId.value }.distinct()

    @Transactional(readOnly = true)
    override fun list(parent: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<AlbumView> {
        if (limit !in 1..50) throw InvalidInputException("INVALID_LIMIT", "limit은 1~50입니다")
        val mine = mine(parent)
        val scope = if (studentIds == null) mine else { if (!mine.containsAll(studentIds)) throw ForbiddenException("본인 자녀가 아닙니다"); studentIds.toList() }
        if (scope.isEmpty()) return emptyList()
        val rows = albums.listForStudents(scope, before, limit)
        val thumbs = albums.firstFileIds(rows.map { it.id }, THUMBS)
        return rows.map { it.toView(fileRefs.refs(thumbs[it.id].orEmpty(), withUrl = true), showRecipients = false) }
    }

    @Transactional(readOnly = true)
    override fun detail(parent: UserId, id: UUID): AlbumView {
        val row = albums.find(id) ?: throw NotFoundException("앨범")
        if (!albums.isRecipient(id, mine(parent))) throw ForbiddenException("공개되지 않은 앨범입니다")
        return row.toView(fileRefs.refs(albums.fileIds(id), withUrl = true), showRecipients = false)
    }
}
