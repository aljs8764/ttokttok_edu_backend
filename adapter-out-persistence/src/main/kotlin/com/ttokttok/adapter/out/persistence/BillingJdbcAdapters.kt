package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.InvoiceFilter
import com.ttokttok.application.port.`in`.InvoiceStatus
import com.ttokttok.application.port.`in`.InvoiceSummary
import com.ttokttok.application.port.`in`.InvoiceView
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.PlanView
import com.ttokttok.application.port.`in`.SubscriptionPaymentView
import com.ttokttok.application.port.`in`.SubscriptionStatus
import com.ttokttok.application.port.out.DueReminder
import com.ttokttok.application.port.out.InvoicePort
import com.ttokttok.application.port.out.SubscriptionPort
import com.ttokttok.application.port.out.SubscriptionRow
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

private fun ResultSet.id(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.ts(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.date(col: String): LocalDate? = getDate(col)?.toLocalDate()
private fun ResultSet.intOrNull(col: String): Int? = getInt(col).takeUnless { wasNull() }
private fun sqlDate(d: LocalDate) = java.sql.Date.valueOf(d)
private fun p(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

// ───────── PAY-001~003 ─────────
@Component
class InvoiceJdbcAdapter(jdbc: JdbcTemplate) : InvoicePort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select i.id, i.institution_id, ins.name as institution_name, i.student_id, s.name as student_name,
                   (select c.name from enrollment e join classroom c on c.id = e.classroom_id
                     where e.student_id = s.id and e.to_date is null order by e.from_date desc limit 1) as classroom_name,
                   i.title, i.amount, i.due_date, i.memo, i.status, i.paid_on, i.pay_method, i.paid_note, i.sent_at, i.created_at
              from invoice i
              join student s on s.id = i.student_id
              join institution ins on ins.id = i.institution_id
        """
        val MAPPER = RowMapper<InvoiceView> { rs, _ ->
            InvoiceView(
                rs.id("id"), rs.id("institution_id"), rs.getString("institution_name"), rs.id("student_id"), rs.getString("student_name"),
                rs.getString("classroom_name"), rs.getString("title"), rs.getInt("amount"), rs.date("due_date")!!, rs.getString("memo"),
                InvoiceStatus.valueOf(rs.getString("status")), rs.date("paid_on"), rs.getString("pay_method")?.let { PayMethod.valueOf(it) },
                rs.getString("paid_note"), rs.ts("sent_at"), rs.ts("created_at")!!,
            )
        }
        val REMINDER_MAPPER = RowMapper<DueReminder> { rs, _ ->
            DueReminder(
                rs.id("invoice_id"), rs.id("institution_id"), rs.getString("institution_name"), rs.id("student_id"), rs.getString("student_name"),
                rs.getString("title"), rs.getInt("amount"), rs.date("due_date")!!, rs.getString("stage"),
            )
        }
    }

    override fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return named.query(
            "select id from student where institution_id = :inst and status <> 'WITHDRAWN' and id in (:ids)",
            p("inst" to institutionId.value, "ids" to ids), RowMapper { rs, _ -> rs.id("id") },
        ).toSet()
    }

    override fun insert(institutionId: InstitutionId, studentId: UUID, title: String, amount: Int, dueDate: LocalDate, memo: String?, creator: UserId, sentAt: Instant?, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into invoice (id, institution_id, student_id, title, amount, due_date, memo, sent_at, created_by, created_at, updated_at)
               values (:id, :inst, :s, :title, :amount, :due, :memo, :sent, :by, :now, :now)""",
            p("id" to id, "inst" to institutionId.value, "s" to studentId, "title" to title, "amount" to amount, "due" to sqlDate(dueDate), "memo" to memo,
                "sent" to sentAt?.let { Timestamp.from(it) }, "by" to creator.value, "now" to Timestamp.from(now)),
        )
        return id
    }

    override fun find(id: UUID): InvoiceView? = named.query("$SELECT where i.id = :id", p("id" to id), MAPPER).firstOrNull()

    override fun findMany(ids: Collection<UUID>): List<InvoiceView> =
        if (ids.isEmpty()) emptyList() else named.query("$SELECT where i.id in (:ids) order by s.name", p("ids" to ids), MAPPER)

    override fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate, filter: InvoiceFilter, today: LocalDate, classroomId: UUID?, studentId: UUID?): List<InvoiceView> {
        val params = p("inst" to institutionId.value, "f" to sqlDate(from), "t" to sqlDate(to), "today" to sqlDate(today))
        val cond = StringBuilder("i.institution_id = :inst and i.due_date between :f and :t")
        when (filter) {
            InvoiceFilter.ALL -> Unit
            InvoiceFilter.UNPAID -> cond.append(" and i.status = 'UNPAID'")
            InvoiceFilter.OVERDUE -> cond.append(" and i.status = 'UNPAID' and i.due_date < cast(:today as date)")
            InvoiceFilter.PAID -> cond.append(" and i.status = 'PAID'")
            InvoiceFilter.CANCELED -> cond.append(" and i.status = 'CANCELED'")
        }
        if (classroomId != null) {
            params.addValue("cls", classroomId)
            cond.append(" and exists (select 1 from enrollment e where e.student_id = i.student_id and e.classroom_id = :cls and e.to_date is null)")
        }
        if (studentId != null) {
            params.addValue("stu", studentId)
            cond.append(" and i.student_id = :stu")
        }
        return named.query("$SELECT where $cond order by i.due_date, s.name", params, MAPPER)
    }

    override fun summary(institutionId: InstitutionId, month: YearMonth, today: LocalDate): InvoiceSummary =
        named.queryForObject(
            """select count(*) filter (where status <> 'CANCELED') as cnt,
                      coalesce(sum(amount) filter (where status <> 'CANCELED'), 0) as total,
                      coalesce(sum(amount) filter (where status = 'PAID'), 0) as paid,
                      count(*) filter (where status = 'UNPAID') as unpaid,
                      count(*) filter (where status = 'UNPAID' and due_date < cast(:today as date)) as overdue,
                      coalesce(sum(amount) filter (where status = 'UNPAID' and due_date < cast(:today as date)), 0) as overdue_amount
                 from invoice where institution_id = :inst and due_date between :f and :t""",
            p("inst" to institutionId.value, "f" to sqlDate(month.atDay(1)), "t" to sqlDate(month.atEndOfMonth()), "today" to sqlDate(today)),
        ) { rs, _ ->
            InvoiceSummary(month, rs.getInt("cnt"), rs.getLong("total"), rs.getLong("paid"), rs.getInt("unpaid"), rs.getInt("overdue"), rs.getLong("overdue_amount"))
        }!!

    override fun markPaid(id: UUID, method: PayMethod, paidOn: LocalDate, note: String?, by: UserId, now: Instant) {
        named.update(
            "update invoice set status = 'PAID', pay_method = :m, paid_on = :d, paid_note = :n, marked_by = :by, updated_at = :now where id = :id",
            p("id" to id, "m" to method.name, "d" to sqlDate(paidOn), "n" to note, "by" to by.value, "now" to Timestamp.from(now)),
        )
    }

    override fun markUnpaid(id: UUID, by: UserId, now: Instant) {
        named.update(
            "update invoice set status = 'UNPAID', pay_method = null, paid_on = null, paid_note = null, marked_by = :by, updated_at = :now where id = :id",
            p("id" to id, "by" to by.value, "now" to Timestamp.from(now)),
        )
    }

    override fun cancel(id: UUID, by: UserId, now: Instant) {
        named.update("update invoice set status = 'CANCELED', marked_by = :by, updated_at = :now where id = :id", p("id" to id, "by" to by.value, "now" to Timestamp.from(now)))
    }

    override fun touchSent(id: UUID, now: Instant) {
        named.update("update invoice set sent_at = :now, updated_at = :now where id = :id", p("id" to id, "now" to Timestamp.from(now)))
    }

    override fun guardianUsers(studentId: UUID): List<UserId> =
        named.query(
            "select distinct user_id from guardian where student_id = :s and user_id is not null and link_status = 'LINKED'",
            p("s" to studentId), RowMapper { rs, _ -> UserId(rs.id("user_id")) },
        )

    override fun listForParent(parent: UserId, studentIds: Set<UUID>?, limit: Int): List<InvoiceView> {
        val params = p("u" to parent.value, "limit" to limit)
        val filter = if (studentIds != null) {
            params.addValue("ids", studentIds.ifEmpty { setOf(UUID(0, 0)) })
            " and i.student_id in (:ids)"
        } else ""
        return named.query(
            "$SELECT where i.status <> 'CANCELED' and i.sent_at is not null$filter " +
                "and exists (select 1 from guardian g where g.student_id = i.student_id and g.user_id = :u and g.link_status = 'LINKED') " +
                "order by i.due_date desc, i.created_at desc limit :limit",
            params, MAPPER,
        )
    }

    override fun dueReminders(today: LocalDate, limit: Int): List<DueReminder> =
        named.query(
            """select * from (
                 select i.id as invoice_id, i.institution_id, ins.name as institution_name, i.student_id, s.name as student_name,
                        i.title, i.amount, i.due_date,
                        case when i.due_date - cast(:today as date) between 1 and 3 then 'DUE_SOON'
                             when i.due_date = cast(:today as date) then 'DUE_DAY'
                             when cast(:today as date) - i.due_date between 1 and 6 then 'OVERDUE_1'
                             when cast(:today as date) - i.due_date between 7 and 30 then 'OVERDUE_7' end as stage
                   from invoice i
                   join student s on s.id = i.student_id
                   join institution ins on ins.id = i.institution_id
                  where i.status = 'UNPAID' and i.sent_at is not null
                    and i.due_date between cast(:today as date) - 30 and cast(:today as date) + 3
               ) x
               where x.stage is not null
                 and not exists (select 1 from invoice_reminder_log l where l.invoice_id = x.invoice_id and l.stage = x.stage)
               order by x.due_date limit :limit""",
            p("today" to sqlDate(today), "limit" to limit), REMINDER_MAPPER,
        )

    override fun markReminded(invoiceId: UUID, stage: String, now: Instant): Boolean =
        named.update(
            "insert into invoice_reminder_log (invoice_id, stage, sent_at) values (:i, :s, :now) on conflict do nothing",
            p("i" to invoiceId, "s" to stage, "now" to Timestamp.from(now)),
        ) == 1
}

