package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.MenuGroupInstitution
import com.ttokttok.application.port.`in`.MenuGroupView
import com.ttokttok.application.port.`in`.PlatformAdminView
import com.ttokttok.application.port.`in`.PlatformInstitutionRow
import com.ttokttok.application.port.`in`.PlatformNoticeKind
import com.ttokttok.application.port.`in`.PlatformNoticeView
import com.ttokttok.application.port.`in`.PlatformPaymentView
import com.ttokttok.application.port.`in`.ServiceStatus
import com.ttokttok.application.port.`in`.TicketCategory
import com.ttokttok.application.port.`in`.TicketMessageView
import com.ttokttok.application.port.`in`.TicketStatus
import com.ttokttok.application.port.`in`.TicketView
import com.ttokttok.application.port.`in`.VoucherSettlementView
import com.ttokttok.application.port.`in`.VoucherUseCase
import com.ttokttok.application.port.`in`.VoucherView
import com.ttokttok.application.port.out.InstitutionServicePort
import com.ttokttok.application.port.out.MenuGroupPort
import com.ttokttok.application.port.out.MonthStats
import com.ttokttok.application.port.out.PlatformAdminPort
import com.ttokttok.application.port.out.PlatformInstitutionPort
import com.ttokttok.application.port.out.PlatformMetricsPort
import com.ttokttok.application.port.out.PlatformNoticePort
import com.ttokttok.application.port.out.PlatformPaymentPort
import com.ttokttok.application.port.out.SupportTicketPort
import com.ttokttok.application.port.out.VoucherBilling
import com.ttokttok.application.port.out.VoucherPort
import com.ttokttok.domain.common.InstitutionId
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
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private fun ResultSet.pid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.pts(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.pdate(col: String): LocalDate? = getDate(col)?.toLocalDate()
private fun psqlDate(d: LocalDate?) = d?.let { java.sql.Date.valueOf(it) }
private fun pts(i: Instant?) = i?.let { Timestamp.from(it) }
private fun pp(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

// ───────── 운영사 계정 / 서비스 상태 ─────────
@Component
class PlatformAdminJdbcAdapter(jdbc: JdbcTemplate) : PlatformAdminPort, InstitutionServicePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun isAdmin(userId: UserId): Boolean =
        named.queryForObject("select exists(select 1 from platform_admin where user_id = :u)", pp("u" to userId.value), Boolean::class.java) ?: false

    override fun list(): List<PlatformAdminView> = named.query(
        "select u.id, u.name, u.email, a.created_at from platform_admin a join app_user u on u.id = a.user_id order by a.created_at",
        pp(),
    ) { rs, _ -> PlatformAdminView(rs.pid("id"), rs.getString("name"), rs.getString("email"), rs.pts("created_at")!!) }

    override fun add(userId: UserId, now: Instant) {
        named.update("insert into platform_admin (user_id, created_at) values (:u, :now) on conflict do nothing", pp("u" to userId.value, "now" to pts(now)))
    }

    override fun remove(userId: UserId) {
        named.update("delete from platform_admin where user_id = :u", pp("u" to userId.value))
    }

    override fun status(institutionId: InstitutionId): ServiceStatus =
        named.query("select service_status from institution where id = :i", pp("i" to institutionId.value), RowMapper { rs, _ -> ServiceStatus.valueOf(rs.getString(1)) })
            .firstOrNull() ?: ServiceStatus.ACTIVE
}

// ───────── SAAS-008 메뉴 그룹 ─────────
@Component
class MenuGroupJdbcAdapter(jdbc: JdbcTemplate) : MenuGroupPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private data class Head(val id: UUID, val name: String, val description: String?, val createdAt: Instant, val updatedAt: Instant)

    private fun load(where: String, params: MapSqlParameterSource): List<MenuGroupView> {
        val heads = named.query("select * from menu_group $where order by name", params) { rs, _ ->
            Head(rs.pid("id"), rs.getString("name"), rs.getString("description"), rs.pts("created_at")!!, rs.pts("updated_at")!!)
        }
        if (heads.isEmpty()) return emptyList()
        val ids = pp("ids" to heads.map { it.id })
        val disabled = named.query("select menu_group_id, menu_key from menu_group_disabled_menu where menu_group_id in (:ids)", ids) { rs, _ ->
            rs.pid("menu_group_id") to rs.getString("menu_key")
        }.groupBy({ it.first }, { it.second })
        val insts = named.query(
            "select m.menu_group_id, i.id, i.name from institution_menu_group m join institution i on i.id = m.institution_id " +
                "where m.menu_group_id in (:ids) order by i.name",
            ids,
        ) { rs, _ -> rs.pid("menu_group_id") to MenuGroupInstitution(rs.pid("id"), rs.getString("name")) }
            .groupBy({ it.first }, { it.second })
        return heads.map { h ->
            MenuGroupView(h.id, h.name, h.description, disabled[h.id].orEmpty().toSortedSet(), insts[h.id].orEmpty(), h.createdAt, h.updatedAt)
        }
    }

    override fun list(): List<MenuGroupView> = load("", pp())

    override fun find(id: UUID): MenuGroupView? = load("where id = :id", pp("id" to id)).firstOrNull()

    override fun existsByName(name: String, exceptId: UUID?): Boolean =
        named.queryForObject(
            "select exists(select 1 from menu_group where lower(name) = lower(:n) and (cast(:ex as uuid) is null or id <> cast(:ex as uuid)))",
            pp("n" to name, "ex" to exceptId), Boolean::class.java,
        ) ?: false

    override fun save(id: UUID, name: String, description: String?, disabledMenus: Set<String>, now: Instant) {
        named.update(
            """insert into menu_group (id, name, description, created_at, updated_at) values (:id, :n, :d, :now, :now)
               on conflict (id) do update set name = excluded.name, description = excluded.description, updated_at = excluded.updated_at""",
            pp("id" to id, "n" to name, "d" to description, "now" to pts(now)),
        )
        named.update("delete from menu_group_disabled_menu where menu_group_id = :id", pp("id" to id))
        disabledMenus.forEach { key ->
            named.update("insert into menu_group_disabled_menu (menu_group_id, menu_key) values (:id, :k)", pp("id" to id, "k" to key))
        }
    }

    override fun delete(id: UUID) {
        named.update("delete from menu_group where id = :id", pp("id" to id))
    }

    override fun replaceInstitutions(groupId: UUID, institutionIds: Set<UUID>, now: Instant) {
        named.update("delete from institution_menu_group where menu_group_id = :g", pp("g" to groupId))
        institutionIds.forEach { assign(it, groupId, now) }
    }

    override fun assign(institutionId: UUID, groupId: UUID?, now: Instant) {
        if (groupId == null) {
            named.update("delete from institution_menu_group where institution_id = :i", pp("i" to institutionId))
            return
        }
        named.update(
            """insert into institution_menu_group (institution_id, menu_group_id, assigned_at) values (:i, :g, :now)
               on conflict (institution_id) do update set menu_group_id = excluded.menu_group_id, assigned_at = excluded.assigned_at""",
            pp("i" to institutionId, "g" to groupId, "now" to pts(now)),
        )
    }

    override fun findByInstitution(institutionId: UUID): MenuGroupView? {
        val groupId = named.query("select menu_group_id from institution_menu_group where institution_id = :i", pp("i" to institutionId)) { rs, _ ->
            rs.pid("menu_group_id")
        }.firstOrNull() ?: return null
        return find(groupId)
    }
}

