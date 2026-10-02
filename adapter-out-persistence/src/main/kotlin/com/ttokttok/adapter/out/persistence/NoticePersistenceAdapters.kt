package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.NoticeEntity
import com.ttokttok.adapter.out.persistence.repository.NoticeJpaRepository
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
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
 * 수신자 스냅샷. 한 번에 수백 행을 넣고 집계·부분 갱신이 많아 JdbcTemplate 으로 직접 다룬다.
 * 호출한 유스케이스 트랜잭션에 함께 묶인다.
 */
@Component
class NoticeRecipientJdbcAdapter(jdbc: JdbcTemplate) : NoticeRecipientPort {
    private val plain = jdbc
    private val named = NamedParameterJdbcTemplate(jdbc)

    private val mapper = RowMapper { rs, _ ->
        NoticeRecipient(
            noticeId = NoticeId(rs.getObject("notice_id", UUID::class.java)),
            studentId = StudentId(rs.getObject("student_id", UUID::class.java)),
            guardianUserId = rs.getObject("guardian_user_id", UUID::class.java)?.let { UserId(it) },
            deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
            readAt = rs.getTimestamp("read_at")?.toInstant(),
            resentCount = rs.getInt("resent_count"),
        )
    }

    override fun saveAll(recipients: List<NoticeRecipient>) {
        if (recipients.isEmpty()) return
        recipients.chunked(500).forEach { chunk ->
            plain.batchUpdate(
                """insert into notice_recipient (notice_id, student_id, guardian_user_id, delivered_at, read_at, resent_count)
                   values (?, ?, ?, ?, ?, ?) on conflict do nothing""",
                chunk.map {
                    arrayOf(
                        it.noticeId.value, it.studentId.value, it.guardianUserId?.value,
                        it.deliveredAt?.let(Timestamp::from), it.readAt?.let(Timestamp::from), it.resentCount,
                    )
                },
            )
        }
    }

    override fun findByNotice(noticeId: NoticeId): List<NoticeRecipient> =
        plain.query("select * from notice_recipient where notice_id = ?", mapper, noticeId.value)

    override fun findByNotices(noticeIds: Collection<NoticeId>): List<NoticeRecipient> =
        noticeIds.map { it.value }.distinct().chunked(1000).flatMap { ids ->
            named.query("select * from notice_recipient where notice_id in (:ids)", MapSqlParameterSource("ids", ids), mapper)
        }

    override fun markRead(noticeId: NoticeId, userId: UserId, at: Instant): Boolean =
        plain.update(
            "update notice_recipient set read_at = ? where notice_id = ? and guardian_user_id = ? and read_at is null",
            Timestamp.from(at), noticeId.value, userId.value,
        ) > 0

    override fun markDelivered(noticeId: NoticeId, userIds: Collection<UserId>, at: Instant) {
        if (userIds.isEmpty()) return
        userIds.map { it.value }.distinct().chunked(1000).forEach { ids ->
            named.update(
                """update notice_recipient set delivered_at = coalesce(delivered_at, :at)
                   where notice_id = :noticeId and guardian_user_id in (:ids)""",
                MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("noticeId", noticeId.value).addValue("ids", ids),
            )
        }
    }

    override fun incrementResent(noticeId: NoticeId, studentIds: Collection<StudentId>) {
        if (studentIds.isEmpty()) return
        studentIds.map { it.value }.distinct().chunked(1000).forEach { ids ->
            named.update(
                "update notice_recipient set resent_count = resent_count + 1 where notice_id = :noticeId and student_id in (:ids)",
                MapSqlParameterSource().addValue("noticeId", noticeId.value).addValue("ids", ids),
            )
        }
    }

    override fun findInboxNoticeIds(userId: UserId, studentIds: Collection<StudentId>?, before: Instant?, limit: Int): List<NoticeId> {
        val params = MapSqlParameterSource().addValue("userId", userId.value).addValue("limit", limit)
        val studentFilter = if (studentIds.isNullOrEmpty()) "" else {
            params.addValue("studentIds", studentIds.map { it.value })
            " and r.student_id in (:studentIds)"
        }
        val beforeFilter = if (before == null) "" else {
            params.addValue("before", Timestamp.from(before))
            " and n.sent_at < :before"
        }
        return named.query(
            """select n.id from notice n
               where n.status = 'SENT'$beforeFilter
                 and exists (select 1 from notice_recipient r
                             where r.notice_id = n.id and r.guardian_user_id = :userId$studentFilter)
               order by n.sent_at desc limit :limit""",
            params,
        ) { rs, _ -> NoticeId(rs.getObject("id", UUID::class.java)) }
    }

    override fun findForUser(noticeIds: Collection<NoticeId>, userId: UserId): List<NoticeRecipient> {
        if (noticeIds.isEmpty()) return emptyList()
        return named.query(
            "select * from notice_recipient where notice_id in (:ids) and guardian_user_id = :userId",
            MapSqlParameterSource().addValue("ids", noticeIds.map { it.value }).addValue("userId", userId.value),
            mapper,
        )
    }
}
