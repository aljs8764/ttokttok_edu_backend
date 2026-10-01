package com.ttokttok.domain.messaging

import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.PhoneNumber
import java.time.Instant

/** 알림톡 템플릿 (카카오 비즈메시지 사전 심사 대상 — Open Issue #4) */
enum class MessageTemplate(val code: String) {
    /** STU-003 개별 등록 후 앱 설치 안내 */
    APP_INSTALL_GUIDE("TTOK_APP_INSTALL"),
    /** STU-005 정보 입력 링크 */
    JOIN_INVITE("TTOK_JOIN_INVITE"),
    /** STU-004 가입 승인 */
    JOIN_APPROVED("TTOK_JOIN_APPROVED"),
    /** STU-004 가입 거절 */
    JOIN_REJECTED("TTOK_JOIN_REJECTED"),
    /** NTC-001·009 앱 미설치 보호자에게 알림장·공지 도착 안내 (설치 링크 포함) */
    NOTICE_NEW("TTOK_NOTICE_NEW"),
}

/**
 * 보호자 휴대폰으로 알림톡(실패 시 SMS)을 보내 달라는 요청. Outbox → 워커.
 * 번호가 담기므로 Outbox 페이로드는 암호화 저장된다.
 */
data class GuardianMessageRequested(
    override val institutionId: InstitutionId,
    val phone: PhoneNumber,
    val template: MessageTemplate,
    val variables: Map<String, String>,
    override val occurredAt: Instant,
) : DomainEvent
