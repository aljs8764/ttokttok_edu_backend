package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.institution.InstitutionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Phase 2 ⑤ SaaS 운영 백오피스 — 운영사(platform_admin) 전용. 기관 헤더 없이 동작한다.

// ───────── SAAS-001 기관 ─────────
enum class ServiceStatus { PENDING, ACTIVE, SUSPENDED }

data class PlatformInstitutionRow(
    val id: UUID,
    val name: String,
    val ownerName: String,
    val type: String,
    val serviceStatus: ServiceStatus,
    val statusReason: String?,
    val planCode: String?,
    val subscriptionStatus: String?,
    val studentCount: Int,
    val voucherCode: String?,
    val createdAt: Instant,
    /** SAAS-008 매핑된 메뉴 그룹 (없으면 모든 메뉴) */
    val menuGroupId: UUID? = null,
    val menuGroupName: String? = null,
)

data class PlatformInstitutionDetail(
    val row: PlatformInstitutionRow,
    val nextBillingDate: LocalDate?,
    val trialEndsOn: LocalDate?,
    val cancelAtPeriodEnd: Boolean,
    val cardBrand: String?,
    val cardLast4: String?,
    val staffCount: Int,
    val recentPayments: List<PlatformPaymentView>,
    /** 운영사가 만든 기관의 가장 최근 원장 초대 (없으면 null) */
    val ownerInvitation: OwnerInvitationView? = null,
)

data class OwnerInvitationView(val email: String, val name: String, val status: String, val expiresAt: Instant, val acceptedAt: Instant?)

data class CreateInstitution(val institutionName: String, val type: InstitutionType, val ownerName: String, val ownerEmail: String)

interface PlatformInstitutionUseCase {
    fun list(actor: UserId, status: ServiceStatus?, keyword: String?): List<PlatformInstitutionRow>
    fun detail(actor: UserId, institutionId: UUID): PlatformInstitutionDetail
    fun approve(actor: UserId, institutionId: UUID): PlatformInstitutionRow
    fun suspend(actor: UserId, institutionId: UUID, reason: String): PlatformInstitutionRow
    /** 운영사가 기관을 직접 만들고 원장에게 초대 메일을 보낸다. 원장이 링크에서 수락하면 OWNER 계정이 된다 */
    fun create(actor: UserId, command: CreateInstitution): PlatformInstitutionDetail
    /** 원장 초대 다시 보내기 — 대기 중인 초대는 취소하고 새 링크(7일)를 보낸다 */
    fun resendOwnerInvitation(actor: UserId, institutionId: UUID): PlatformInstitutionDetail
    /** 운영사가 구독 상태를 직접 바꿈 (플랜·상태·다음 결제일) */
    fun overrideSubscription(actor: UserId, institutionId: UUID, planCode: String, status: SubscriptionStatus, nextBillingDate: LocalDate?): PlatformInstitutionDetail
}

// ───────── SAAS-002 구독 결제 ─────────
data class PlatformPaymentView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val planCode: String,
    val amount: Int,
    val status: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val paidAt: Instant?,
    val failureReason: String?,
    val createdAt: Instant,
)

interface PlatformBillingUseCase {
    data class Record(val planCode: String, val amount: Int, val paid: Boolean, val periodStart: LocalDate, val periodEnd: LocalDate, val failureReason: String?)

    fun payments(actor: UserId, status: String?, month: YearMonth?): List<PlatformPaymentView>
    /** 결제 결과 기록: 성공이면 구독 ACTIVE + 다음 결제일, 실패면 PAST_DUE */
    fun record(actor: UserId, institutionId: UUID, command: Record): PlatformPaymentView
    fun retry(actor: UserId, paymentId: UUID): PlatformPaymentView
    fun refund(actor: UserId, paymentId: UUID): PlatformPaymentView
    /** 스케줄러: "기간 종료 시 해지"를 걸어 둔 구독 중 다음 결제일이 지난 건을 해지 처리 */
    fun processCancellations(today: LocalDate): Int
}

// ───────── SAAS-003 바우처 ─────────
data class VoucherView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val voucherCode: String,
    val supportRatio: Int,
    val status: String,
    val startedOn: LocalDate?,
    val endedOn: LocalDate?,
    val memo: String?,
)

data class VoucherSettlementView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val periodMonth: String,
    val billedAmount: Int,
    val supportAmount: Int,
    val status: String,
    val settledOn: LocalDate?,
    val memo: String?,
)

interface VoucherUseCase {
    data class Save(val voucherCode: String, val supportRatio: Int, val status: String, val startedOn: LocalDate?, val endedOn: LocalDate?, val memo: String?)

    fun list(actor: UserId): List<VoucherView>
    fun save(actor: UserId, institutionId: UUID, command: Save): VoucherView
    fun remove(actor: UserId, institutionId: UUID)
    fun settlements(actor: UserId, month: YearMonth): List<VoucherSettlementView>
    /** 그 달의 활성 바우처 기관 정산 대상을 만든다 (이미 있으면 건너뜀). 만든 건수 */
    fun generateSettlements(actor: UserId, month: YearMonth): Int
    fun settle(actor: UserId, id: UUID, memo: String?): VoucherSettlementView
}

// ───────── SAAS-004 CS 티켓 ─────────
enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }
enum class TicketCategory(val label: String) { GENERAL("일반 문의"), BILLING("결제·요금"), BUG("오류 신고"), FEATURE("기능 제안") }

