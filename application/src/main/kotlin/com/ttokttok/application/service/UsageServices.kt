package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.PlatformUsageOverview
import com.ttokttok.application.port.`in`.PlatformUsageQuery
import com.ttokttok.application.port.`in`.UiLocale
import com.ttokttok.application.port.`in`.UsageAlertView
import com.ttokttok.application.port.`in`.UsageAnomalyUseCase
import com.ttokttok.application.port.`in`.UsageDelta
import com.ttokttok.application.port.`in`.UsageMeteringUseCase
import com.ttokttok.application.port.`in`.UsagePoint
import com.ttokttok.application.port.`in`.UserLocaleUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.UsagePort
import com.ttokttok.application.port.out.UserLocalePort
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 기관을 모르는 요청(로그인 등)을 모으는 플랫폼 전체 행 */
val PLATFORM_USAGE_ID: UUID = UUID(0L, 0L)

// ───────── SAAS-006 사용량 수집·이상 탐지 ─────────
@Service
class UsageMeteringService(private val usage: UsagePort) : UsageMeteringUseCase {
    @Transactional
    override fun record(deltas: List<UsageDelta>) {
        if (deltas.isNotEmpty()) usage.add(deltas)
    }
}

@Service
class UsageAnomalyService(
    private val usage: UsagePort,
    private val clock: ClockPort,
) : UsageAnomalyUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun detect(now: Instant): Int {
        val hour = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1))
        val rows = usage.hour(hour)
        val baseline = usage.baseline(hour, rows.map { it.institutionId })
        var created = 0
        fun alert(inst: UUID?, kind: String, value: Double, base: Double?, message: String) {
            if (usage.insertAlert(inst, kind, hour, value, base, message.take(300), now)) created++
        }
        rows.forEach { r ->
            val p = r.point
            val name = r.institutionName ?: "기관"
            if (r.institutionId == PLATFORM_USAGE_ID) {
                if (p.loginFailed >= LOGIN_FAIL_MIN && p.loginFailed > p.loginSuccess) {
                    alert(null, "LOGIN_FAIL", p.loginFailed.toDouble(), null, "로그인 실패 ${p.loginFailed}회 (성공 ${p.loginSuccess}회) — 무차별 대입 시도 의심")
                }
                return@forEach
            }
            val base = baseline[r.institutionId]
            val ref = maxOf(base ?: 0.0, SPIKE_FLOOR_BASE)
            if (p.requests >= SPIKE_MIN && p.requests > ref * SPIKE_RATIO) {
                alert(r.institutionId, "SPIKE", p.requests.toDouble(), base, "$name: 요청 ${p.requests}건, 평소(${"%.0f".format(base ?: 0.0)}건)의 ${"%.1f".format(p.requests / ref)}배")
            }
            if (p.requests >= ERROR_MIN_REQUESTS && p.errors.toDouble() / p.requests >= ERROR_RATE) {
                alert(r.institutionId, "ERRORS", p.errors.toDouble(), null, "$name: 서버 오류 ${p.errors}건 / 요청 ${p.requests}건 (${p.errors * 100 / p.requests}%)")
            }
            if (p.denied >= DENIED_MIN) {
                alert(r.institutionId, "DENIED", p.denied.toDouble(), null, "$name: 권한 거부(401·403) ${p.denied}건 — 만료 토큰 반복·권한 밖 접근 확인")
            }
            if (p.throttled >= THROTTLED_MIN) {
                alert(r.institutionId, "THROTTLED", p.throttled.toDouble(), null, "$name: 요청 제한(429) ${p.throttled}건")
            }
        }
        // 하루 한 번(오전 9시 KST 판정분): 3일 넘게 요청이 없는 운영 중 기관 → 이탈 위험
        if (hour.atZone(clock.zone()).hour == 9) {
            usage.silentInstitutions(hour.minus(Duration.ofDays(3)), hour.plus(Duration.ofHours(1))).forEach { (id, name) ->
                alert(id, "SILENT", 0.0, null, "$name: 3일 동안 사용 기록 없음 — 이탈 위험, 연락 권장")
            }
        }
        if (created > 0) log.warn("사용량 이상 경고 {}건 ({})", created, hour)
        return created
    }

    @Transactional
    override fun purge(now: Instant): Int = usage.purgeBefore(now.minus(Duration.ofDays(90)))

    companion object {
        const val SPIKE_MIN = 300
        const val SPIKE_RATIO = 5.0
        const val SPIKE_FLOOR_BASE = 20.0
        const val ERROR_MIN_REQUESTS = 50
        const val ERROR_RATE = 0.10
        const val DENIED_MIN = 100
        const val THROTTLED_MIN = 20
        const val LOGIN_FAIL_MIN = 100
    }
}

@Service
class PlatformUsageService(
    private val guard: PlatformGuard,
    private val usage: UsagePort,
    private val clock: ClockPort,
) : PlatformUsageQuery {

    private fun checkDays(days: Int) {
        if (days !in 1..90) throw InvalidInputException("INVALID_DAYS", "기간은 1~90일입니다")
    }

    @Transactional(readOnly = true)
    override fun overview(actor: UserId, days: Int): PlatformUsageOverview {
        guard.require(actor)
        checkDays(days)
        val to = clock.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))
        val from = to.minus(Duration.ofDays(days.toLong()))
        val prevFrom = from.minus(Duration.ofDays(days.toLong()))
        val rows = usage.overview(from, to, prevFrom)
        val (req, ok, fail) = usage.platformTotals(from, to)
        return PlatformUsageOverview(days, req, ok, fail, rows, rows.count { it.serviceStatus == "ACTIVE" && it.requests == 0L })
    }

    @Transactional(readOnly = true)
    override fun hourly(actor: UserId, institutionId: UUID?, days: Int): List<UsagePoint> {
        guard.require(actor)
        checkDays(days)
        val to = clock.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))
        return usage.hourly(institutionId, to.minus(Duration.ofDays(days.toLong())), to)
    }

    @Transactional(readOnly = true)
    override fun alerts(actor: UserId, openOnly: Boolean): List<UsageAlertView> {
        guard.require(actor)
        return usage.alerts(openOnly, 200)
    }

    @Transactional
    override fun acknowledge(actor: UserId, alertId: UUID): UsageAlertView {
        guard.require(actor)
        val a = usage.findAlert(alertId) ?: throw NotFoundException("경고")
        if (a.acknowledgedAt == null) usage.acknowledge(alertId, actor, clock.now())
        return usage.findAlert(alertId)!!
    }
}

// ───────── SET-007 ─────────
@Service
class UserLocaleService(private val locales: UserLocalePort) : UserLocaleUseCase {
    @Transactional(readOnly = true)
    override fun get(actor: UserId): UiLocale = locales.get(actor)

    @Transactional
    override fun set(actor: UserId, locale: UiLocale): UiLocale {
        locales.set(actor, locale)
        return locale
    }
}
