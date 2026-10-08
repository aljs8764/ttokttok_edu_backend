package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.MessageSide
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

data class ConversationView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val guardianUserId: UUID,
    val guardianName: String,
    /** 알림장 회신이면 그 알림장, 일반 채팅이면 null */
    val noticeId: UUID?,
    val noticeTitle: String?,
    val lastMessageAt: Instant,
    val lastMessagePreview: String,
    /** 보고 있는 쪽 기준 안 읽은 메시지 수 */
    val unread: Int,
)

data class MessageView(
    val id: UUID,
    val senderId: UUID,
    val senderName: String,
    val side: MessageSide,
    val body: String,
    val createdAt: Instant,
)

/** NTC-007 알림장 회신 · NTC-008 1:1 채팅 — 학부모 쪽 */
interface ParentMessagingUseCase {
    /** childId 필터는 GetMyChildrenQuery.resolveFilter 결과(원생 id 집합), null = 전체 */
    fun conversations(parent: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<ConversationView>
    /** 대화 열기(없으면 생성). noticeId 가 있으면 그 알림장 수신자만 */
    fun open(parent: UserId, studentId: UUID, noticeId: UUID?): ConversationView
    fun messages(parent: UserId, conversationId: UUID, before: Instant?, limit: Int): List<MessageView>
    fun send(parent: UserId, conversationId: UUID, body: String): MessageView
    fun markRead(parent: UserId, conversationId: UUID)
    fun unreadTotal(parent: UserId): Int
}

/** NTC-007 회신 확인 · NTC-008 1:1 채팅 — 교직원 쪽(원장·실장 전체, 교사는 담당 반 원생만) */
interface StaffMessagingUseCase {
    fun conversations(actor: UserId, institutionId: InstitutionId, unreadOnly: Boolean, noticeId: UUID?, studentId: UUID?, page: Int, size: Int): List<ConversationView>
    /** 교직원이 먼저 말을 거는 경우 — 원생의 연결된 보호자 계정마다 대화를 만든다 */
    fun openForStudent(actor: UserId, institutionId: InstitutionId, studentId: UUID): List<ConversationView>
    fun messages(actor: UserId, institutionId: InstitutionId, conversationId: UUID, before: Instant?, limit: Int): List<MessageView>
    fun send(actor: UserId, institutionId: InstitutionId, conversationId: UUID, body: String): MessageView
    fun markRead(actor: UserId, institutionId: InstitutionId, conversationId: UUID)
    fun unreadTotal(actor: UserId, institutionId: InstitutionId): Int
}
