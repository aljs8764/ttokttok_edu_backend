package com.ttokttok.domain.messaging

import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant

/**
 * 교사(교직원) 앱 푸시 요청 — 예약 알림장 발송 완료, 행사 자동 독촉 결과 등 작성자에게 알리는 용도.
 * Outbox → 워커 → FCM (AppFlavor.TEACHER 기기).
 */
data class TeacherPushRequested(
    override val institutionId: InstitutionId,
    /** notification_log 템플릿 코드 (예: NOTICE_SCHEDULED_SENT) */
    val template: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val recipientUserIds: List<UserId>,
    override val occurredAt: Instant,
) : DomainEvent