// ───────── SAAS-001 기관 ─────────
@Component
class PlatformInstitutionJdbcAdapter(jdbc: JdbcTemplate) : PlatformInstitutionPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select i.id, i.name, i.owner_name, i.type, i.service_status, i.status_reason, i.created_at,
                   s.plan_code, s.status as sub_status,
                   (select count(*) from student st where st.institution_id = i.id and st.status <> 'WITHDRAWN') as student_count,
                   v.voucher_code, mg.id as menu_group_id, mg.name as menu_group_name
              from institution i
              left join institution_subscription s on s.institution_id = i.id
              left join voucher_registration v on v.institution_id = i.id and v.status = 'ACTIVE'
              left join institution_menu_group img on img.institution_id = i.id
              left join menu_group mg on mg.id = img.menu_group_id
        """
        val MAPPER = RowMapper<PlatformInstitutionRow> { rs, _ ->
            PlatformInstitutionRow(
                rs.pid("id"), rs.getString("name"), rs.getString("owner_name"), rs.getString("type"),
                ServiceStatus.valueOf(rs.getString("service_status")), rs.getString("status_reason"),
                rs.getString("plan_code"), rs.getString("sub_status"), rs.getInt("student_count"),
                rs.getString("voucher_code"), rs.pts("created_at")!!,
                rs.getObject("menu_group_id", UUID::class.java), rs.getString("menu_group_name"),
            )
        }
    }

    override fun list(status: ServiceStatus?, keyword: String?): List<PlatformInstitutionRow> {
        val kw = keyword?.trim()?.takeIf { it.isNotEmpty() }
        return named.query(
            "$SELECT where (cast(:st as varchar) is null or i.service_status = cast(:st as varchar)) " +
                "and (cast(:kw as varchar) is null or i.name ilike '%' || cast(:kw as varchar) || '%' or i.owner_name ilike '%' || cast(:kw as varchar) || '%') " +
                "order by i.created_at desc limit 500",
            pp("st" to status?.name, "kw" to kw), MAPPER,
        )
    }

    override fun find(id: UUID): PlatformInstitutionRow? =
        named.query("$SELECT where i.id = :id", pp("id" to id), MAPPER).firstOrNull()

    override fun setStatus(id: UUID, status: ServiceStatus, reason: String?, now: Instant) {
        named.update(
            "update institution set service_status = :st, status_reason = :r, status_changed_at = :now, updated_at = :now where id = :id",
            pp("st" to status.name, "r" to reason, "now" to pts(now), "id" to id),
        )
    }

    override fun staffCount(id: UUID): Int =
        named.queryForObject("select count(*) from membership where institution_id = :i and role <> 'PARENT'", pp("i" to id), Int::class.java) ?: 0

    override fun managers(id: UUID): List<UserId> =
        named.query(
            "select user_id from membership where institution_id = :i and role in ('OWNER', 'ADMIN')",
            pp("i" to id), RowMapper { rs, _ -> UserId(rs.pid("user_id")) },
        )
}

// ───────── SAAS-002 구독 결제 ─────────
@Component
class PlatformPaymentJdbcAdapter(jdbc: JdbcTemplate) : PlatformPaymentPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select p.id, p.institution_id, i.name as institution_name, p.plan_code, p.amount, p.status,
                   p.period_start, p.period_end, p.paid_at, p.failure_reason, p.created_at
              from subscription_payment p join institution i on i.id = p.institution_id
        """
        val MAPPER = RowMapper<PlatformPaymentView> { rs, _ ->
            PlatformPaymentView(
                rs.pid("id"), rs.pid("institution_id"), rs.getString("institution_name"), rs.getString("plan_code"), rs.getInt("amount"),
                rs.getString("status"), rs.pdate("period_start")!!, rs.pdate("period_end")!!, rs.pts("paid_at"), rs.getString("failure_reason"),
                rs.pts("created_at")!!,
            )
        }
    }

    override fun list(status: String?, from: LocalDate?, to: LocalDate?): List<PlatformPaymentView> =
        named.query(
            "$SELECT where (cast(:st as varchar) is null or p.status = cast(:st as varchar)) " +
                "and (cast(:from as date) is null or p.created_at >= cast(:from as date)) " +
                "and (cast(:to as date) is null or p.created_at < cast(:to as date)) " +
                "order by p.created_at desc limit 500",
            pp("st" to status, "from" to psqlDate(from), "to" to psqlDate(to)), MAPPER,
        )

    override fun find(id: UUID): PlatformPaymentView? = named.query("$SELECT where p.id = :id", pp("id" to id), MAPPER).firstOrNull()

    override fun recent(institutionId: UUID, limit: Int): List<PlatformPaymentView> =
        named.query("$SELECT where p.institution_id = :i order by p.created_at desc limit :l", pp("i" to institutionId, "l" to limit), MAPPER)

    override fun insert(institutionId: UUID, planCode: String, amount: Int, status: String, periodStart: LocalDate, periodEnd: LocalDate, paidAt: Instant?, failureReason: String?, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into subscription_payment (id, institution_id, plan_code, amount, status, period_start, period_end, paid_at, failure_reason, created_at)
               values (:id, :i, :plan, :amount, :st, :ps, :pe, :paid, :fail, :now)""",
            pp("id" to id, "i" to institutionId, "plan" to planCode, "amount" to amount, "st" to status, "ps" to psqlDate(periodStart), "pe" to psqlDate(periodEnd),
                "paid" to pts(paidAt), "fail" to failureReason, "now" to pts(now)),
        )
        return id
    }

    override fun update(id: UUID, status: String, paidAt: Instant?, failureReason: String?) {
        named.update(
            "update subscription_payment set status = :st, paid_at = :paid, failure_reason = :fail where id = :id",
            pp("st" to status, "paid" to pts(paidAt), "fail" to failureReason, "id" to id),
        )
    }
}

// ───────── SAAS-003 바우처 ─────────
@Component
class VoucherJdbcAdapter(jdbc: JdbcTemplate) : VoucherPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select v.id, v.institution_id, i.name as institution_name, v.voucher_code, v.support_ratio, v.status, v.started_on, v.ended_on, v.memo
              from voucher_registration v join institution i on i.id = v.institution_id
        """
        val MAPPER = RowMapper<VoucherView> { rs, _ ->
            VoucherView(
                rs.pid("id"), rs.pid("institution_id"), rs.getString("institution_name"), rs.getString("voucher_code"), rs.getInt("support_ratio"),
                rs.getString("status"), rs.pdate("started_on"), rs.pdate("ended_on"), rs.getString("memo"),
            )
        }
        const val SETTLEMENT_SELECT = """
            select s.id, s.institution_id, i.name as institution_name, s.period_month, s.billed_amount, s.support_amount, s.status, s.settled_on, s.memo
              from voucher_settlement s join institution i on i.id = s.institution_id
        """
        val SETTLEMENT_MAPPER = RowMapper<VoucherSettlementView> { rs, _ ->
            VoucherSettlementView(
                rs.pid("id"), rs.pid("institution_id"), rs.getString("institution_name"), rs.getString("period_month"), rs.getInt("billed_amount"),
                rs.getInt("support_amount"), rs.getString("status"), rs.pdate("settled_on"), rs.getString("memo"),
            )
        }
    }

    override fun list(): List<VoucherView> = named.query("$SELECT order by i.name", MapSqlParameterSource(), MAPPER)

    override fun find(institutionId: UUID): VoucherView? = named.query("$SELECT where v.institution_id = :i", pp("i" to institutionId), MAPPER).firstOrNull()

    override fun upsert(institutionId: UUID, command: VoucherUseCase.Save, now: Instant) {
        named.update(
            """insert into voucher_registration (id, institution_id, voucher_code, support_ratio, status, started_on, ended_on, memo, created_at, updated_at)
               values (:id, :i, :code, :ratio, :st, :sd, :ed, :memo, :now, :now)
               on conflict (institution_id) do update set voucher_code = :code, support_ratio = :ratio, status = :st,
                   started_on = :sd, ended_on = :ed, memo = :memo, updated_at = :now""",
            pp("id" to Uuid7.next(), "i" to institutionId, "code" to command.voucherCode, "ratio" to command.supportRatio, "st" to command.status,
                "sd" to psqlDate(command.startedOn), "ed" to psqlDate(command.endedOn), "memo" to command.memo, "now" to pts(now)),
        )
    }

    override fun delete(institutionId: UUID) {
        named.update("delete from voucher_registration where institution_id = :i", pp("i" to institutionId))
    }

    override fun settlements(month: String): List<VoucherSettlementView> =
        named.query("$SETTLEMENT_SELECT where s.period_month = :m order by i.name", pp("m" to month), SETTLEMENT_MAPPER)

    override fun findSettlement(id: UUID): VoucherSettlementView? =
        named.query("$SETTLEMENT_SELECT where s.id = :id", pp("id" to id), SETTLEMENT_MAPPER).firstOrNull()

    override fun billing(month: YearMonth): List<VoucherBilling> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        return named.query(
            """select v.institution_id, v.support_ratio, coalesce(pl.monthly_price, 0) as billed
                 from voucher_registration v
                 join institution_subscription s on s.institution_id = v.institution_id
                 join subscription_plan pl on pl.code = s.plan_code
                where (v.started_on is null or v.started_on <= :last)
                  and (v.ended_on is null or v.ended_on >= :first)
                  and (v.status = 'ACTIVE' or v.ended_on is not null)""",
            pp("first" to psqlDate(first), "last" to psqlDate(last)),
            RowMapper { rs, _ -> VoucherBilling(rs.pid("institution_id"), rs.getInt("support_ratio"), rs.getInt("billed")) },
        )
    }

    override fun insertSettlement(institutionId: UUID, month: String, billed: Int, support: Int, now: Instant): Boolean =
        named.update(
            """insert into voucher_settlement (id, institution_id, period_month, billed_amount, support_amount, created_at)
               values (:id, :i, :m, :b, :s, :now) on conflict (institution_id, period_month) do nothing""",
            pp("id" to Uuid7.next(), "i" to institutionId, "m" to month, "b" to billed, "s" to support, "now" to pts(now)),
        ) > 0

    override fun settle(id: UUID, on: LocalDate, memo: String?) {
        named.update(
            "update voucher_settlement set status = 'SETTLED', settled_on = :d, memo = coalesce(:memo, memo) where id = :id",
            pp("d" to psqlDate(on), "memo" to memo, "id" to id),
        )
    }
}

