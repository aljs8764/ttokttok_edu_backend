package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import java.time.Instant

/** SET-004 알림 종류. 학부모에게 가는 알림만 대상 (교직원 알림은 항상 켜짐) */
enum class NotificationCategory(val label: String) {
    ATTENDANCE("출결(등·하원)"), NOTICE("알림장·공지"), EVENT("행사"), CHAT("메시지(회신·채팅)"),
    SURVEY("설문"), ALBUM("앨범"), ABSENCE("결석 신청 확인"), PAYMENT("납부 안내·미납 알림");

    companion object {
        /** ParentPushRequested.template → 카테고리. 몰라도 null (끌 수 없는 알림) */
        fun ofTemplate(template: String): NotificationCategory? = when {
            template.startsWith("EVENT") -> EVENT
            template.startsWith("CHAT") -> CHAT
            template.startsWith("SURVEY") -> SURVEY
            template.startsWith("ALBUM") -> ALBUM
            template.startsWith("ABSENCE") -> ABSENCE
            template.startsWith("NOTICE") -> NOTICE
            template.startsWith("PAYMENT") -> PAYMENT
            template.startsWith("SHUTTLE") -> ATTENDANCE
            else -> null
        }
    }
}

data class ChannelFlags(val push: Boolean, val alimtalk: Boolean)

interface NotificationSettingPort {
    /** 설정이 없으면 켜짐. SMS·EMAIL 은 이 설정과 무관 */
    fun enabled(institutionId: InstitutionId, category: NotificationCategory, channel: NotificationChannel): Boolean
    fun findAll(institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags>
    fun upsert(institutionId: InstitutionId, category: NotificationCategory, flags: ChannelFlags, now: Instant)
}

/** PAR-008 */
interface LocationConsentPort {
    /** 행이 없으면 true */
    fun isGranted(childId: java.util.UUID): Boolean
    fun set(childId: java.util.UUID, granted: Boolean, by: java.util.UUID, now: Instant)
    fun grantedAt(childId: java.util.UUID): Instant?
}
