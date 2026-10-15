package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ApiUsageHourlyEntity
import com.ttokttok.adapter.out.persistence.entity.UsageAlertEntity
import com.ttokttok.adapter.out.persistence.repository.ApiUsageHourlyJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UsageAlertJpaRepository
import com.ttokttok.adapter.out.persistence.repository.UserJpaRepository
import com.ttokttok.application.port.`in`.InstitutionUsageRow
import com.ttokttok.application.port.`in`.UiLocale
import com.ttokttok.application.port.`in`.UsageAlertView
import com.ttokttok.application.port.`in`.UsageDelta
import com.ttokttok.application.port.`in`.UsagePoint
import com.ttokttok.application.port.out.HourUsage
import com.ttokttok.application.port.out.UsagePort
import com.ttokttok.application.port.out.UserLocalePort
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val ZERO_ID: UUID = UUID(0L, 0L)

@Component
class UsageJpaAdapter(
    private val usage: ApiUsageHourlyJpaRepository,
    private val alerts: UsageAlertJpaRepository,
    private val q: JpaQueries,
) : UsagePort {

    private fun point(rs: Row) = UsagePoint(
        rs.instant("hour"), rs.int("requests"), rs.int("errors"), rs.int("denied"), rs.int("throttled"),
        rs.int("active_users"), rs.int("login_success"), rs.int("login_failed"),
    )

    private fun ApiUsageHourlyEntity.toPoint() = UsagePoint(hour, requests, errors, denied, throttled, activeUsers, loginSuccess, loginFailed)

    /** 여러 인스턴스가 동시에 더하므로 on conflict 로 원자적으로 누적 (네이티브) */
    override fun add(deltas: List<UsageDelta>) {
        deltas.forEach {
            q.update(
                """insert into api_usage_hourly (institution_id, hour, requests, errors, denied, throttled, active_users, login_success, login_failed)
                   values (:i, :h, :r, :e, :d, :t, :u, :ls, :lf)
                   on conflict (institution_id, hour) do update set
                       requests = api_usage_hourly.requests + excluded.requests,
                       errors = api_usage_hourly.errors + excluded.errors,
                       denied = api_usage_hourly.denied + excluded.denied,
                       throttled = api_usage_hourly.throttled + excluded.throttled,
                       active_users = greatest(api_usage_hourly.active_users, excluded.active_users),
                       login_success = api_usage_hourly.login_success + excluded.login_success,
                       login_failed = api_usage_hourly.login_failed + excluded.login_failed""",
                params(
                    "i" to it.institutionId, "h" to it.hour, "r" to it.requests, "e" to it.errors, "d" to it.denied, "t" to it.throttled,
                    "u" to it.activeUsers, "ls" to it.loginSuccess, "lf" to it.loginFailed,
                ),
            )
        }
    }

    override fun hour(hour: Instant): List<HourUsage> =
        q.jpqlRows(
            "select u, i.name from ApiUsageHourlyEntity u left join InstitutionEntity i on i.id = u.institutionId where u.hour = :h",
            params("h" to hour),
        ).map { r ->
            val u = r[0] as ApiUsageHourlyEntity
            HourUsage(u.institutionId, r[1] as String?, u.toPoint())
        }

    override fun baseline(hour: Instant, institutionIds: Collection<UUID>): Map<UUID, Double> {
        if (institutionIds.isEmpty()) return emptyMap()
        // 직전 7일의 같은 시각. 기록이 없는 날은 0 으로 쳐서 7로 나눈다 (generate_series 라 네이티브)
        return q.rows(
            """select institution_id, sum(requests)::float8 / 7 as avg_req from api_usage_hourly
                where institution_id in (:ids) and hour in (select cast(:h as timestamptz) - make_interval(days => d) from generate_series(1, 7) d)
                group by institution_id""",
            params("ids" to institutionIds, "h" to hour),
        ).associate { it.uuid("institution_id") to it.double("avg_req") }
    }

    /** (기관, 종류, 시각) 유니크 식 인덱스라 on conflict 는 네이티브 */
    override fun insertAlert(institutionId: UUID?, kind: String, hour: Instant, value: Double, baseline: Double?, message: String, now: Instant): Boolean =
        q.update(
            """insert into usage_alert (id, institution_id, kind, hour, value, baseline, message, created_at)
               values (:id, :i, :k, :h, :v, :b, :m, :now)
               on conflict do nothing""",
            params(
                "id" to Uuid7.next(), "i" to typed(institutionId), "k" to kind, "h" to hour, "v" to value, "b" to typed(baseline),
                "m" to message, "now" to now,
            ),
        ) == 1

    override fun overview(from: Instant, to: Instant, prevFrom: Instant): List<InstitutionUsageRow> =
        q.rows(
            """select i.id, i.name, i.service_status,
                      coalesce(sum(u.requests) filter (where u.hour >= :f), 0) as requests,
                      coalesce(sum(u.errors) filter (where u.hour >= :f), 0) as errors,
                      coalesce(sum(u.denied) filter (where u.hour >= :f), 0) as denied,
                      coalesce(sum(u.throttled) filter (where u.hour >= :f), 0) as throttled,
                      coalesce(max(u.active_users) filter (where u.hour >= :f), 0) as peak_users,
                      max(u.hour) filter (where u.requests > 0) as last_active,
                      coalesce(sum(u.requests) filter (where u.hour < :f), 0) as prev_requests,
                      (select count(*) from usage_alert a where a.institution_id = i.id and a.acknowledged_at is null) as open_alerts
                 from institution i
                 left join api_usage_hourly u on u.institution_id = i.id and u.hour >= :pf and u.hour < :t
                where not i.is_sample
                group by i.id, i.name, i.service_status
                order by requests desc, i.name""",
            params("f" to from, "t" to to, "pf" to prevFrom),
        ).map { rs ->
            val req = rs.long("requests")
            val prev = rs.long("prev_requests")
            InstitutionUsageRow(
                rs.uuid("id"), rs.str("name"), rs.str("service_status"), req, rs.long("errors"), rs.long("denied"),
                rs.long("throttled"), rs.int("peak_users"), rs.instantOrNull("last_active"),
                if (prev > 0) (req - prev).toDouble() / prev else null, rs.int("open_alerts"),
            )
        }

    override fun platformTotals(from: Instant, to: Instant): Triple<Long, Long, Long> =
        q.row(
            """select coalesce(sum(requests) filter (where institution_id <> :z), 0) as req,
                      coalesce(sum(login_success), 0) as ok, coalesce(sum(login_failed), 0) as fail
                 from api_usage_hourly where hour >= :f and hour < :t""",
            params("z" to ZERO_ID, "f" to from, "t" to to),
        )!!.let { rs -> Triple(rs.long("req"), rs.long("ok"), rs.long("fail")) }

    override fun hourly(institutionId: UUID?, from: Instant, to: Instant): List<UsagePoint> =
        if (institutionId != null) {
            usage.findRange(institutionId, from, to).map { it.toPoint() }
        } else {
            q.rows(
                """select hour, sum(requests)::int as requests, sum(errors)::int as errors, sum(denied)::int as denied, sum(throttled)::int as throttled,
                          sum(active_users)::int as active_users, sum(login_success)::int as login_success, sum(login_failed)::int as login_failed
                     from api_usage_hourly where hour >= :f and hour < :t group by hour order by hour""",
                params("f" to from, "t" to to),
            ).map(::point)
        }

    private val alertSelect = """
        select a, i.name, u.name
          from UsageAlertEntity a left join InstitutionEntity i on i.id = a.institutionId left join UserEntity u on u.id = a.acknowledgedBy
    """

    private fun alertView(r: Array<Any?>): UsageAlertView {
        val a = r[0] as UsageAlertEntity
        return UsageAlertView(
            a.id, a.institutionId, r[1] as String?, a.kind, a.hour,
            a.value, a.baseline, a.message, a.createdAt,
            a.acknowledgedAt, r[2] as String?,
        )
    }

    override fun alerts(openOnly: Boolean, limit: Int): List<UsageAlertView> =
        q.jpqlRows("$alertSelect ${if (openOnly) "where a.acknowledgedAt is null" else ""} order by a.createdAt desc", limit = limit).map(::alertView)

    override fun findAlert(id: UUID): UsageAlertView? = q.jpqlRows("$alertSelect where a.id = :id", params("id" to id)).firstOrNull()?.let(::alertView)

    override fun acknowledge(id: UUID, by: UserId, now: Instant) {
        alerts.acknowledge(id, by.value, now)
    }

    override fun purgeBefore(before: Instant): Int = usage.purgeBefore(before)

    override fun silentInstitutions(from: Instant, to: Instant): List<Pair<UUID, String>> =
        q.jpqlRows(
            """select i.id, i.name from InstitutionEntity i
                where i.serviceStatus = 'ACTIVE' and i.isSample = false and i.createdAt < :f
                  and not exists (select 1 from ApiUsageHourlyEntity u where u.institutionId = i.id and u.hour >= :f and u.hour < :t and u.requests > 0)""",
            params("f" to from, "t" to to),
        ).map { (it[0] as UUID) to (it[1] as String) }
}

@Component
class UserLocaleJpaAdapter(private val users: UserJpaRepository) : UserLocalePort {

    override fun get(user: UserId): UiLocale = users.findByIdOrNull(user.value)?.locale?.let { UiLocale.of(it) } ?: UiLocale.KO

    override fun set(user: UserId, locale: UiLocale) {
        users.setLocale(user.value, locale.code, Instant.now())
    }
}
