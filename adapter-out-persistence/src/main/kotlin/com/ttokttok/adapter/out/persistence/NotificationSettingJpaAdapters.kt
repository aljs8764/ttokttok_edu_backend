package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ChildLocationConsentEntity
import com.ttokttok.adapter.out.persistence.entity.NotificationSettingEntity
import com.ttokttok.adapter.out.persistence.entity.NotificationSettingKey
import com.ttokttok.adapter.out.persistence.repository.ChildLocationConsentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.NotificationSettingJpaRepository
import com.ttokttok.application.port.out.ChannelFlags
import com.ttokttok.application.port.out.LocationConsentPort
import com.ttokttok.application.port.out.NotificationCategory
import com.ttokttok.application.port.out.NotificationChannel
import com.ttokttok.application.port.out.NotificationSettingPort
import com.ttokttok.domain.common.InstitutionId
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** SET-004 알림 채널 옵션 (행이 없으면 모두 켜짐) */
@Component
class NotificationSettingJpaAdapter(private val repo: NotificationSettingJpaRepository) : NotificationSettingPort {

    override fun enabled(institutionId: InstitutionId, category: NotificationCategory, channel: NotificationChannel): Boolean {
        val row = repo.findByIdOrNull(NotificationSettingKey(institutionId.value, category.name)) ?: return true
        return when (channel) {
            NotificationChannel.PUSH -> row.pushEnabled
            NotificationChannel.ALIMTALK -> row.alimtalkEnabled
            else -> true
        }
    }

    override fun findAll(institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags> =
        repo.findByInstitutionId(institutionId.value).associate { NotificationCategory.valueOf(it.category) to ChannelFlags(it.pushEnabled, it.alimtalkEnabled) }

    override fun upsert(institutionId: InstitutionId, category: NotificationCategory, flags: ChannelFlags, now: Instant) {
        repo.save(NotificationSettingEntity(institutionId.value, category.name, flags.push, flags.alimtalk, now))
    }
}

/** PAR-008 위치 정보 제공 동의 (행이 없으면 동의) */
@Component
class LocationConsentJpaAdapter(private val repo: ChildLocationConsentJpaRepository) : LocationConsentPort {

    override fun isGranted(childId: UUID): Boolean = repo.findByIdOrNull(childId)?.granted ?: true

    override fun set(childId: UUID, granted: Boolean, by: UUID, now: Instant) {
        repo.save(ChildLocationConsentEntity(childId, granted, by, now))
    }

    override fun grantedAt(childId: UUID): Instant? = repo.findByIdOrNull(childId)?.updatedAt
}
