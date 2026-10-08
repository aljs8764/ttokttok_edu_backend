package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.ConversationRow
import com.ttokttok.application.port.out.MessageRow
import com.ttokttok.application.port.out.MessageSide
import com.ttokttok.application.port.out.MessagingPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 대화·메시지 (NTC-007·008). 목록·읽음 수가 단순해서 JPA 엔티티 없이 JDBC 로 둔다 */
@Component
class MessagingJdbcAdapter(jdbc: JdbcTemplate) : MessagingPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val NO_NOTICE = "00000000-0000-0000-0000-000000000000"

        const val CONV_SELECT = """
            select c.id, c.institution_id, i.name as institution_name, c.student_id, s.name as student_name,
                   c.guardian_user_id, u.name as guardian_name, c.notice_id, n.title as notice_title,
                   c.last_message_at, c.last_message_preview, c.parent_unread, c.staff_unread
            from conversation c
            join institution i on i.id = c.institution_id
            join student s on s.id = c.student_id
            join app_user u on u.id = c.guardian_user_id
            left join notice n on n.id = c.notice_id
        """

        /** 교사 담당 반의 현재 원생 */
        const val TEACHES = """
            exists (select 1 from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
                    where e.student_id = c.student_id and e.to_date is null and ct.user_id = :teacher)
        """

        val CONV_MAPPER = RowMapper<ConversationRow> { rs, _ ->
            ConversationRow(
                id = rs.uuid("id"), institutionId = rs.uuid("institution_id"), institutionName = rs.getString("institution_name"),
                studentId = rs.uuid("student_id"), studentName = rs.getString("student_name"),
                guardianUserId = rs.uuid("guardian_user_id"), guardianName = rs.getString("guardian_name"),
                noticeId = rs.getObject("notice_id", UUID::class.java), noticeTitle = rs.getString("notice_title"),
                lastMessageAt = rs.getTimestamp("last_message_at").toInstant(), lastMessagePreview = rs.getString("last_message_preview"),
                parentUnread = rs.getInt("parent_unread"), staffUnread = rs.getInt("staff_unread"),
            )
        }

        val MESSAGE_MAPPER = RowMapper<MessageRow> { rs, _ ->
            MessageRow(
                id = rs.uuid("id"), conversationId = rs.uuid("conversation_id"), senderId = rs.uuid("sender_id"),
                senderName = rs.getString("sender_name"), side = MessageSide.valueOf(rs.getString("sender_side")),
                body = rs.getString("body"), createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

        fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
    }

    private fun ts(i: Instant) = Timestamp.from(i)

    override fun findOrCreate(institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, noticeId: UUID?, now: Instant): ConversationRow {
        val p = MapSqlParameterSource()
            .addValue("id", com.ttokttok.domain.common.Uuid7.next())
            .addValue("inst", institutionId.value).addValue("student", studentId)
            .addValue("guardian", guardianUserId.value).addValue("notice", noticeId).addValue("now", ts(now))
        named.update(
            """
            insert into conversation (id, institution_id, student_id, guardian_user_id, notice_id, last_message_at)
            values (:id, :inst, :student, :guardian, :notice, :now)
            on conflict (student_id, guardian_user_id, (coalesce(notice_id, '$NO_NOTICE'::uuid))) do nothing
            """.trimIndent(),
            p,
        )
        return named.query(
            "$CONV_SELECT where c.student_id = :student and c.guardian_user_id = :guardian and coalesce(c.notice_id, '$NO_NOTICE'::uuid) = coalesce(:notice, '$NO_NOTICE'::uuid)",
            p, CONV_MAPPER,
        ).first()
    }

    override fun find(id: UUID): ConversationRow? =
        named.query("$CONV_SELECT where c.id = :id", MapSqlParameterSource("id", id), CONV_MAPPER).firstOrNull()

    override fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<ConversationRow> {
        val p = MapSqlParameterSource("guardian", guardianUserId.value).addValue("limit", limit)
        val where = StringBuilder("where c.guardian_user_id = :guardian and c.last_message_preview <> ''")
        if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            where.append(" and c.student_id in (:students)")
            p.addValue("students", studentIds)
        }
        if (before != null) {
            where.append(" and c.last_message_at < :before")
            p.addValue("before", ts(before))
        }
        return named.query("$CONV_SELECT $where order by c.last_message_at desc limit :limit", p, CONV_MAPPER)
    }

    override fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, unreadOnly: Boolean, noticeId: UUID?, studentId: UUID?, limit: Int, offset: Int): List<ConversationRow> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("limit", limit).addValue("offset", offset)
        val where = StringBuilder("where c.institution_id = :inst and c.last_message_preview <> ''")
        if (teacherId != null) {
            where.append(" and $TEACHES")
            p.addValue("teacher", teacherId.value)
        }
        if (unreadOnly) where.append(" and c.staff_unread > 0")
        if (noticeId != null) {
            where.append(" and c.notice_id = :notice")
            p.addValue("notice", noticeId)
        }
        if (studentId != null) {
            where.append(" and c.student_id = :student")
            p.addValue("student", studentId)
        }
        return named.query("$CONV_SELECT $where order by c.last_message_at desc limit :limit offset :offset", p, CONV_MAPPER)
    }

    override fun staffUnreadTotal(institutionId: InstitutionId, teacherId: UserId?): Int {
        val p = MapSqlParameterSource("inst", institutionId.value)
        val teacherClause = if (teacherId != null) {
            p.addValue("teacher", teacherId.value)
            " and $TEACHES"
        } else ""
        return named.queryForObject(
            "select coalesce(sum(c.staff_unread), 0) from conversation c where c.institution_id = :inst$teacherClause", p, Int::class.java,
        ) ?: 0
    }

    override fun parentUnreadTotal(guardianUserId: UserId): Int =
        named.queryForObject(
            "select coalesce(sum(parent_unread), 0) from conversation where guardian_user_id = :g",
            MapSqlParameterSource("g", guardianUserId.value), Int::class.java,
        ) ?: 0

    override fun teaches(conversationId: UUID, teacherId: UserId): Boolean =
        named.queryForObject(
            "select $TEACHES from conversation c where c.id = :id",
            MapSqlParameterSource("id", conversationId).addValue("teacher", teacherId.value), Boolean::class.java,
        ) ?: false

    override fun messages(conversationId: UUID, before: Instant?, limit: Int): List<MessageRow> {
        val p = MapSqlParameterSource("conv", conversationId).addValue("limit", limit)
        val beforeClause = if (before != null) {
            p.addValue("before", ts(before))
            " and m.created_at < :before"
        } else ""
        return named.query(
            """
            select m.id, m.conversation_id, m.sender_id, u.name as sender_name, m.sender_side, m.body, m.created_at
            from message m join app_user u on u.id = m.sender_id
            where m.conversation_id = :conv$beforeClause
            order by m.created_at desc limit :limit
            """.trimIndent(),
            p, MESSAGE_MAPPER,
        )
    }

    override fun addMessage(conversationId: UUID, senderId: UserId, side: MessageSide, body: String, now: Instant): MessageRow {
        val id = com.ttokttok.domain.common.Uuid7.next()
        val p = MapSqlParameterSource("id", id).addValue("conv", conversationId).addValue("sender", senderId.value)
            .addValue("side", side.name).addValue("body", body).addValue("now", ts(now))
            .addValue("preview", body.replace(Regex("\\s+"), " ").take(100))
        named.update(
            "insert into message (id, conversation_id, sender_id, sender_side, body, created_at) values (:id, :conv, :sender, :side, :body, :now)",
            p,
        )
        val parentInc = if (side == MessageSide.STAFF) 1 else 0
        val staffInc = if (side == MessageSide.PARENT) 1 else 0
        // 보낸 쪽은 방금 대화를 본 것이므로 그쪽 안읽은 수는 0
        val updated = named.update(
            """
            update conversation set last_message_at = :now, last_message_preview = :preview,
                   parent_unread = case when :side = 'PARENT' then 0 else parent_unread + $parentInc end,
                   staff_unread = case when :side = 'STAFF' then 0 else staff_unread + $staffInc end
            where id = :conv
            """.trimIndent(),
            p,
        )
        if (updated == 0) throw NotFoundException("대화")
        return named.query(
            """
            select m.id, m.conversation_id, m.sender_id, u.name as sender_name, m.sender_side, m.body, m.created_at
            from message m join app_user u on u.id = m.sender_id where m.id = :id
            """.trimIndent(),
            p, MESSAGE_MAPPER,
        ).first()
    }

    override fun markRead(conversationId: UUID, side: MessageSide) {
        val column = if (side == MessageSide.PARENT) "parent_unread" else "staff_unread"
        named.update("update conversation set $column = 0 where id = :id and $column <> 0", MapSqlParameterSource("id", conversationId))
    }

    override fun isNoticeRecipient(noticeId: UUID, studentId: UUID, guardianUserId: UserId): Boolean =
        named.queryForObject(
            "select exists (select 1 from notice_recipient where notice_id = :n and student_id = :s and guardian_user_id = :g)",
            MapSqlParameterSource("n", noticeId).addValue("s", studentId).addValue("g", guardianUserId.value), Boolean::class.java,
        ) ?: false

    override fun staffRecipients(institutionId: InstitutionId, studentId: UUID, noticeId: UUID?): List<UserId> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("student", studentId)
        if (noticeId != null) {
            val author = named.query("select author_id from notice where id = :n", MapSqlParameterSource("n", noticeId)) { rs, _ -> rs.uuid("author_id") }.firstOrNull()
            if (author != null) return listOf(UserId(author))
        }
        val teachers = named.query(
            """
            select distinct ct.user_id from enrollment e join classroom_teacher ct on ct.classroom_id = e.classroom_id
            where e.student_id = :student and e.to_date is null
            """.trimIndent(),
            p,
        ) { rs, _ -> UserId(rs.uuid("user_id")) }
        if (teachers.isNotEmpty()) return teachers
        return named.query(
            "select user_id from membership where institution_id = :inst and role in ('OWNER', 'ADMIN')",
            p,
        ) { rs, _ -> UserId(rs.uuid("user_id")) }
    }

    override fun noticeInstitution(noticeId: UUID): UUID? =
        named.query("select institution_id from notice where id = :n", MapSqlParameterSource("n", noticeId)) { rs, _ -> rs.uuid("institution_id") }.firstOrNull()
}
