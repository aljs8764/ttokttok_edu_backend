package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.NoticeEntity
import com.ttokttok.adapter.out.persistence.entity.NoticeRecipientEntity
import com.ttokttok.adapter.out.persistence.repository.NoticeJpaRepository
import com.ttokttok.adapter.out.persistence.repository.NoticeRecipientJpaRepository
import com.ttokttok.application.port.out.NoticePort
import com.ttokttok.application.port.out.NoticeRecipientPort
import com.ttokttok.application.port.out.NoticeSearchCriteria
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.notice.Notice
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticeRecipient
import com.ttokttok.domain.notice.NoticeStatus
import com.ttokttok.domain.notice.NoticeTarget
import com.ttokttok.domain.notice.TargetScope
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class NoticePersistenceAdapter(private val repo: NoticeJpaRepository) : NoticePort {
    private val json = jacksonObjectMapper()

    private data class TargetJson(val scope: String, val id: UUID?)

    override fun save(notice: Notice): Notice {
        repo.save(
            NoticeEntity(
                id = notice.id.value, institutionId = notice.institutionId.value, authorId = notice.authorId.value,
                kind = notice.kind.name, title = notice.title, body = notice.body, pinned = notice.pinned,
                targets = json.writeValueAsString(notice.targets.map { TargetJson(it.scope.name, it.classroomId?.value ?: it.studentId?.value) }),
                status = notice.status.name, scheduledAt = notice.scheduledAt, sentAt = notice.sentAt,
                lastResentAt = notice.lastResentAt, createdAt = notice.createdAt, updatedAt = Instant.now(),
                attachments = json.writeValueAsString(notice.attachments.map { it.value }),
            ),
        )
        return notice
    }

    override fun find(id: NoticeId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()
    override fun findForUpdate(id: NoticeId, institutionId: InstitutionId) = repo.findForUpdate(id.value, institutionId.value)?.toDomain()
    override fun findAllByIds(ids: Collection<NoticeId>) = if (ids.isEmpty()) emptyList() else repo.findAllById(ids.map { it.value }).map { it.toDomain() }
    override fun lockDue(now: Instant, limit: Int) = repo.lockDue(now, limit).map { it.toDomain() }

    override fun search(criteria: NoticeSearchCriteria): PageResult<Notice> {
        val page = repo.search(
            criteria.institutionId.value, criteria.authorId?.value, criteria.kind?.name, criteria.status?.name,
            PageRequest.of(criteria.page, criteria.size),
        )
        return PageResult(page.content.map { it.toDomain() }, criteria.page, criteria.size, page.totalElements)
    }

    override fun findSentBetween(institutionId: InstitutionId, from: Instant, to: Instant, authorId: UserId?) =
        repo.findSentBetween(institutionId.value, from, to, authorId?.value).map { it.toDomain() }

    private fun NoticeEntity.toDomain() = Notice(
        id = NoticeId(id), institutionId = InstitutionId(institutionId), authorId = UserId(authorId),
        kind = NoticeKind.valueOf(kind), title = title, body = body, pinned = pinned,
        targets = json.readValue<List<TargetJson>>(targets).map { t ->
            when (TargetScope.valueOf(t.scope)) {
                TargetScope.ALL -> NoticeTarget.all()
                TargetScope.CLASS -> NoticeTarget.classroom(ClassroomId(t.id!!))
                TargetScope.STUDENT -> NoticeTarget.student(StudentId(t.id!!))
            }
        },
        status = NoticeStatus.valueOf(status), scheduledAt = scheduledAt, sentAt = sentAt,
        lastResentAt = lastResentAt, createdAt = createdAt,
        attachments = json.readValue<List<UUID>>(attachments).map(::FileId),
    )
}

/**
 * 수신자 스냅샷. 한 번에 수백 행을 넣으므로 쓰기는 여러 행을 한 문장으로(on conflict do nothing) 넣고,
 * 부분 갱신은 JPQL 일괄 update 로 한다. 호출한 유스케이스 트랜잭션에 함께 묶인다.
 */
@Component
class NoticeRecipientJpaAdapter(
    private val repo: NoticeRecipientJpaRepository,
    private val q: JpaQueries,
) : NoticeRecipientPort {

    private fun NoticeRecipientEntity.toDomain() = NoticeRecipient(
        noticeId = NoticeId(noticeId), studentId = StudentId(studentId), guardianUserId = guardianUserId?.let { UserId(it) },
        deliveredAt = deliveredAt, readAt = readAt, resentCount = resentCount,
    )

    override fun saveAll(recipients: List<NoticeRecipient>) {
        // 같은 (알림장, 학생, 보호자) 는 유니크 인덱스(ux_notice_recipient)로 한 번만
        q.insertValues(
            "insert into notice_recipient (notice_id, student_id, guardian_user_id, delivered_at, read_at, resent_count)",
            recipients.map {
                listOf(it.noticeId.value, it.studentId.value, typed(it.guardianUserId?.value), typed(it.deliveredAt), typed(it.readAt), it.resentCount)
            },
            "on conflict do nothing",
        )
    }

    override fun findByNotice(noticeId: NoticeId): List<NoticeRecipient> = repo.findByNoticeId(noticeId.value).map { it.toDomain() }

    override fun findByNotices(noticeIds: Collection<NoticeId>): List<NoticeRecipient> =
        noticeIds.map { it.value }.distinct().chunked(1000).flatMap { repo.findByNoticeIdIn(it) }.map { it.toDomain() }

    override fun markRead(noticeId: NoticeId, userId: UserId, at: Instant): Boolean = repo.markRead(noticeId.value, userId.value, at) > 0

    override fun markDelivered(noticeId: NoticeId, userIds: Collection<UserId>, at: Instant) {
        if (userIds.isEmpty()) return
        userIds.map { it.value }.distinct().chunked(1000).forEach { repo.markDelivered(noticeId.value, it, at) }
    }

    override fun incrementResent(noticeId: NoticeId, studentIds: Collection<StudentId>) {
        if (studentIds.isEmpty()) return
        studentIds.map { it.value }.distinct().chunked(1000).forEach { repo.incrementResent(noticeId.value, it) }
    }

    override fun findInboxNoticeIds(userId: UserId, studentIds: Collection<StudentId>?, before: Instant?, limit: Int): List<NoticeId> {
        val p = params("userId" to userId.value)
        val studentFilter = if (studentIds.isNullOrEmpty()) "" else {
            p["studentIds"] = studentIds.map { it.value }
            " and r.studentId in (:studentIds)"
        }
        val beforeFilter = if (before == null) "" else {
            p["before"] = before
            " and n.sentAt < :before"
        }
        return q.jpql(
            """select n.id from NoticeEntity n
               where n.status = 'SENT'$beforeFilter
                 and exists (select 1 from NoticeRecipientEntity r
                             where r.noticeId = n.id and r.guardianUserId = :userId$studentFilter)
               order by n.sentAt desc""",
            UUID::class.java, p, limit = limit,
        ).map(::NoticeId)
    }

    override fun findForUser(noticeIds: Collection<NoticeId>, userId: UserId): List<NoticeRecipient> {
        if (noticeIds.isEmpty()) return emptyList()
        return repo.findByNoticeIdInAndGuardianUserId(noticeIds.map { it.value }, userId.value).map { it.toDomain() }
    }
}