// ───────── SAAS-004 CS 티켓 ─────────
@Component
class SupportTicketJdbcAdapter(jdbc: JdbcTemplate) : SupportTicketPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select t.id, t.institution_id, i.name as institution_name, cu.name as created_by_name, t.subject, t.category, t.status,
                   au.name as assignee_name, t.last_message_at, t.created_at
              from support_ticket t
              join institution i on i.id = t.institution_id
              join app_user cu on cu.id = t.created_by
              left join app_user au on au.id = t.assignee_id
        """
        val MAPPER = RowMapper<TicketView> { rs, _ ->
            TicketView(
                rs.pid("id"), rs.pid("institution_id"), rs.getString("institution_name"), rs.getString("created_by_name"), rs.getString("subject"),
                TicketCategory.valueOf(rs.getString("category")), TicketStatus.valueOf(rs.getString("status")), rs.getString("assignee_name"),
                rs.pts("last_message_at")!!, rs.pts("created_at")!!, null,
            )
        }
    }

    override fun insert(institutionId: UUID, createdBy: UserId, subject: String, category: TicketCategory, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into support_ticket (id, institution_id, created_by, subject, category, last_message_at, created_at, updated_at)
               values (:id, :i, :by, :sub, :cat, :now, :now, :now)""",
            pp("id" to id, "i" to institutionId, "by" to createdBy.value, "sub" to subject, "cat" to category.name, "now" to pts(now)),
        )
        return id
    }

    override fun addMessage(ticketId: UUID, author: UserId, fromOperator: Boolean, body: String, now: Instant) {
        named.update(
            "insert into support_message (id, ticket_id, author_id, from_operator, body, created_at) values (:id, :t, :a, :op, :body, :now)",
            pp("id" to Uuid7.next(), "t" to ticketId, "a" to author.value, "op" to fromOperator, "body" to body, "now" to pts(now)),
        )
        named.update("update support_ticket set last_message_at = :now, updated_at = :now where id = :t", pp("now" to pts(now), "t" to ticketId))
    }

    override fun find(id: UUID): TicketView? = named.query("$SELECT where t.id = :id", pp("id" to id), MAPPER).firstOrNull()

    override fun messages(ticketId: UUID): List<TicketMessageView> =
        named.query(
            """select m.id, u.name as author_name, m.from_operator, m.body, m.created_at
                 from support_message m join app_user u on u.id = m.author_id
                where m.ticket_id = :t order by m.created_at, m.id""",
            pp("t" to ticketId),
            RowMapper { rs, _ -> TicketMessageView(rs.pid("id"), rs.getString("author_name"), rs.getBoolean("from_operator"), rs.getString("body"), rs.pts("created_at")!!) },
        )

    override fun list(institutionId: UUID?, status: TicketStatus?): List<TicketView> =
        named.query(
            "$SELECT where (cast(:i as uuid) is null or t.institution_id = cast(:i as uuid)) " +
                "and (cast(:st as varchar) is null or t.status = cast(:st as varchar)) order by t.last_message_at desc limit 300",
            pp("i" to institutionId, "st" to status?.name), MAPPER,
        )

    override fun update(id: UUID, status: TicketStatus?, assignee: UserId?, now: Instant) {
        named.update(
            """update support_ticket set status = coalesce(cast(:st as varchar), status), assignee_id = coalesce(cast(:a as uuid), assignee_id), updated_at = :now
                where id = :id""",
            pp("st" to status?.name, "a" to assignee?.value, "now" to pts(now), "id" to id),
        )
    }

    override fun creator(id: UUID): UUID? =
        named.query("select created_by from support_ticket where id = :id", pp("id" to id), RowMapper { rs, _ -> rs.pid("created_by") }).firstOrNull()
}

