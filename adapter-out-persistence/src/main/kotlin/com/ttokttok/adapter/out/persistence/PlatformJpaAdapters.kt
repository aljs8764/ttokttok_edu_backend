package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.InstitutionEntity
import com.ttokttok.adapter.out.persistence.entity.InstitutionMenuGroupEntity
import com.ttokttok.adapter.out.persistence.entity.MenuGroupDisabledMenuEntity
import com.ttokttok.adapter.out.persistence.entity.MenuGroupEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformAdminEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformMarketingCostEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformNoticeEntity
import com.ttokttok.adapter.out.persistence.entity.SubscriptionPaymentEntity
import com.ttokttok.adapter.out.persistence.entity.SupportMessageEntity
import com.ttokttok.adapter.out.persistence.entity.SupportTicketEntity
import com.ttokttok.adapter.out.persistence.entity.VoucherRegistrationEntity
import com.ttokttok.adapter.out.persistence.entity.VoucherSettlementEntity
import com.ttokttok.adapter.out.persistence.repository.InstitutionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InstitutionMenuGroupJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MembershipJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MenuGroupDisabledMenuJpaRepository
import com.ttokttok.adapter.out.persistence.repository.MenuGroupJpaRepository
import com.ttokttok.adapter.out.persistence.repository.PlatformAdminJpaRepository
import com.ttokttok.adapter.out.persistence.repository.PlatformMarketingCostJpaRepository
import com.ttokttok.adapter.out.persistence.repository.PlatformNoticeJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SubscriptionPaymentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SupportMessageJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SupportTicketJpaRepository
import com.ttokttok.adapter.out.persistence.repository.VoucherRegistrationJpaRepository
import com.ttokttok.adapter.out.persistence.repository.VoucherSettlementJpaRepository
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

// ───────── 운영사 계정 / 서비스 상태 ─────────
@Component
class PlatformAdminJpaAdapter(
    private val admins: PlatformAdminJpaRepository,
    private val institutions: InstitutionJpaRepository,
) : PlatformAdminPort, InstitutionServicePort {

    override fun isAdmin(userId: UserId): Boolean = admins.existsById(userId.value)

    override fun hasAny(): Boolean = admins.count() > 0

    override fun grant(userId: UserId) {
        admins.insertIfAbsent(userId.value, Instant.now())
    }

    override fun list(): List<PlatformAdminView> =
        admins.findViews().map { PlatformAdminView(it[0] as UUID, it[1] as String, it[2] as String?, it[3] as Instant) }

    override fun add(userId: UserId, now: Instant) {
        admins.insertIfAbsent(userId.value, now)
    }

    override fun remove(userId: UserId) {
        if (admins.existsById(userId.value)) admins.deleteById(userId.value)
    }

    override fun status(institutionId: InstitutionId): ServiceStatus =
        institutions.findByIdOrNull(institutionId.value)?.let { ServiceStatus.valueOf(it.serviceStatus) } ?: ServiceStatus.ACTIVE
}

