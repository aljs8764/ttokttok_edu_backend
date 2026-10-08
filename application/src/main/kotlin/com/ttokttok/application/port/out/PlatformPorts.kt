package com.ttokttok.application.port.out

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