// ───────── SAAS-005 공지/점검 ─────────
@Component
class PlatformNoticeJdbcAdapter(jdbc: JdbcTemplate) : PlatformNoticePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = "select id, kind, title, body, starts_at, ends_at, cancelled, created_at from platform_notice"
        val MAPPER = RowMapper<PlatformNoticeView> { rs, _ ->
            PlatformNoticeView(
                rs.pid("id"), PlatformNoticeKind.valueOf(rs.getString("kind")), rs.getString("title"), rs.getString("body"),
                rs.pts("starts_at")!!, rs.pts("ends_at"), rs.getBoolean("cancelled"), rs.pts("created_at")!!,
            )
        }
    }

    override fun insert(kind: PlatformNoticeKind, title: String, body: String, startsAt: Instant, endsAt: Instant?, by: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            "insert into platform_notice (id, kind, title, body, starts_at, ends_at, created_by, created_at) values (:id, :k, :t, :b, :s, :e, :by, :now)",
            pp("id" to id, "k" to kind.name, "t" to title, "b" to body, "s" to pts(startsAt), "e" to pts(endsAt), "by" to by.value, "now" to pts(now)),
        )
        return id
    }

    override fun find(id: UUID): PlatformNoticeView? = named.query("$SELECT where id = :id", pp("id" to id), MAPPER).firstOrNull()

    override fun list(): List<PlatformNoticeView> = named.query("$SELECT order by starts_at desc limit 200", MapSqlParameterSource(), MAPPER)

    override fun cancel(id: UUID) {
        named.update("update platform_notice set cancelled = true where id = :id", pp("id" to id))
    }

    override fun active(now: Instant): List<PlatformNoticeView> =
        named.query(
            "$SELECT where not cancelled and starts_at <= :now and (ends_at is null or ends_at > :now) order by starts_at desc",
            pp("now" to pts(now)), MAPPER,
        )
}

