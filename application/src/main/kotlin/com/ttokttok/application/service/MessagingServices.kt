package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ConversationView
import com.ttokttok.application.port.`in`.MessageView
import com.ttokttok.application.port.`in`.ParentMessagingUseCase
import com.ttokttok.application.port.`in`.StaffMessagingUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.ConversationRow
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.MessageRow
import com.ttokttok.application.port.out.MessageSide
import com.ttokttok.application.port.out.MessagingPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.messaging.TeacherPushRequested
import com.ttokttok.domain.student.GuardianLinkStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private const val MAX_BODY = 2000

private fun normalizeBody(raw: String): String {
    val body = raw.trim()
    if (body.isEmpty()) throw InvalidInputException("EMPTY_MESSAGE", "메시지를 입력해 주세요")
    if (body.length > MAX_BODY) throw InvalidInputException("MESSAGE_TOO_LONG", "메시지는 ${MAX_BODY}자까지 보낼 수 있습니다")
    return body
}

internal fun ConversationRow.toView(side: MessageSide) = ConversationView(
    id, institutionId, institutionName, studentId, studentName, guardianUserId, guardianName,
    noticeId, noticeTitle, lastMessageAt, lastMessagePreview, if (side == MessageSide.PARENT) parentUnread else staffUnread,
)

internal fun MessageRow.toView() = MessageView(id, senderId, senderName, side, body, createdAt)

/** 메시지가 저장된 뒤의 알림: 푸시(Outbox) + 실시간(개인 큐). 푸시 본문에는 앞부분만 싣는다 */
@Component
class MessageNotifier(
    private val messaging: MessagingPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
) {
    fun parentToStaff(conv: ConversationRow, message: MessageRow, now: Instant) {
        val institutionId = InstitutionId(conv.institutionId)
        val recipients = messaging.staffRecipients(institutionId, conv.studentId, conv.noticeId)
        val payload = mapOf("type" to "chat.message", "conversationId" to conv.id.toString(), "noticeId" to conv.noticeId?.toString())
        if (recipients.isNotEmpty()) {
            outbox.publish(
                TeacherPushRequested(
                    institutionId, "CHAT_MESSAGE",
                    if (conv.noticeId != null) "[${conv.studentName}] 알림장 회신" else "[${conv.studentName}] 학부모 메시지",
                    message.body.take(80),
                    mapOf("type" to "chat", "conversationId" to conv.id.toString()),
                    recipients, now,
                ),
            )
            realtime.userEvents(recipients, payload)
        }
        realtime.institutionEvent(institutionId, payload)
    }

    fun staffToParent(conv: ConversationRow, message: MessageRow, now: Instant) {
        val guardian = UserId(conv.guardianUserId)
        outbox.publish(
            ParentPushRequested(
                InstitutionId(conv.institutionId), "CHAT_MESSAGE", "[${conv.institutionName}] 선생님 메시지",
                message.body.take(80),
                mapOf("type" to "chat", "conversationId" to conv.id.toString(), "studentId" to conv.studentId.toString()),
                listOf(guardian), now,
            ),
        )
        realtime.userEvents(listOf(guardian), mapOf("type" to "chat.message", "conversationId" to conv.id.toString()))
    }
}