// ───────── SET-003 ─────────
@Component
class SubscriptionJdbcAdapter(jdbc: JdbcTemplate) : SubscriptionPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        val PLAN = RowMapper<PlanView> { rs, _ ->
            PlanView(rs.getString("code"), rs.getString("name"), rs.intOrNull("monthly_price"), rs.intOrNull("student_limit"), rs.getString("description"))
        }
        val ROW = RowMapper<SubscriptionRow> { rs, _ ->
            SubscriptionRow(
                rs.getString("plan_code"), SubscriptionStatus.valueOf(rs.getString("status")), rs.date("trial_ends_on"), rs.date("next_billing_date"),
                rs.getBoolean("cancel_at_period_end"), rs.getString("card_brand"), rs.getString("card_last4"),
            )
        }
    }

    override fun plans(): List<PlanView> =
        named.query("select code, name, monthly_price, student_limit, description from subscription_plan order by sort_order", PLAN)

    override fun plan(code: String): PlanView? =
        named.query("select code, name, monthly_price, student_limit, description from subscription_plan where code = :c", p("c" to code), PLAN).firstOrNull()

    override fun find(institutionId: InstitutionId): SubscriptionRow? =
        named.query("select * from institution_subscription where institution_id = :i", p("i" to institutionId.value), ROW).firstOrNull()

    override fun ensure(institutionId: InstitutionId, trialEndsOn: LocalDate, now: Instant): SubscriptionRow {
        named.update(
            """insert into institution_subscription (institution_id, plan_code, status, trial_ends_on, next_billing_date, created_at, updated_at)
               values (:i, 'TRIAL', 'TRIAL', :t, :t, :now, :now) on conflict (institution_id) do nothing""",
            p("i" to institutionId.value, "t" to sqlDate(trialEndsOn), "now" to Timestamp.from(now)),
        )
        return find(institutionId)!!
    }

    override fun changePlan(institutionId: InstitutionId, planCode: String, status: SubscriptionStatus, nextBillingDate: LocalDate?, now: Instant) {
        named.update(
            """update institution_subscription set plan_code = :c, status = :st, next_billing_date = :nb, trial_ends_on = null,
                      cancel_at_period_end = false, updated_at = :now,
                      activated_at = case when cast(:st as varchar) = 'ACTIVE' then coalesce(activated_at, :now) else activated_at end,
                      canceled_at = case when cast(:st as varchar) = 'CANCELED' then coalesce(canceled_at, :now) else null end
                where institution_id = :i""",
            p("i" to institutionId.value, "c" to planCode, "st" to status.name, "nb" to nextBillingDate?.let { sqlDate(it) }, "now" to Timestamp.from(now)),
        )
    }

    override fun setCard(institutionId: InstitutionId, brand: String, last4: String, now: Instant) {
        named.update(
            "update institution_subscription set card_brand = :b, card_last4 = :l, card_updated_at = :now, updated_at = :now where institution_id = :i",
            p("i" to institutionId.value, "b" to brand, "l" to last4, "now" to Timestamp.from(now)),
        )
    }

    override fun setCancelAtPeriodEnd(institutionId: InstitutionId, cancel: Boolean, now: Instant) {
        named.update(
            "update institution_subscription set cancel_at_period_end = :c, updated_at = :now where institution_id = :i",
            p("i" to institutionId.value, "c" to cancel, "now" to Timestamp.from(now)),
        )
    }

    override fun payments(institutionId: InstitutionId, limit: Int): List<SubscriptionPaymentView> =
        named.query(
            """select id, plan_code, amount, status, period_start, period_end, paid_at, failure_reason
                 from subscription_payment where institution_id = :i order by created_at desc limit :n""",
            p("i" to institutionId.value, "n" to limit),
        ) { rs, _ ->
            SubscriptionPaymentView(rs.id("id"), rs.getString("plan_code"), rs.getInt("amount"), rs.getString("status"), rs.date("period_start")!!, rs.date("period_end")!!, rs.ts("paid_at"), rs.getString("failure_reason"))
        }

    override fun setStatus(institutionId: InstitutionId, status: SubscriptionStatus, nextBillingDate: LocalDate?, now: Instant) {
        named.update(
            """update institution_subscription set status = :st, next_billing_date = coalesce(:nb, next_billing_date), updated_at = :now,
                      activated_at = case when cast(:st as varchar) = 'ACTIVE' then coalesce(activated_at, :now) else activated_at end,
                      canceled_at = case when cast(:st as varchar) = 'CANCELED' then coalesce(canceled_at, :now) else null end
                where institution_id = :i""",
            p("i" to institutionId.value, "st" to status.name, "nb" to nextBillingDate?.let { sqlDate(it) }, "now" to Timestamp.from(now)),
        )
    }

    override fun cancelDue(today: LocalDate, now: Instant): Int =
        named.update(
            """update institution_subscription set status = 'CANCELED', canceled_at = :now, updated_at = :now
                where status = 'ACTIVE' and cancel_at_period_end and next_billing_date <= :today""",
            p("today" to sqlDate(today), "now" to Timestamp.from(now)),
        )

    override fun studentCount(institutionId: InstitutionId): Int =
        named.queryForObject("select count(*) from student where institution_id = :i and status <> 'WITHDRAWN'", p("i" to institutionId.value), Int::class.java) ?: 0
}
