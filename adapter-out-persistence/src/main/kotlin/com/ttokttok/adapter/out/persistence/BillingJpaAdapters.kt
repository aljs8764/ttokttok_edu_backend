package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.InstitutionSubscriptionEntity
import com.ttokttok.adapter.out.persistence.entity.InvoiceEntity
import com.ttokttok.adapter.out.persistence.entity.SubscriptionPlanEntity
import com.ttokttok.adapter.out.persistence.repository.EnrollmentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.GuardianJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InstitutionSubscriptionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InvoiceJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InvoiceReminderLogJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SubscriptionPaymentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SubscriptionPlanJpaRepository
import com.ttokttok.adapter.out.persistence.repository.currentClassroomNameOf
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// ───────── PAY-001~003 ─────────
@Component
class InvoiceJpaAdapter(
    private val invoices: InvoiceJpaRepository,
    private val reminderLogs: InvoiceReminderLogJpaRepository,
    private val students: StudentJpaRepository,
    private val guardians: GuardianJpaRepository,
    private val enrollments: EnrollmentJpaRepository,
    private val q: JpaQueries,
) : InvoicePort {

    private companion object {
        const val SELECT = """
            select i, ins.name, s.name from InvoiceEntity i
              join StudentEntity s on s.id = i.studentId
              join InstitutionEntity ins on ins.id = i.institutionId
        """
    }

    /** [SELECT] 결과 → 화면 모델 (현재 반 이름은 한 번에 따로 읽는다) */
    private fun views(rows: List<Array<Any?>>): List<InvoiceView> {
        val classNames = enrollments.currentClassroomNameOf(rows.map { (it[0] as InvoiceEntity).studentId })
        return rows.map { r ->
            val i = r[0] as InvoiceEntity
            InvoiceView(
                i.id, i.institutionId, r[1] as String, i.studentId, r[2] as String,
                classNames[i.studentId], i.title, i.amount, i.dueDate, i.memo,
                InvoiceStatus.valueOf(i.status), i.paidOn, i.payMethod?.let { PayMethod.valueOf(it) },
                i.paidNote, i.sentAt, i.createdAt,
            )
        }
    }

    override fun activeStudentIds(institutionId: InstitutionId, ids: Collection<UUID>): Set<UUID> =
        if (ids.isEmpty()) emptySet() else students.findActiveIds(institutionId.value, ids).toSet()

    override fun insert(institutionId: InstitutionId, studentId: UUID, title: String, amount: Int, dueDate: LocalDate, memo: String?, creator: UserId, sentAt: Instant?, now: Instant): UUID {
        val id = Uuid7.next()
        invoices.save(
            InvoiceEntity(
                id, institutionId.value, studentId, title, amount, dueDate, memo, sentAt = sentAt, createdBy = creator.value, createdAt = now, updatedAt = now,
            ),
        )
        return id
    }

    override fun find(id: UUID): InvoiceView? = views(q.jpqlRows("$SELECT where i.id = :id", params("id" to id))).firstOrNull()

    override fun findMany(ids: Collection<UUID>): List<InvoiceView> =
        if (ids.isEmpty()) emptyList() else views(q.jpqlRows("$SELECT where i.id in (:ids) order by s.name", params("ids" to ids)))

    override fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate, filter: InvoiceFilter, today: LocalDate, classroomId: UUID?, studentId: UUID?): List<InvoiceView> {
        val p = params("inst" to institutionId.value, "f" to from, "t" to to)
        val cond = StringBuilder("i.institutionId = :inst and i.dueDate between :f and :t")
        when (filter) {
            InvoiceFilter.ALL -> Unit
            InvoiceFilter.UNPAID -> cond.append(" and i.status = 'UNPAID'")
            InvoiceFilter.OVERDUE -> {
                cond.append(" and i.status = 'UNPAID' and i.dueDate < :today")
                p["today"] = today
            }
            InvoiceFilter.PAID -> cond.append(" and i.status = 'PAID'")
            InvoiceFilter.CANCELED -> cond.append(" and i.status = 'CANCELED'")
        }
        if (classroomId != null) {
            p["cls"] = classroomId
            cond.append(" and exists (select 1 from EnrollmentEntity e where e.studentId = i.studentId and e.classroomId = :cls and e.toDate is null)")
        }
        if (studentId != null) {
            p["stu"] = studentId
            cond.append(" and i.studentId = :stu")
        }
        return views(q.jpqlRows("$SELECT where $cond order by i.dueDate, s.name", p))
    }

    override fun summary(institutionId: InstitutionId, month: YearMonth, today: LocalDate): InvoiceSummary {
        val r = q.row(
            """select count(*) filter (where status <> 'CANCELED') as cnt,
                      coalesce(sum(amount) filter (where status <> 'CANCELED'), 0) as total,
                      coalesce(sum(amount) filter (where status = 'PAID'), 0) as paid,
                      count(*) filter (where status = 'UNPAID') as unpaid,
                      count(*) filter (where status = 'UNPAID' and due_date < :today) as overdue,
                      coalesce(sum(amount) filter (where status = 'UNPAID' and due_date < :today), 0) as overdue_amount
                 from invoice where institution_id = :inst and due_date between :f and :t""",
            params("inst" to institutionId.value, "f" to month.atDay(1), "t" to month.atEndOfMonth(), "today" to today),
        )!!
        return InvoiceSummary(month, r.int("cnt"), r.long("total"), r.long("paid"), r.int("unpaid"), r.int("overdue"), r.long("overdue_amount"))
    }

    private fun update(id: UUID, change: InvoiceEntity.() -> Unit) {
        val e = invoices.findByIdOrNull(id) ?: return
        e.change()
        invoices.save(e)
    }

    override fun markPaid(id: UUID, method: PayMethod, paidOn: LocalDate, note: String?, by: UserId, now: Instant) = update(id) {
        status = "PAID"; payMethod = method.name; this.paidOn = paidOn; paidNote = note; markedBy = by.value; updatedAt = now
    }

    override fun markUnpaid(id: UUID, by: UserId, now: Instant) = update(id) {
        status = "UNPAID"; payMethod = null; paidOn = null; paidNote = null; markedBy = by.value; updatedAt = now
    }

    override fun cancel(id: UUID, by: UserId, now: Instant) = update(id) {
        status = "CANCELED"; markedBy = by.value; updatedAt = now
    }

    override fun touchSent(id: UUID, now: Instant) = update(id) {
        sentAt = now; updatedAt = now
    }

    override fun guardianUsers(studentId: UUID): List<UserId> = guardians.findLinkedUserIds(studentId).map(::UserId)

    override fun listForParent(parent: UserId, studentIds: Set<UUID>?, limit: Int): List<InvoiceView> {
        val p = params("u" to parent.value)
        val filter = if (studentIds != null) {
            p["ids"] = studentIds.ifEmpty { setOf(UUID(0, 0)) }
            " and i.studentId in (:ids)"
        } else ""
        return views(
            q.jpqlRows(
                "$SELECT where i.status <> 'CANCELED' and i.sentAt is not null$filter " +
                    "and exists (select 1 from GuardianEntity g where g.studentId = i.studentId and g.userId = :u and g.linkStatus = 'LINKED') " +
                    "order by i.dueDate desc, i.createdAt desc",
                p, limit = limit,
            ),
        )
    }

    /** 날짜 차이로 단계를 정하는 집계라 네이티브 쿼리 */
    override fun dueReminders(today: LocalDate, limit: Int): List<DueReminder> =
        q.rows(
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
            params("today" to today, "limit" to limit),
        ).map {
            DueReminder(
                it.uuid("invoice_id"), it.uuid("institution_id"), it.str("institution_name"), it.uuid("student_id"), it.str("student_name"),
                it.str("title"), it.int("amount"), it.date("due_date"), it.str("stage"),
            )
        }

    override fun markReminded(invoiceId: UUID, stage: String, now: Instant): Boolean = reminderLogs.insertIfAbsent(invoiceId, stage, now) == 1
}