// ───────── SAAS-008 메뉴 그룹 ─────────
@Component
class MenuGroupJpaAdapter(
    private val groups: MenuGroupJpaRepository,
    private val disabledMenus: MenuGroupDisabledMenuJpaRepository,
    private val assignments: InstitutionMenuGroupJpaRepository,
    private val q: JpaQueries,
) : MenuGroupPort {

    private fun load(heads: List<MenuGroupEntity>): List<MenuGroupView> {
        if (heads.isEmpty()) return emptyList()
        val ids = heads.map { it.id }
        val disabled = disabledMenus.findByMenuGroupIdIn(ids).groupBy({ it.menuGroupId }, { it.menuKey })
        val insts = assignments.findInstitutions(ids).groupBy({ it[0] as UUID }, { MenuGroupInstitution(it[1] as UUID, it[2] as String) })
        return heads.map { h ->
            MenuGroupView(h.id, h.name, h.description, disabled[h.id].orEmpty().toSortedSet(), insts[h.id].orEmpty(), h.createdAt, h.updatedAt)
        }
    }

    override fun list(): List<MenuGroupView> = load(groups.findAllByOrderByName())

    override fun find(id: UUID): MenuGroupView? = load(listOfNotNull(groups.findByIdOrNull(id))).firstOrNull()

    override fun existsByName(name: String, exceptId: UUID?): Boolean {
        val p = params("n" to name)
        val except = if (exceptId != null) {
            p["ex"] = exceptId
            " and g.id <> :ex"
        } else ""
        return q.jpqlCount("select count(g) from MenuGroupEntity g where lower(g.name) = lower(:n)$except", p) > 0
    }

    override fun save(id: UUID, name: String, description: String?, disabledMenus: Set<String>, now: Instant) {
        val g = groups.findByIdOrNull(id) ?: MenuGroupEntity(id, name, description, now, now)
        g.name = name
        g.description = description
        g.updatedAt = now
        groups.save(g)
        this.disabledMenus.deleteByGroup(id)
        disabledMenus.forEach { key -> q.persist(MenuGroupDisabledMenuEntity(id, key)) }
    }

    override fun delete(id: UUID) {
        groups.deleteGroup(id)
    }

    override fun replaceInstitutions(groupId: UUID, institutionIds: Set<UUID>, now: Instant) {
        assignments.deleteByGroup(groupId)
        institutionIds.forEach { assign(it, groupId, now) }
    }

    override fun assign(institutionId: UUID, groupId: UUID?, now: Instant) {
        if (groupId == null) {
            if (assignments.existsById(institutionId)) assignments.deleteById(institutionId)
            return
        }
        val a = assignments.findByIdOrNull(institutionId) ?: InstitutionMenuGroupEntity(institutionId, groupId, now)
        a.menuGroupId = groupId
        a.assignedAt = now
        assignments.save(a)
    }

    override fun findByInstitution(institutionId: UUID): MenuGroupView? {
        val groupId = assignments.findByIdOrNull(institutionId)?.menuGroupId ?: return null
        return find(groupId)
    }
}

// ───────── SAAS-001 기관 ─────────
@Component
class PlatformInstitutionJpaAdapter(
    private val institutions: InstitutionJpaRepository,
    private val memberships: MembershipJpaRepository,
    private val q: JpaQueries,
) : PlatformInstitutionPort {

    private companion object {
        const val SELECT = """
            select i, s.planCode, s.status,
                   (select count(st) from StudentEntity st where st.institutionId = i.id and st.status <> 'WITHDRAWN'),
                   v.voucherCode, mg.id, mg.name
              from InstitutionEntity i
              left join InstitutionSubscriptionEntity s on s.institutionId = i.id
              left join VoucherRegistrationEntity v on v.institutionId = i.id and v.status = 'ACTIVE'
              left join InstitutionMenuGroupEntity img on img.institutionId = i.id
              left join MenuGroupEntity mg on mg.id = img.menuGroupId
        """

        fun row(r: Array<Any?>): PlatformInstitutionRow {
            val i = r[0] as InstitutionEntity
            return PlatformInstitutionRow(
                i.id, i.name, i.ownerName, i.type,
                ServiceStatus.valueOf(i.serviceStatus), i.statusReason,
                r[1] as String?, r[2] as String?, (r[3] as Number).toInt(),
                r[4] as String?, i.createdAt,
                r[5] as UUID?, r[6] as String?,
            )
        }
    }

    override fun list(status: ServiceStatus?, keyword: String?): List<PlatformInstitutionRow> {
        val kw = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val p = params()
        val where = StringBuilder(" where 1 = 1")
        if (status != null) { where.append(" and i.serviceStatus = :st"); p["st"] = status.name }
        if (kw != null) {
            where.append(" and (lower(i.name) like :kw or lower(i.ownerName) like :kw)")
            p["kw"] = "%" + kw.lowercase() + "%"
        }
        return q.jpqlRows("$SELECT$where order by i.createdAt desc", p, limit = 500).map(::row)
    }

    override fun find(id: UUID): PlatformInstitutionRow? = q.jpqlRows("$SELECT where i.id = :id", params("id" to id)).firstOrNull()?.let(::row)

    override fun setStatus(id: UUID, status: ServiceStatus, reason: String?, now: Instant) {
        institutions.setServiceStatus(id, status.name, reason, now, sampleOnly = false)
    }

    override fun staffCount(id: UUID): Int = memberships.countByInstitutionIdAndRoleNot(id, "PARENT").toInt()

    override fun managers(id: UUID): List<UserId> = memberships.findByInstitutionIdAndRoleIn(id, listOf("OWNER", "ADMIN")).map { UserId(it.userId) }
}

