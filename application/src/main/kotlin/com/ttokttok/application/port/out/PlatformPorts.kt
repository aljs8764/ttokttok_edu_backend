package com.ttokttok.application.port.out

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
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

interface PlatformAdminPort {
    fun isAdmin(userId: UserId): Boolean
    /** 운영사 계정이 하나라도 있는지 (기동 시 초기 계정 생성 판단) */
    fun hasAny(): Boolean
    /** 운영사로 지정. 이미 지정돼 있으면 아무 일도 하지 않는다 */
    fun grant(userId: UserId)
    fun list(): List<PlatformAdminView>
    fun add(userId: UserId, now: Instant)
    fun remove(userId: UserId)
}

/** SAAS-008 메뉴 그룹 저장소. 끈 메뉴(disabledMenus)와 기관 매핑을 함께 다룬다 */
interface MenuGroupPort {
    fun list(): List<MenuGroupView>
    fun find(id: UUID): MenuGroupView?
    fun existsByName(name: String, exceptId: UUID?): Boolean
    fun save(id: UUID, name: String, description: String?, disabledMenus: Set<String>, now: Instant)
    fun delete(id: UUID)
    /** 그룹에 매핑된 기관을 정확히 이 목록으로 맞춘다 (다른 그룹에 있던 기관은 옮겨 온다) */
    fun replaceInstitutions(groupId: UUID, institutionIds: Set<UUID>, now: Instant)
    /** null 이면 매핑 해제 → 모든 메뉴 */
    fun assign(institutionId: UUID, groupId: UUID?, now: Instant)
    fun findByInstitution(institutionId: UUID): MenuGroupView?
}

/** 기관 서비스 상태. AccessGuard 가 정지·승인 대기 기관의 업무 API 를 막는 데 쓴다 */
interface InstitutionServicePort {
    /** 기관이 없으면 ACTIVE 로 본다 (다른 곳에서 NotFound 처리) */
    fun status(institutionId: InstitutionId): ServiceStatus
}

interface PlatformInstitutionPort {
    fun list(status: ServiceStatus?, keyword: String?): List<PlatformInstitutionRow>
    fun find(id: UUID): PlatformInstitutionRow?
    fun setStatus(id: UUID, status: ServiceStatus, reason: String?, now: Instant)
    fun staffCount(id: UUID): Int
    /** 원장·실장 계정 */
    fun managers(id: UUID): List<UserId>
}

interface PlatformPaymentPort {
    fun list(status: String?, from: LocalDate?, to: LocalDate?): List<PlatformPaymentView>
    fun find(id: UUID): PlatformPaymentView?
    fun recent(institutionId: UUID, limit: Int): List<PlatformPaymentView>
    fun insert(institutionId: UUID, planCode: String, amount: Int, status: String, periodStart: LocalDate, periodEnd: LocalDate, paidAt: Instant?, failureReason: String?, now: Instant): UUID
    fun update(id: UUID, status: String, paidAt: Instant?, failureReason: String?)
}

data class VoucherBilling(val institutionId: UUID, val supportRatio: Int, val billed: Int)

interface VoucherPort {
    fun list(): List<VoucherView>
    fun find(institutionId: UUID): VoucherView?
    fun upsert(institutionId: UUID, command: VoucherUseCase.Save, now: Instant)
    fun delete(institutionId: UUID)
    fun settlements(month: String): List<VoucherSettlementView>
    fun findSettlement(id: UUID): VoucherSettlementView?
    /** 그 달에 효력이 있는 바우처 기관과 청구액(구독 월 요금) */
    fun billing(month: YearMonth): List<VoucherBilling>
    /** 이미 있으면 false */
    fun insertSettlement(institutionId: UUID, month: String, billed: Int, support: Int, now: Instant): Boolean
    fun settle(id: UUID, on: LocalDate, memo: String?)
}

interface SupportTicketPort {
    fun insert(institutionId: UUID, createdBy: UserId, subject: String, category: TicketCategory, now: Instant): UUID
    fun addMessage(ticketId: UUID, author: UserId, fromOperator: Boolean, body: String, now: Instant)
    fun find(id: UUID): TicketView?
    fun messages(ticketId: UUID): List<TicketMessageView>
    fun list(institutionId: UUID?, status: TicketStatus?): List<TicketView>
    fun update(id: UUID, status: TicketStatus?, assignee: UserId?, now: Instant)
    fun creator(id: UUID): UUID?
}

interface PlatformNoticePort {
    fun insert(kind: PlatformNoticeKind, title: String, body: String, startsAt: Instant, endsAt: Instant?, by: UserId, now: Instant): UUID
    fun find(id: UUID): PlatformNoticeView?
    fun list(): List<PlatformNoticeView>
    fun cancel(id: UUID)
    fun active(now: Instant): List<PlatformNoticeView>
}

data class MonthStats(val newPaid: Int, val churned: Int, val activeAtStart: Int, val activeAtEnd: Int, val mrrAtEnd: Long)

interface PlatformMetricsPort {
    fun monthStats(start: Instant, end: Instant): MonthStats
    fun currentCounts(): Pair<Int, Int>   // (유료 ACTIVE, 체험 TRIAL)
    fun currentMrr(): Long
    fun marketingCost(month: String): Long?
    fun setMarketingCost(month: String, amount: Long, by: UserId, now: Instant)
}
