package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.ChannelFlags
import com.ttokttok.application.port.out.NotificationCategory
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

/** SET-004 채널별 알림 옵션 — 원장·실장 */
interface NotificationSettingUseCase {
    /** 모든 카테고리(없으면 켜짐) */
    fun get(actor: UserId, institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags>
    fun update(actor: UserId, institutionId: InstitutionId, changes: Map<NotificationCategory, ChannelFlags>): Map<NotificationCategory, ChannelFlags>
}

data class LocationConsentView(val childId: UUID, val granted: Boolean, val updatedAt: Instant?)

/** PAR-008 위치 정보 제공 동의 — 학부모 */
interface LocationConsentUseCase {
    fun get(parent: UserId, childId: UUID): LocationConsentView
    fun set(parent: UserId, childId: UUID, granted: Boolean): LocationConsentView
}