// ───────── SAAS-002 구독 결제 ─────────
@Component
class PlatformPaymentJpaAdapter(
    private val payments: SubscriptionPaymentJpaRepository,
    private val q: JpaQueries,
) : PlatformPaymentPort {

    private companion object {
        const val SELECT = "select p, i.name from SubscriptionPaymentEntity p join InstitutionEntity i on i.id = p.institutionId"

        fun view(r: Array<Any?>): PlatformPaymentView {
            val p = r[0] as SubscriptionPaymentEntity
            return PlatformPaymentView(
                p.id, p.institutionId, r[1] as String, p.planCode, p.amount,
                p.status, p.periodStart, p.periodEnd, p.paidAt, p.failureReason,
                p.createdAt,
            )
        }
    }

    /** 날짜 경계는 예전 SQL(timestamptz >= date) 과 같게 세션 시간대(= JVM 기본 시간대)의 자정 */
    private fun LocalDate.startInstant(): Instant = atStartOfDay(ZoneId.systemDefault()).toInstant()

    override fun list(status: String?, from: LocalDate?, to: LocalDate?): List<PlatformPaymentView> {
        val prm = params()
        val where = StringBuilder(" where 1 = 1")
        if (status != null) { where.append(" and p.status = :st"); prm["st"] = status }
        if (from != null) { where.append(" and p.createdAt >= :from"); prm["from"] = from.startInstant() }
        if (to != null) { where.append(" and p.createdAt < :to"); prm["to"] = to.startInstant() }
        return q.jpqlRows("$SELECT$where order by p.createdAt desc", prm, limit = 500).map(::view)
    }

    override fun find(id: UUID): PlatformPaymentView? = q.jpqlRows("$SELECT where p.id = :id", params("id" to id)).firstOrNull()?.let(::view)

    override fun recent(institutionId: UUID, limit: Int): List<PlatformPaymentView> =
        q.jpqlRows("$SELECT where p.institutionId = :i order by p.createdAt desc", params("i" to institutionId), limit = limit).map(::view)

    override fun insert(institutionId: UUID, planCode: String, amount: Int, status: String, periodStart: LocalDate, periodEnd: LocalDate, paidAt: Instant?, failureReason: String?, now: Instant): UUID {
        val id = Uuid7.next()
        payments.save(SubscriptionPaymentEntity(id, institutionId, planCode, amount, status, periodStart, periodEnd, paidAt, failureReason, createdAt = now))
        return id
    }

    override fun update(id: UUID, status: String, paidAt: Instant?, failureReason: String?) {
        val e = payments.findByIdOrNull(id) ?: return
        e.status = status
        e.paidAt = paidAt
        e.failureReason = failureReason
        payments.save(e)
    }
}