// ───────── SET-003 ─────────
@Component
class SubscriptionJpaAdapter(
    private val plans: SubscriptionPlanJpaRepository,
    private val subscriptions: InstitutionSubscriptionJpaRepository,
    private val payments: SubscriptionPaymentJpaRepository,
    private val students: StudentJpaRepository,
) : SubscriptionPort {

    private fun SubscriptionPlanEntity.toView() = PlanView(code, name, monthlyPrice, studentLimit, description)

    private fun InstitutionSubscriptionEntity.toRow() = SubscriptionRow(
        planCode, SubscriptionStatus.valueOf(status), trialEndsOn, nextBillingDate, cancelAtPeriodEnd, cardBrand, cardLast4,
    )

    override fun plans(): List<PlanView> = plans.findAllByOrderBySortOrder().map { it.toView() }

    override fun plan(code: String): PlanView? = plans.findByIdOrNull(code)?.toView()

    override fun find(institutionId: InstitutionId): SubscriptionRow? = subscriptions.findByIdOrNull(institutionId.value)?.toRow()

    override fun ensure(institutionId: InstitutionId, trialEndsOn: LocalDate, now: Instant): SubscriptionRow {
        subscriptions.insertTrialIfAbsent(institutionId.value, trialEndsOn, now)
        return find(institutionId)!!
    }

    private fun update(institutionId: InstitutionId, change: InstitutionSubscriptionEntity.() -> Unit) {
        val e = subscriptions.findByIdOrNull(institutionId.value) ?: return
        e.change()
        subscriptions.save(e)
    }

    /** 유료 전환·해지 시점 기록 (SAAS-007 지표) */
    private fun InstitutionSubscriptionEntity.stamp(st: SubscriptionStatus, now: Instant) {
        if (st == SubscriptionStatus.ACTIVE) activatedAt = activatedAt ?: now
        canceledAt = if (st == SubscriptionStatus.CANCELED) canceledAt ?: now else null
    }

    override fun changePlan(institutionId: InstitutionId, planCode: String, status: SubscriptionStatus, nextBillingDate: LocalDate?, now: Instant) = update(institutionId) {
        this.planCode = planCode
        this.status = status.name
        this.nextBillingDate = nextBillingDate
        trialEndsOn = null
        cancelAtPeriodEnd = false
        updatedAt = now
        stamp(status, now)
    }

    override fun setCard(institutionId: InstitutionId, brand: String, last4: String, now: Instant) = update(institutionId) {
        cardBrand = brand; cardLast4 = last4; cardUpdatedAt = now; updatedAt = now
    }

    override fun setCancelAtPeriodEnd(institutionId: InstitutionId, cancel: Boolean, now: Instant) = update(institutionId) {
        cancelAtPeriodEnd = cancel; updatedAt = now
    }

    override fun payments(institutionId: InstitutionId, limit: Int): List<SubscriptionPaymentView> =
        payments.findByInstitutionIdOrderByCreatedAtDesc(institutionId.value, PageRequest.of(0, limit)).map {
            SubscriptionPaymentView(it.id, it.planCode, it.amount, it.status, it.periodStart, it.periodEnd, it.paidAt, it.failureReason)
        }

    override fun setStatus(institutionId: InstitutionId, status: SubscriptionStatus, nextBillingDate: LocalDate?, now: Instant) = update(institutionId) {
        this.status = status.name
        if (nextBillingDate != null) this.nextBillingDate = nextBillingDate
        updatedAt = now
        stamp(status, now)
    }

    override fun cancelDue(today: LocalDate, now: Instant): Int = subscriptions.cancelDue(today, now)

    override fun studentCount(institutionId: InstitutionId): Int = students.countByInstitutionIdAndStatusNot(institutionId.value, "WITHDRAWN").toInt()
}
