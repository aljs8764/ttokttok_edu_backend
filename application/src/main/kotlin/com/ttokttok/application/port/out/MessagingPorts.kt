package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

enum class MessageSide { PARENT, STAFF }

data class ConversationRow(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val guardianUserId: UUID,
    val guardianName: String,
    val noticeId: UUID?,
    val noticeTitle: String?,
    val lastMessageAt: Instant,
    val lastMessagePreview: String,
    val parentUnread: Int,
    val staffUnread: Int,
)

data class MessageRow(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val senderName: String,
    val side: MessageSide,
    val body: String,
    val createdAt: Instant,
)

/** 대화·메시지 저장소 (NTC-007·008). JPA 로 구현 */
interface MessagingPort {
    /** (아이, 보호자, 알림장) 대화를 찾고 없으면 만든다. */
    fun findOrCreate(institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, noticeId: UUID?, now: Instant): ConversationRow
    fun find(id: UUID): ConversationRow?
    /** 보호자 목록 — studentIds 가 null 이면 내 모든 대화. 최근 대화 순 */
    fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, before: Instant?, limit: Int): List<ConversationRow>
    /** 교직원 목록. teacherId 가 null 이면 기관 전체(원장·실장), 있으면 그 교사 담당 반의 현재 원생만 */
    fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, unreadOnly: Boolean, noticeId: UUID?, studentId: UUID?, limit: Int, offset: Int): List<ConversationRow>
    fun staffUnreadTotal(institutionId: InstitutionId, teacherId: UserId?): Int
    fun parentUnreadTotal(guardianUserId: UserId): Int
    /** 교사가 이 대화의 원생을 담당하는지 (현재 소속 반의 담당 교사) */
    fun teaches(conversationId: UUID, teacherId: UserId): Boolean
    /** 최신순. before 이전 */
    fun messages(conversationId: UUID, before: Instant?, limit: Int): List<MessageRow>
    /** 메시지 저장 + 대화의 마지막 메시지·상대 쪽 안읽은 수 증가 */
    fun addMessage(conversationId: UUID, senderId: UserId, side: MessageSide, body: String, now: Instant): MessageRow
    /** side 쪽이 읽음 처리 → 그쪽 안읽은 수 0 */
    fun markRead(conversationId: UUID, side: MessageSide)
    fun isNoticeRecipient(noticeId: UUID, studentId: UUID, guardianUserId: UserId): Boolean
    /** 보호자 메시지를 받을 교직원: 알림장 회신이면 작성자, 아니면 원생 담당 교사 → 없으면 원장·실장 */
    fun staffRecipients(institutionId: InstitutionId, studentId: UUID, noticeId: UUID?): List<UserId>
    fun noticeInstitution(noticeId: UUID): UUID?
}