@Service
class ParentMessagingService(
    private val messaging: MessagingPort,
    private val guardians: GuardianPort,
    private val notifier: MessageNotifier,
    private val clock: ClockPort,
) : ParentMessagingUseCase {

    @Transactional(readOnly = true)
    override fun conversations(parent: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<ConversationView> {
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        return messaging.listForGuardian(parent, studentIds, before, limit).map { it.toView(MessageSide.PARENT) }
    }

    @Transactional
    override fun open(parent: UserId, studentId: UUID, noticeId: UUID?): ConversationView {
        val link = guardians.findLinkedByUser(parent).firstOrNull { it.studentId.value == studentId && it.linkStatus == GuardianLinkStatus.LINKED }
            ?: throw ForbiddenException("본인 자녀가 아닙니다")
        if (noticeId != null) {
            if (!messaging.isNoticeRecipient(noticeId, studentId, parent)) throw ForbiddenException("받은 알림장이 아닙니다")
        }
        return messaging.findOrCreate(link.institutionId, studentId, parent, noticeId, clock.now()).toView(MessageSide.PARENT)
    }

    @Transactional(readOnly = true)
    override fun messages(parent: UserId, conversationId: UUID, before: Instant?, limit: Int): List<MessageView> {
        own(parent, conversationId)
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        return messaging.messages(conversationId, before, limit).map { it.toView() }
    }

    @Transactional
    override fun send(parent: UserId, conversationId: UUID, body: String): MessageView {
        val conv = own(parent, conversationId)
        val now = clock.now()
        val message = messaging.addMessage(conversationId, parent, MessageSide.PARENT, normalizeBody(body), now)
        notifier.parentToStaff(conv, message, now)
        return message.toView()
    }

    @Transactional
    override fun markRead(parent: UserId, conversationId: UUID) {
        own(parent, conversationId)
        messaging.markRead(conversationId, MessageSide.PARENT)
    }

    @Transactional(readOnly = true)
    override fun unreadTotal(parent: UserId) = messaging.parentUnreadTotal(parent)

    private fun own(parent: UserId, id: UUID): ConversationRow {
        val conv = messaging.find(id) ?: throw NotFoundException("대화")
        if (conv.guardianUserId != parent.value) throw ForbiddenException("내 대화가 아닙니다")
        return conv
    }
}

@Service
class StaffMessagingService(
    private val messaging: MessagingPort,
    private val guardians: GuardianPort,
    private val guard: AccessGuard,
    private val notifier: MessageNotifier,
    private val clock: ClockPort,
) : StaffMessagingUseCase {

    @Transactional(readOnly = true)
    override fun conversations(actor: UserId, institutionId: InstitutionId, unreadOnly: Boolean, noticeId: UUID?, studentId: UUID?, page: Int, size: Int): List<ConversationView> {
        val m = guard.requireStaff(actor, institutionId)
        if (page < 0 || size !in 1..100) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~100입니다")
        val teacher = if (m.role.isManager) null else actor
        return messaging.listForStaff(institutionId, teacher, unreadOnly, noticeId, studentId, size, page * size).map { it.toView(MessageSide.STAFF) }
    }

    @Transactional
    override fun openForStudent(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<ConversationView> {
        val m = guard.requireStaff(actor, institutionId)
        val linked = guardians.findByStudent(com.ttokttok.domain.common.StudentId(studentId))
            .filter { it.institutionId == institutionId && it.linkStatus == GuardianLinkStatus.LINKED }
            .mapNotNull { it.userId }.distinct()
        if (linked.isEmpty()) throw InvalidInputException("NO_LINKED_GUARDIAN", "앱에 연결된 보호자가 없습니다")
        val now = clock.now()
        val rows = linked.map { messaging.findOrCreate(institutionId, studentId, it, null, now) }
        if (!m.role.isManager && rows.any { !messaging.teaches(it.id, actor) }) throw ForbiddenException("담당 반 원생이 아닙니다")
        return rows.map { it.toView(MessageSide.STAFF) }
    }

    @Transactional(readOnly = true)
    override fun messages(actor: UserId, institutionId: InstitutionId, conversationId: UUID, before: Instant?, limit: Int): List<MessageView> {
        access(actor, institutionId, conversationId)
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        return messaging.messages(conversationId, before, limit).map { it.toView() }
    }

    @Transactional
    override fun send(actor: UserId, institutionId: InstitutionId, conversationId: UUID, body: String): MessageView {
        val conv = access(actor, institutionId, conversationId)
        val now = clock.now()
        val message = messaging.addMessage(conversationId, actor, MessageSide.STAFF, normalizeBody(body), now)
        notifier.staffToParent(conv, message, now)
        return message.toView()
    }

    @Transactional
    override fun markRead(actor: UserId, institutionId: InstitutionId, conversationId: UUID) {
        access(actor, institutionId, conversationId)
        messaging.markRead(conversationId, MessageSide.STAFF)
    }

    @Transactional(readOnly = true)
    override fun unreadTotal(actor: UserId, institutionId: InstitutionId): Int {
        val m = guard.requireStaff(actor, institutionId)
        return messaging.staffUnreadTotal(institutionId, if (m.role.isManager) null else actor)
    }

    private fun access(actor: UserId, institutionId: InstitutionId, id: UUID): ConversationRow {
        val m = guard.requireStaff(actor, institutionId)
        val conv = messaging.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("대화")
        if (!m.role.isManager && !messaging.teaches(id, actor)) throw ForbiddenException("담당 반 원생의 대화가 아닙니다")
        return conv
    }
}
