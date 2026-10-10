package com.ttokttok.adapter.out.persistence

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.uId(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.uTs(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun uTime(i: Instant?) = i?.let { Timestamp.from(it) }
private fun uP(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }
private val ZERO_ID: UUID = UUID(0L, 0L)

@Component
class UsageJdbcAdapter(jdbc: JdbcTemplate) : UsagePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private val pointMapper = RowMapper<UsagePoint> { rs, _ ->
        UsagePoint(
            rs.uTs("hour")!!, rs.getInt("requests"), rs.getInt("errors"), rs.getInt("denied"), rs.getInt("throttled"),
            rs.getInt("active_users"), rs.getInt("login_success"), rs.getInt("login_failed"),
        )
    }

    override fun add(deltas: List<UsageDelta>) {
        named.batchUpdate(
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
            deltas.map {
                uP("i" to it.institutionId, "h" to uTime(it.hour), "r" to it.requests, "e" to it.errors, "d" to it.denied, "t" to it.throttled,
                    "u" to it.activeUsers, "ls" to it.loginSuccess, "lf" to it.loginFailed)
            }.toTypedArray(),
        )
    }

    override fun hour(hour: Instant): List<HourUsage> =
        named.query(
            "select u.*, i.name from api_usage_hourly u left join institution i on i.id = u.institution_id where u.hour = :h",
            uP("h" to uTime(hour)),
        ) { rs, n -> HourUsage(rs.uId("institution_id")!!, rs.getString("name"), pointMapper.mapRow(rs, n)!!) }

    override fun baseline(hour: Instant, institutionIds: Collection<UUID>): Map<UUID, Double> {
        if (institutionIds.isEmpty()) return emptyMap()
        // 직전 7일의 같은 시각. 기록이 없는 날은 0 으로 쳐서 7로 나눈다
        return named.query(
            """select institution_id, sum(requests)::float8 / 7 as avg_req from api_usage_hourly
                where institution_id in (:ids) and hour in (select cast(:h as timestamptz) - make_interval(days => d) from generate_series(1, 7) d)
                group by institution_id""",
            uP("ids" to institutionIds, "h" to uTime(hour)),
        ) { rs, _ -> rs.uId("institution_id")!! to rs.getDouble("avg_req") }.toMap()
    }

    override fun insertAlert(institutionId: UUID?, kind: String, hour: Instant, value: Double, baseline: Double?, message: String, now: Instant): Boolean =
        named.update(
            """insert into usage_alert (id, institution_id, kind, hour, value, baseline, message, created_at)
               values (:id, :i, :k, :h, :v, :b, :m, :now)
               on conflict do nothing""",
            uP("id" to Uuid7.next(), "i" to institutionId, "k" to kind, "h" to uTime(hour), "v" to value, "b" to baseline, "m" to message, "now" to uTime(now)),
        ) == 1

    override fun overview(from: Instant, to: Instant, prevFrom: Instant): List<InstitutionUsageRow> =
        named.query(
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
            uP("f" to uTime(from), "t" to uTime(to), "pf" to uTime(prevFrom)),
        ) { rs, _ ->
            val req = rs.getLong("requests")
            val prev = rs.getLong("prev_requests")
            InstitutionUsageRow(
                rs.uId("id")!!, rs.getString("name"), rs.getString("service_status"), req, rs.getLong("errors"), rs.getLong("denied"),
                rs.getLong("throttled"), rs.getInt("peak_users"), rs.uTs("last_active"),
                if (prev > 0) (req - prev).toDouble() / prev else null, rs.getInt("open_alerts"),
            )
        }

    override fun platformTotals(from: Instant, to: Instant): Triple<Long, Long, Long> =
        named.queryForObject(
            """select coalesce(sum(requests) filter (where institution_id <> :z), 0) as req,
                      coalesce(sum(login_success), 0) as ok, coalesce(sum(login_failed), 0) as fail
                 from api_usage_hourly where hour >= :f and hour < :t""",
            uP("z" to ZERO_ID, "f" to uTime(from), "t" to uTime(to)),
        ) { rs, _ -> Triple(rs.getLong("req"), rs.getLong("ok"), rs.getLong("fail")) }!!

    override fun hourly(institutionId: UUID?, from: Instant, to: Instant): List<UsagePoint> =
        if (institutionId != null) {
            named.query(
                "select * from api_usage_hourly where institution_id = :i and hour >= :f and hour < :t order by hour",
                uP("i" to institutionId, "f" to uTime(from), "t" to uTime(to)), pointMapper,
            )
        } else {
            named.query(
                """select hour, sum(requests)::int as requests, sum(errors)::int as errors, sum(denied)::int as denied, sum(throttled)::int as throttled,
                          sum(active_users)::int as active_users, sum(login_success)::int as login_success, sum(login_failed)::int as login_failed
                     from api_usage_hourly where hour >= :f and hour < :t group by hour order by hour""",
                uP("f" to uTime(from), "t" to uTime(to)), pointMapper,
            )
        }

    private val alertSelect = """
        select a.*, i.name as institution_name, u.name as ack_name
          from usage_alert a left join institution i on i.id = a.institution_id left join app_user u on u.id = a.acknowledged_by
    """
    private val alertMapper = RowMapper<UsageAlertView> { rs, _ ->
        UsageAlertView(
            rs.uId("id")!!, rs.uId("institution_id"), rs.getString("institution_name"), rs.getString("kind"), rs.uTs("hour")!!,
            rs.getDouble("value"), rs.getDouble("baseline").takeUnless { rs.wasNull() }, rs.getString("message"), rs.uTs("created_at")!!,
            rs.uTs("acknowledged_at"), rs.getString("ack_name"),
        )
    }

    override fun alerts(openOnly: Boolean, limit: Int): List<UsageAlertView> =
        named.query(
            "$alertSelect ${if (openOnly) "where a.acknowledged_at is null" else ""} order by a.created_at desc limit :lim",
            uP("lim" to limit), alertMapper,
        )

    override fun findAlert(id: UUID): UsageAlertView? = named.query("$alertSelect where a.id = :id", uP("id" to id), alertMapper).firstOrNull()

    override fun acknowledge(id: UUID, by: UserId, now: Instant) {
        named.update("update usage_alert set acknowledged_at = :now, acknowledged_by = :by where id = :id and acknowledged_at is null",
            uP("id" to id, "by" to by.value, "now" to uTime(now)))
    }

    override fun purgeBefore(before: Instant): Int =
        named.update("delete from api_usage_hourly where hour < :b", uP("b" to uTime(before)))

    override fun silentInstitutions(from: Instant, to: Instant): List<Pair<UUID, String>> =
        named.query(
            """select i.id, i.name from institution i
                where i.service_status = 'ACTIVE' and not i.is_sample and i.created_at < :f
                  and not exists (select 1 from api_usage_hourly u where u.institution_id = i.id and u.hour >= :f and u.hour < :t and u.requests > 0)""",
            uP("f" to uTime(from), "t" to uTime(to)),
        ) { rs, _ -> rs.uId("id")!! to rs.getString("name") }
}

@Component
class UserLocaleJdbcAdapter(jdbc: JdbcTemplate) : UserLocalePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun get(user: UserId): UiLocale =
        named.query("select locale from app_user where id = :u", uP("u" to user.value)) { rs, _ -> rs.getString("locale") }
            .firstOrNull()?.let { UiLocale.of(it) } ?: UiLocale.KO

    override fun set(user: UserId, locale: UiLocale) {
        named.update("update app_user set locale = :l, updated_at = now() where id = :u", uP("u" to user.value, "l" to locale.code))
    }
}