data class TicketMessageView(val id: UUID, val authorName: String, val fromOperator: Boolean, val body: String, val createdAt: Instant)

data class TicketView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val createdByName: String,
    val subject: String,
    val category: TicketCategory,
    val status: TicketStatus,
    val assigneeName: String?,
    val lastMessageAt: Instant,
    val createdAt: Instant,
    val messages: List<TicketMessageView>?,
)

/** 기관(원장·실장) 쪽 */
interface InstitutionSupportUseCase {
    fun open(actor: UserId, institutionId: InstitutionId, subject: String, category: TicketCategory, body: String): TicketView
    fun list(actor: UserId, institutionId: InstitutionId): List<TicketView>
    fun get(actor: UserId, institutionId: InstitutionId, id: UUID): TicketView
    fun reply(actor: UserId, institutionId: InstitutionId, id: UUID, body: String): TicketView
}

/** 운영사 쪽 */
interface PlatformSupportUseCase {
    fun list(actor: UserId, status: TicketStatus?, institutionId: UUID?): List<TicketView>
    fun get(actor: UserId, id: UUID): TicketView
    fun reply(actor: UserId, id: UUID, body: String): TicketView
    /** 상태 변경·담당자 지정(assign=true 면 본인에게) */
    fun update(actor: UserId, id: UUID, status: TicketStatus?, assignToMe: Boolean?): TicketView
}

// ───────── SAAS-005 공지/점검 ─────────
enum class PlatformNoticeKind { NOTICE, MAINTENANCE }

data class PlatformNoticeView(
    val id: UUID,
    val kind: PlatformNoticeKind,
    val title: String,
    val body: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val cancelled: Boolean,
    val createdAt: Instant,
)

interface PlatformNoticeUseCase {
    fun list(actor: UserId): List<PlatformNoticeView>
    fun create(actor: UserId, kind: PlatformNoticeKind, title: String, body: String, startsAt: Instant?, endsAt: Instant?): PlatformNoticeView
    fun cancel(actor: UserId, id: UUID): PlatformNoticeView
}

/** 모든 로그인 사용자의 화면 상단 배너용 */
interface ActivePlatformNoticeQuery {
    fun active(): List<PlatformNoticeView>
}

// ───────── SAAS-007 지표 ─────────
data class MetricsMonth(
    val month: String,
    val newPaid: Int,
    val churned: Int,
    val activeAtEnd: Int,
    val mrr: Long,
)

data class PlatformMetrics(
    val month: String,
    val mrr: Long,
    val arr: Long,
    val payingInstitutions: Int,
    val trialInstitutions: Int,
    val newPaid: Int,
    val churned: Int,
    /** 월초 유료 기관 대비 해지 비율(%). 월초 유료가 없으면 null */
    val churnRate: Double?,
    val marketingCost: Long?,
    /** 마케팅 비용 ÷ 신규 유료. 비용 미입력이거나 신규가 없으면 null */
    val cac: Long?,
    val trend: List<MetricsMonth>,
)

interface PlatformMetricsUseCase {
    fun metrics(actor: UserId, month: YearMonth): PlatformMetrics
    fun setMarketingCost(actor: UserId, month: YearMonth, amount: Long)
}

/** 운영사 여부 */
interface PlatformAccessQuery {
    fun isPlatformAdmin(userId: UserId): Boolean
}

// ───────── SAAS-009 운영사 계정 관리 ─────────
data class PlatformAdminView(val userId: UUID, val name: String, val email: String?, val createdAt: Instant)

interface PlatformAdminUseCase {
    fun list(actor: UserId): List<PlatformAdminView>
    /** 이미 가입한 계정(이메일)을 운영사로 지정 */
    fun add(actor: UserId, email: String): PlatformAdminView
    /** 자기 자신은 해제할 수 없다 (그래서 운영사가 0명이 되는 일은 없다) */
    fun remove(actor: UserId, userId: UUID)
}

// ───────── SAAS-008 기관별 메뉴 그룹 ─────────
data class MenuGroupInstitution(val id: UUID, val name: String)

data class MenuGroupView(
    val id: UUID,
    val name: String,
    val description: String?,
    /** 끈 메뉴 키 (관리자 웹 경로). 비어 있으면 모든 메뉴 */
    val disabledMenus: Set<String>,
    val institutions: List<MenuGroupInstitution>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface MenuGroupUseCase {
    data class Command(val name: String, val description: String?, val disabledMenus: Set<String>)

    fun list(actor: UserId): List<MenuGroupView>
    fun create(actor: UserId, command: Command): MenuGroupView
    fun update(actor: UserId, id: UUID, command: Command): MenuGroupView
    /** 지우면 매핑된 기관은 모든 메뉴로 돌아간다 */
    fun delete(actor: UserId, id: UUID)
    fun replaceInstitutions(actor: UserId, id: UUID, institutionIds: Set<UUID>): MenuGroupView
    fun assign(actor: UserId, institutionId: UUID, groupId: UUID?)
}

/** 기관 화면용: 이 기관에서 끈 메뉴 (구성원이면 상태와 무관하게 조회) */
data class InstitutionMenusView(val menuGroupId: UUID?, val menuGroupName: String?, val disabledMenus: Set<String>)

interface InstitutionMenusQuery {
    fun menus(actor: UserId, institutionId: InstitutionId): InstitutionMenusView
}
