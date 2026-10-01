package com.ttokttok.domain.messaging

import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant

/**
 * 학부모 앱 푸시 범용 요청 (행사 RSVP 요청·마감 독촉 등). Outbox → 워커 → FCM.
 * 출결·알림장처럼 후처리(도달 기록 등)가 따로 필요한 경우는 전용 이벤트를 쓴다.
 */
data class ParentPushRequested(
    override val institutionId: InstitutionId,
    /** notification_log 템플릿 코드 (예: EVENT_RSVP_REQUEST) */
    val template: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val recipientUserIds: List<UserId>,
    override val occurredAt: Instant,
) : DomainEvent