// ───────── SAAS-003 바우처 ─────────
@Component
class VoucherJpaAdapter(
    private val vouchers: VoucherRegistrationJpaRepository,
    private val settlements: VoucherSettlementJpaRepository,
    private val q: JpaQueries,
) : VoucherPort {

    private companion object {
        const val SELECT = "select v, i.name from VoucherRegistrationEntity v join InstitutionEntity i on i.id = v.institutionId"
        const val SETTLEMENT_SELECT = "select s, i.name from VoucherSettlementEntity s join InstitutionEntity i on i.id = s.institutionId"

        fun view(r: Array<Any?>): VoucherView {
            val v = r[0] as VoucherRegistrationEntity
            return VoucherView(v.id, v.institutionId, r[1] as String, v.voucherCode, v.supportRatio, v.status, v.startedOn, v.endedOn, v.memo)
        }

        fun settlement(r: Array<Any?>): VoucherSettlementView {
            val s = r[0] as VoucherSettlementEntity
            return VoucherSettlementView(s.id, s.institutionId, r[1] as String, s.periodMonth, s.billedAmount, s.supportAmount, s.status, s.settledOn, s.memo)
        }
    }

    override fun list(): List<VoucherView> = q.jpqlRows("$SELECT order by i.name").map(::view)

    override fun find(institutionId: UUID): VoucherView? = q.jpqlRows("$SELECT where v.institutionId = :i", params("i" to institutionId)).firstOrNull()?.let(::view)

    override fun upsert(institutionId: UUID, command: VoucherUseCase.Save, now: Instant) {
        val v = vouchers.findByInstitutionId(institutionId)
            ?: VoucherRegistrationEntity(Uuid7.next(), institutionId, command.voucherCode, command.supportRatio, command.status, command.startedOn, command.endedOn, command.memo, now, now)
        v.voucherCode = command.voucherCode
        v.supportRatio = command.supportRatio
        v.status = command.status
        v.startedOn = command.startedOn
        v.endedOn = command.endedOn
        v.memo = command.memo
        v.updatedAt = now
        vouchers.save(v)
    }

    override fun delete(institutionId: UUID) {
        vouchers.deleteByInstitution(institutionId)
    }

    override fun settlements(month: String): List<VoucherSettlementView> =
        q.jpqlRows("$SETTLEMENT_SELECT where s.periodMonth = :m order by i.name", params("m" to month)).map(::settlement)

    override fun findSettlement(id: UUID): VoucherSettlementView? =
        q.jpqlRows("$SETTLEMENT_SELECT where s.id = :id", params("id" to id)).firstOrNull()?.let(::settlement)

    override fun billing(month: YearMonth): List<VoucherBilling> =
        q.jpqlRows(
            """select v.institutionId, v.supportRatio, coalesce(pl.monthlyPrice, 0)
                 from VoucherRegistrationEntity v
                 join InstitutionSubscriptionEntity s on s.institutionId = v.institutionId
                 join SubscriptionPlanEntity pl on pl.code = s.planCode
                where (v.startedOn is null or v.startedOn <= :last)
                  and (v.endedOn is null or v.endedOn >= :first)
                  and (v.status = 'ACTIVE' or v.endedOn is not null)""",
            params("first" to month.atDay(1), "last" to month.atEndOfMonth()),
        ).map { VoucherBilling(it[0] as UUID, (it[1] as Number).toInt(), (it[2] as Number).toInt()) }

    override fun insertSettlement(institutionId: UUID, month: String, billed: Int, support: Int, now: Instant): Boolean =
        settlements.insertIfAbsent(Uuid7.next(), institutionId, month, billed, support, now) > 0

    override fun settle(id: UUID, on: LocalDate, memo: String?) {
        val s = settlements.findByIdOrNull(id) ?: return
        s.status = "SETTLED"
        s.settledOn = on
        if (memo != null) s.memo = memo
        settlements.save(s)
    }
}

// ───────── SAAS-004 CS 티켓 ─────────
@Component
class SupportTicketJpaAdapter(
    private val tickets: SupportTicketJpaRepository,
    private val messages: SupportMessageJpaRepository,
    private val q: JpaQueries,
) : SupportTicketPort {

    private companion object {
        const val SELECT = """
            select t, i.name, cu.name, au.name
              from SupportTicketEntity t
              join InstitutionEntity i on i.id = t.institutionId
              join UserEntity cu on cu.id = t.createdBy
              left join UserEntity au on au.id = t.assigneeId
        """

        fun view(r: Array<Any?>): TicketView {
            val t = r[0] as SupportTicketEntity
            return TicketView(
                t.id, t.institutionId, r[1] as String, r[2] as String, t.subject,
                TicketCategory.valueOf(t.category), TicketStatus.valueOf(t.status), r[3] as String?,
                t.lastMessageAt, t.createdAt, null,
            )
        }
    }

    override fun insert(institutionId: UUID, createdBy: UserId, subject: String, category: TicketCategory, now: Instant): UUID {
        val id = Uuid7.next()
        tickets.save(SupportTicketEntity(id, institutionId, createdBy.value, subject, category.name, lastMessageAt = now, createdAt = now, updatedAt = now))
        return id
    }

    override fun addMessage(ticketId: UUID, author: UserId, fromOperator: Boolean, body: String, now: Instant) {
        messages.save(SupportMessageEntity(Uuid7.next(), ticketId, author.value, fromOperator, body, now))
        tickets.touch(ticketId, now)
    }

    override fun find(id: UUID): TicketView? = q.jpqlRows("$SELECT where t.id = :id", params("id" to id)).firstOrNull()?.let(::view)

    override fun messages(ticketId: UUID): List<TicketMessageView> =
        messages.findViews(ticketId).map { r ->
            val m = r[0] as SupportMessageEntity
            TicketMessageView(m.id, r[1] as String, m.fromOperator, m.body, m.createdAt)
        }

    override fun list(institutionId: UUID?, status: TicketStatus?): List<TicketView> {
        val p = params()
        val where = StringBuilder(" where 1 = 1")
        if (institutionId != null) { where.append(" and t.institutionId = :i"); p["i"] = institutionId }
        if (status != null) { where.append(" and t.status = :st"); p["st"] = status.name }
        return q.jpqlRows("$SELECT$where order by t.lastMessageAt desc", p, limit = 300).map(::view)
    }

    override fun update(id: UUID, status: TicketStatus?, assignee: UserId?, now: Instant) {
        val t = tickets.findByIdOrNull(id) ?: return
        if (status != null) t.status = status.name
        if (assignee != null) t.assigneeId = assignee.value
        t.updatedAt = now
        tickets.save(t)
    }

    override fun creator(id: UUID): UUID? = tickets.findByIdOrNull(id)?.createdBy
}

