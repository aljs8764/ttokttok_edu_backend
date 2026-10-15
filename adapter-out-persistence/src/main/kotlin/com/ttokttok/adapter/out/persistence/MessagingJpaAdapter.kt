package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ConversationEntity
import com.ttokttok.adapter.out.persistence.entity.MessageEntity
import com.ttokttok.adapter.out.persistence.repository.ConversationJpaRepository
import com.ttokttok.adapter.out.persistence.repository.EnrollmentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MessageJpaRepository
import com.ttokttok.adapter.out.persistence.repository.NoticeJpaRepository
import com.ttokttok.adapter.out.persistence.repository.NoticeRecipientJpaRepository
import com.ttokttok.application.port.out.ConversationRow
import com.ttokttok.application.port.out.MessageRow
import com.ttokttok.application.port.out.MessageSide
import com.ttokttok.application.port.out.MessagingPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 대화·메시지 (NTC-007·008) */
@Component
class MessagingJpaAdapter(
    private val conversations: ConversationJpaRepository,
    private val messages: MessageJpaRepository,
    private val notices: NoticeJpaRepository,
    private val noticeRecipients: NoticeRecipientJpaRepository,
    private val enrollments: EnrollmentJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
) : MessagingPort {

    private companion object {
        const val NO_NOTICE = "00000000-0000-0000-0000-000000000000"

        const val CONV_SELECT = """
            select c, i.name, s.name, u.name, n.title
            from ConversationEntity c
            join InstitutionEntity i on i.id = c.institutionId
            join StudentEntity s on s.id = c.studentId
            join UserEntity u on u.id = c.guardianUserId
            left join NoticeEntity n on n.id = c.noticeId
        """

        /** 교사 담당 반의 현재 원생 */
        const val TEACHES = """
            exists (select 1 from EnrollmentEntity e, ClassroomTeacherEntity ct
                     where ct.classroomId = e.classroomId and e.studentId = c.studentId and e.toDate is null and ct.userId = :teacher)
        """

        fun conv(r: Array<Any?>): ConversationRow {
            val c = r[0] as ConversationEntity
            return ConversationRow(
                id = c.id, institutionId = c.institutionId, institutionName = r[1] as String,
                studentId = c.studentId, studentName = r[2] as String,
                guardianUserId = c.guardianUserId, guardianName = r[3] as String,
                noticeId = c.noticeId, noticeTitle = r[4] as String?,
                lastMessageAt = c.lastMessageAt, lastMessagePreview = c.lastMessagePreview,
                parentUnread = c.parentUnread, staffUnread = c.staffUnread,
            )
        }

        fun message(r: Array<Any?>): MessageRow {
            val m = r[0] as MessageEntity
            return MessageRow(
                id = m.id, conversationId = m.conversationId, senderId = m.senderId,
                senderName = r[1] as String, side = MessageSide.valueOf(m.senderSide),
                body = m.body, createdAt = m.createdAt,
            )
        }
    }

    override fun findOrCreate(institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, noticeId: UUID?, now: Instant): ConversationRow {
        // 유니크 인덱스가 coalesce(notice_id) 식이라 on conflict 는 네이티브로
        q.update(
            """
            insert into conversation (id, institution_id, student_id, guardian_user_id, notice_id, last_message_at)
            values (:id, :inst, :student, :guardian, :notice, :now)
            on conflict (student_id, guardian_user_id, (coalesce(notice_id, '$NO_NOTICE'::uuid))) do nothing
            """.trimIndent(),
            params(
                "id" to Uuid7.next(), "inst" to institutionId.value, "student" to studentId,
                "guardian" to guardianUserId.value, "notice" to typed(noticeId), "now" to now,
            ),
        )
        val p = params("student" to studentId, "guardian" to guardianUserId.value)
        val noticeClause = if (noticeId != null) {
            p["notice"] = noticeId
            "c.noticeId = :notice"
        } else "c.noticeId is null"
        return q.jpqlRows("$CONV_SELECT where c.studentId = :student and c.guardianUserId = :guardian and $noticeClause", p).first().let(::conv)
    }

    override fun find(id: UUID): ConversationRow? = q.jpqlRows("$CONV_SELECT where c.id = :id", params("id" to id)).firstOrNull()?.let(::conv)

    override fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<ConversationRow> {
        val p = params("guardian" to guardianUserId.value)
        val where = StringBuilder(" where c.guardianUserId = :guardian and c.lastMessagePreview <> ''")
        if (studentIds != null) {
            if (studentIds.isEmpty()) return emptyList()
            where.append(" and c.studentId in (:students)")
            p["students"] = studentIds
        }
        if (before != null) {
            where.append(" and c.lastMessageAt < :before")
            p["before"] = before
        }
        return q.jpqlRows("$CONV_SELECT$where order by c.lastMessageAt desc", p, limit = limit).map(::conv)
    }

    override fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, unreadOnly: Boolean, noticeId: UUID?, studentId: UUID?, limit: Int, offset: Int): List<ConversationRow> {
        val p = params("inst" to institutionId.value)
        val where = StringBuilder(" where c.institutionId = :inst and c.lastMessagePreview <> ''")
        if (teacherId != null) {
            where.append(" and $TEACHES")
            p["teacher"] = teacherId.value
        }
        if (unreadOnly) where.append(" and c.staffUnread > 0")
        if (noticeId != null) {
            where.append(" and c.noticeId = :notice")
            p["notice"] = noticeId
        }
        if (studentId != null) {
            where.append(" and c.studentId = :student")
            p["student"] = studentId
        }
        return q.jpqlRows("$CONV_SELECT$where order by c.lastMessageAt desc", p, limit = limit, offset = offset).map(::conv)
    }

    override fun staffUnreadTotal(institutionId: InstitutionId, teacherId: UserId?): Int {
        val p = params("inst" to institutionId.value)
        val teacherClause = if (teacherId != null) {
            p["teacher"] = teacherId.value
            " and $TEACHES"
        } else ""
        return q.jpqlCount("select coalesce(sum(c.staffUnread), 0) from ConversationEntity c where c.institutionId = :inst$teacherClause", p).toInt()
    }

    override fun parentUnreadTotal(guardianUserId: UserId): Int = conversations.parentUnreadTotal(guardianUserId.value).toInt()

    override fun teaches(conversationId: UUID, teacherId: UserId): Boolean = conversations.countTaughtBy(conversationId, teacherId.value) > 0

    override fun messages(conversationId: UUID, before: Instant?, limit: Int): List<MessageRow> {
        val p = params("conv" to conversationId)
        val beforeClause = if (before != null) {
            p["before"] = before
            " and m.createdAt < :before"
        } else ""
        return q.jpqlRows(
            "select m, u.name from MessageEntity m join UserEntity u on u.id = m.senderId where m.conversationId = :conv$beforeClause order by m.createdAt desc",
            p, limit = limit,
        ).map(::message)
    }

    override fun addMessage(conversationId: UUID, senderId: UserId, side: MessageSide, body: String, now: Instant): MessageRow {
        val id = Uuid7.next()
        val preview = body.replace(Regex("\\s+"), " ").take(100)
        messages.save(MessageEntity(id, conversationId, senderId.value, side.name, body, now))
        // 보낸 쪽은 방금 대화를 본 것이므로 그쪽 안읽은 수는 0
        val updated = if (side == MessageSide.PARENT) conversations.onParentMessage(conversationId, preview, now)
        else conversations.onStaffMessage(conversationId, preview, now)
        if (updated == 0) throw NotFoundException("대화")
        return messages.findView(id).first().let(::message)
    }

    override fun markRead(conversationId: UUID, side: MessageSide) {
        if (side == MessageSide.PARENT) conversations.markReadByParent(conversationId) else conversations.markReadByStaff(conversationId)
    }

    override fun isNoticeRecipient(noticeId: UUID, studentId: UUID, guardianUserId: UserId): Boolean =
        noticeRecipients.existsByNoticeIdAndStudentIdAndGuardianUserId(noticeId, studentId, guardianUserId.value)

    override fun staffRecipients(institutionId: InstitutionId, studentId: UUID, noticeId: UUID?): List<UserId> {
        if (noticeId != null) {
            val author = notices.findByIdOrNull(noticeId)?.authorId
            if (author != null) return listOf(UserId(author))
        }
        val teachers = enrollments.findCurrentTeacherIds(studentId).map(::UserId)
        if (teachers.isNotEmpty()) return teachers
        return memberships.findByInstitutionIdAndRoleIn(institutionId.value, listOf("OWNER", "ADMIN")).map { UserId(it.userId) }
    }

    override fun noticeInstitution(noticeId: UUID): UUID? = notices.findByIdOrNull(noticeId)?.institutionId
}