// ───────── SAAS-007 지표 ─────────
@Component
// ONB-003: 샘플(체험) 기관은 지표에서 뺀다
class PlatformMetricsJdbcAdapter(jdbc: JdbcTemplate) : PlatformMetricsPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun monthStats(start: Instant, end: Instant): MonthStats {
        val prm = pp("start" to pts(start), "end" to pts(end))
        val newPaid = named.queryForObject("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at >= :start and activated_at < :end", prm, Int::class.java) ?: 0
        val churned = named.queryForObject("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at is not null and canceled_at >= :start and canceled_at < :end", prm, Int::class.java) ?: 0
        val activeAtStart = named.queryForObject(
            "select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at < :start and (canceled_at is null or canceled_at >= :start)", prm, Int::class.java,
        ) ?: 0
        val activeAtEnd = named.queryForObject(
            "select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at < :end and (canceled_at is null or canceled_at >= :end)", prm, Int::class.java,
        ) ?: 0
        // 과거 요금 이력이 없어 현재 플랜 가격으로 근사한다
        val mrr = named.queryForObject(
            """select coalesce(sum(pl.monthly_price), 0) from institution_subscription s join subscription_plan pl on pl.code = s.plan_code
                where s.institution_id not in (select id from institution where is_sample) and s.activated_at < :end and (s.canceled_at is null or s.canceled_at >= :end)""",
            prm, Long::class.java,
        ) ?: 0L
        return MonthStats(newPaid, churned, activeAtStart, activeAtEnd, mrr)
    }

    override fun currentCounts(): Pair<Int, Int> {
        val paying = named.queryForObject("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and status in ('ACTIVE', 'PAST_DUE')", MapSqlParameterSource(), Int::class.java) ?: 0
        val trial = named.queryForObject("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and status = 'TRIAL'", MapSqlParameterSource(), Int::class.java) ?: 0
        return paying to trial
    }

    override fun currentMrr(): Long =
        named.queryForObject(
            """select coalesce(sum(pl.monthly_price), 0) from institution_subscription s join subscription_plan pl on pl.code = s.plan_code
                where s.institution_id not in (select id from institution where is_sample) and s.status in ('ACTIVE', 'PAST_DUE')""",
            MapSqlParameterSource(), Long::class.java,
        ) ?: 0L

    override fun marketingCost(month: String): Long? =
        named.query("select amount from platform_marketing_cost where month = :m", pp("m" to month), RowMapper { rs, _ -> rs.getLong(1) }).firstOrNull()

    override fun setMarketingCost(month: String, amount: Long, by: UserId, now: Instant) {
        named.update(
            """insert into platform_marketing_cost (month, amount, updated_by, updated_at) values (:m, :a, :by, :now)
               on conflict (month) do update set amount = :a, updated_by = :by, updated_at = :now""",
            pp("m" to month, "a" to amount, "by" to by.value, "now" to pts(now)),
        )
    }
}