// ───────── SAAS-005 공지/점검 ─────────
@Component
class PlatformNoticeJpaAdapter(private val repo: PlatformNoticeJpaRepository) : PlatformNoticePort {

    private fun PlatformNoticeEntity.toView() = PlatformNoticeView(
        id, PlatformNoticeKind.valueOf(kind), title, body, startsAt, endsAt, cancelled, createdAt,
    )

    override fun insert(kind: PlatformNoticeKind, title: String, body: String, startsAt: Instant, endsAt: Instant?, by: UserId, now: Instant): UUID {
        val id = Uuid7.next()
        repo.save(PlatformNoticeEntity(id, kind.name, title, body, startsAt, endsAt, createdBy = by.value, createdAt = now))
        return id
    }

    override fun find(id: UUID): PlatformNoticeView? = repo.findByIdOrNull(id)?.toView()

    override fun list(): List<PlatformNoticeView> = repo.findAllByOrderByStartsAtDesc(PageRequest.of(0, 200)).map { it.toView() }

    override fun cancel(id: UUID) {
        val e = repo.findByIdOrNull(id) ?: return
        e.cancelled = true
        repo.save(e)
    }

    override fun active(now: Instant): List<PlatformNoticeView> = repo.findActive(now).map { it.toView() }
}

// ───────── SAAS-007 지표 ─────────
@Component
// ONB-003: 샘플(체험) 기관은 지표에서 뺀다
class PlatformMetricsJpaAdapter(private val q: JpaQueries, private val costs: PlatformMarketingCostJpaRepository) : PlatformMetricsPort {

    override fun monthStats(start: Instant, end: Instant): MonthStats {
        val prm = params("start" to start, "end" to end)
        val newPaid = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at >= :start and activated_at < :end", prm).toInt()
        val churned = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at is not null and canceled_at >= :start and canceled_at < :end", prm).toInt()
        val activeAtStart = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at < :start and (canceled_at is null or canceled_at >= :start)", prm).toInt()
        val activeAtEnd = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and activated_at < :end and (canceled_at is null or canceled_at >= :end)", prm).toInt()
        // 과거 요금 이력이 없어 현재 플랜 가격으로 근사한다
        val mrr = q.count("""select coalesce(sum(pl.monthly_price), 0) from institution_subscription s join subscription_plan pl on pl.code = s.plan_code
                where s.institution_id not in (select id from institution where is_sample) and s.activated_at < :end and (s.canceled_at is null or s.canceled_at >= :end)""", prm)
        return MonthStats(newPaid, churned, activeAtStart, activeAtEnd, mrr)
    }

    override fun currentCounts(): Pair<Int, Int> {
        val paying = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and status in ('ACTIVE', 'PAST_DUE')").toInt()
        val trial = q.count("select count(*) from institution_subscription where institution_id not in (select id from institution where is_sample) and status = 'TRIAL'").toInt()
        return paying to trial
    }

    override fun currentMrr(): Long =
        q.count("""select coalesce(sum(pl.monthly_price), 0) from institution_subscription s join subscription_plan pl on pl.code = s.plan_code
                where s.institution_id not in (select id from institution where is_sample) and s.status in ('ACTIVE', 'PAST_DUE')""")

    override fun marketingCost(month: String): Long? = costs.findByIdOrNull(month)?.amount

    override fun setMarketingCost(month: String, amount: Long, by: UserId, now: Instant) {
        val e = costs.findByIdOrNull(month) ?: PlatformMarketingCostEntity(month, amount, by.value, now)
        e.amount = amount
        e.updatedBy = by.value
        e.updatedAt = now
        costs.save(e)
    }
}
