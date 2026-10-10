package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.InstitutionUsageRow
import com.ttokttok.application.port.`in`.UiLocale
import com.ttokttok.application.port.`in`.UsageAlertView
import com.ttokttok.application.port.`in`.UsageDelta
import com.ttokttok.application.port.`in`.UsagePoint
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

data class HourUsage(val institutionId: UUID, val institutionName: String?, val point: UsagePoint)

interface UsagePort {
    fun add(deltas: List<UsageDelta>)
    /** 그 시간의 기관별 사용량 (플랫폼 행 포함) */
    fun hour(hour: Instant): List<HourUsage>
    /** 기준선: 직전 7일 같은 시각(hour-of-day)의 평균 요청 수. 기관별 */
    fun baseline(hour: Instant, institutionIds: Collection<UUID>): Map<UUID, Double>
    /** 처음이면 true */
    fun insertAlert(institutionId: UUID?, kind: String, hour: Instant, value: Double, baseline: Double?, message: String, now: Instant): Boolean
    fun overview(from: Instant, to: Instant, prevFrom: Instant): List<InstitutionUsageRow>
    fun platformTotals(from: Instant, to: Instant): Triple<Long, Long, Long>   // requests, loginSuccess, loginFailed
    fun hourly(institutionId: UUID?, from: Instant, to: Instant): List<UsagePoint>
    fun alerts(openOnly: Boolean, limit: Int): List<UsageAlertView>
    fun findAlert(id: UUID): UsageAlertView?
    fun acknowledge(id: UUID, by: UserId, now: Instant)
    fun purgeBefore(before: Instant): Int
    /** 운영 중(ACTIVE, 샘플 제외)인데 [from, to) 동안 요청이 없던 기관 */
    fun silentInstitutions(from: Instant, to: Instant): List<Pair<UUID, String>>
}

interface UserLocalePort {
    fun get(user: UserId): UiLocale
    fun set(user: UserId, locale: UiLocale)
}
