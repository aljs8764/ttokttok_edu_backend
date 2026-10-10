package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

// Phase 3 P3-③ — SAAS-006 사용량·이상 탐지, SET-007 다국어

/** 웹 필터가 1분마다 모아 넘기는 시간 단위 사용량 증분 */
data class UsageDelta(
    val institutionId: UUID,
    val hour: Instant,
    val requests: Int,
    val errors: Int,
    val denied: Int,
    val throttled: Int,
    val activeUsers: Int,
    val loginSuccess: Int,
    val loginFailed: Int,
)

interface UsageMeteringUseCase {
    fun record(deltas: List<UsageDelta>)
}

interface UsageAnomalyUseCase {
    /** 스케줄러(매시 5분): 지난 1시간을 기준선과 비교해 경고를 만든다. 만든 건수 */
    fun detect(now: Instant): Int
    /** 90일 지난 사용량 삭제 */
    fun purge(now: Instant): Int
}

data class InstitutionUsageRow(
    val institutionId: UUID,
    val institutionName: String,
    val serviceStatus: String,
    val requests: Long,
    val errors: Long,
    val denied: Long,
    val throttled: Long,
    val peakActiveUsers: Int,
    val lastActiveAt: Instant?,
    /** 직전 같은 길이 기간 대비 요청 증감률 (이전이 0 이면 null) */
    val changeRate: Double?,
    val openAlerts: Int,
)

data class UsagePoint(val hour: Instant, val requests: Int, val errors: Int, val denied: Int, val throttled: Int, val activeUsers: Int, val loginSuccess: Int, val loginFailed: Int)

data class UsageAlertView(
    val id: UUID, val institutionId: UUID?, val institutionName: String?, val kind: String, val hour: Instant,
    val value: Double, val baseline: Double?, val message: String, val createdAt: Instant, val acknowledgedAt: Instant?, val acknowledgedByName: String?,
)

data class PlatformUsageOverview(
    val days: Int,
    val totalRequests: Long,
    val loginSuccess: Long,
    val loginFailed: Long,
    val institutions: List<InstitutionUsageRow>,
    /** 기간 안에 요청이 한 번도 없던 활성 기관 수 (이탈 위험) */
    val silentInstitutions: Int,
)

/** 운영 콘솔 (플랫폼 관리자) */
interface PlatformUsageQuery {
    fun overview(actor: UserId, days: Int): PlatformUsageOverview
    /** institutionId = null 이면 플랫폼 전체 합계 */
    fun hourly(actor: UserId, institutionId: UUID?, days: Int): List<UsagePoint>
    fun alerts(actor: UserId, openOnly: Boolean): List<UsageAlertView>
    fun acknowledge(actor: UserId, alertId: UUID): UsageAlertView
}

// ───────── SET-007 ─────────
enum class UiLocale(val code: String) { KO("ko"), EN("en");
    companion object { fun of(code: String): UiLocale? = entries.firstOrNull { it.code == code } }
}

interface UserLocaleUseCase {
    fun get(actor: UserId): UiLocale
    fun set(actor: UserId, locale: UiLocale): UiLocale
}
