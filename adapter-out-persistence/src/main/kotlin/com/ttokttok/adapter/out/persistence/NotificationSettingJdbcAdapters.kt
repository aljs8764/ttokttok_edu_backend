package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.ChannelFlags
import com.ttokttok.application.port.out.LocationConsentPort
import com.ttokttok.application.port.out.NotificationCategory
import com.ttokttok.application.port.out.NotificationChannel
import com.ttokttok.application.port.out.NotificationSettingPort
import com.ttokttok.domain.common.InstitutionId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** SET-004 알림 채널 옵션 */
@Component
class NotificationSettingJdbcAdapter(jdbc: JdbcTemplate) : NotificationSettingPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun enabled(institutionId: InstitutionId, category: NotificationCategory, channel: NotificationChannel): Boolean {
        val column = when (channel) {
            NotificationChannel.PUSH -> "push_enabled"
            NotificationChannel.ALIMTALK -> "alimtalk_enabled"
            else -> return true
        }
        return named.query(
            "select $column from notification_setting where institution_id = :inst and category = :cat",
            MapSqlParameterSource("inst", institutionId.value).addValue("cat", category.name),
        ) { rs, _ -> rs.getBoolean(1) }.firstOrNull() ?: true
    }

    override fun findAll(institutionId: InstitutionId): Map<NotificationCategory, ChannelFlags> =
        named.query(
            "select category, push_enabled, alimtalk_enabled from notification_setting where institution_id = :inst",
            MapSqlParameterSource("inst", institutionId.value),
        ) { rs, _ -> NotificationCategory.valueOf(rs.getString("category")) to ChannelFlags(rs.getBoolean("push_enabled"), rs.getBoolean("alimtalk_enabled")) }.toMap()

    override fun upsert(institutionId: InstitutionId, category: NotificationCategory, flags: ChannelFlags, now: Instant) {
        named.update(
            """
            insert into notification_setting (institution_id, category, push_enabled, alimtalk_enabled, updated_at)
            values (:inst, :cat, :push, :alimtalk, :now)
            on conflict (institution_id, category) do update set push_enabled = :push, alimtalk_enabled = :alimtalk, updated_at = :now
            """.trimIndent(),
            MapSqlParameterSource("inst", institutionId.value).addValue("cat", category.name)
                .addValue("push", flags.push).addValue("alimtalk", flags.alimtalk).addValue("now", Timestamp.from(now)),
        )
    }
}

/** PAR-008 위치 정보 제공 동의 */
@Component
class LocationConsentJdbcAdapter(jdbc: JdbcTemplate) : LocationConsentPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun isGranted(childId: UUID): Boolean =
        named.query("select granted from child_location_consent where child_id = :c", MapSqlParameterSource("c", childId)) { rs, _ -> rs.getBoolean(1) }.firstOrNull() ?: true

    override fun set(childId: UUID, granted: Boolean, by: UUID, now: Instant) {
        named.update(
            """
            insert into child_location_consent (child_id, granted, updated_by, updated_at) values (:c, :g, :by, :now)
            on conflict (child_id) do update set granted = :g, updated_by = :by, updated_at = :now
            """.trimIndent(),
            MapSqlParameterSource("c", childId).addValue("g", granted).addValue("by", by).addValue("now", Timestamp.from(now)),
        )
    }

    override fun grantedAt(childId: UUID): Instant? =
        named.query("select updated_at from child_location_consent where child_id = :c", MapSqlParameterSource("c", childId)) { rs, _ -> rs.getTimestamp(1).toInstant() }.firstOrNull()
}
