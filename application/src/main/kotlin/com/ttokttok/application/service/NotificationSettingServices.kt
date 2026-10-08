package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.LocationConsentUseCase
import com.ttokttok.application.port.`in`.LocationConsentView
import com.ttokttok.application.port.`in`.NotificationSettingUseCase
import com.ttokttok.application.port.out.ChannelFlags
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.LocationConsentPort
import com.ttokttok.application.port.out.NotificationCategory
import com.ttokttok.application.port.out.NotificationSettingPort
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class NotificationSettingService(
    private val settings: NotificationSettingPort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : NotificationSettingUseCase {

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags> {
        guard.requireManager(actor, institutionId)
        return all(institutionId)
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, changes: Map<NotificationCategory, ChannelFlags>): Map<NotificationCategory, ChannelFlags> {
        guard.requireManager(actor, institutionId)
        val now = clock.now()
        changes.forEach { (category, flags) -> settings.upsert(institutionId, category, flags, now) }
        return all(institutionId)
    }

    private fun all(institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags> {
        val saved = settings.findAll(institutionId)
        return NotificationCategory.entries.associateWith { saved[it] ?: ChannelFlags(push = true, alimtalk = true) }
    }
}

@Service
class LocationConsentService(
    private val consents: LocationConsentPort,
    private val children: GetMyChildrenQuery,
    private val clock: ClockPort,
) : LocationConsentUseCase {

    @Transactional(readOnly = true)
    override fun get(parent: UserId, childId: UUID): LocationConsentView {
        own(parent, childId)
        return LocationConsentView(childId, consents.isGranted(childId), consents.grantedAt(childId))
    }

    @Transactional
    override fun set(parent: UserId, childId: UUID, granted: Boolean): LocationConsentView {
        own(parent, childId)
        consents.set(childId, granted, parent.value, clock.now())
        return LocationConsentView(childId, granted, consents.grantedAt(childId))
    }

    private fun own(parent: UserId, childId: UUID) {
        // resolveFilter 가 본인 자녀가 아니면 403 을 던진다. 아이 id 로 해석되는지도 확인
        val students = children.resolveFilter(parent, childId)
        if (students.isNullOrEmpty() || children.children(parent).none { it.childId.value == childId }) throw ForbiddenException("본인 자녀가 아닙니다")
    }
}
