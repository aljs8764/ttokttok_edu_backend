package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Phase 2 ⑤ SaaS 운영 백오피스 (V18), SAAS-008 메뉴 그룹 (V27)

/** 운영사(슈퍼관리자) 계정 */
@Entity @Table(name = "platform_admin")
class PlatformAdminEntity(
    @Id var userId: UUID,
    var createdAt: Instant,
)

@Entity @Table(name = "menu_group")
class MenuGroupEntity(
    @Id var id: UUID,
    var name: String,
    var description: String?,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** 그룹에서 끈 메뉴 (메뉴 키 = 관리자 웹 경로) */
@Entity @Table(name = "menu_group_disabled_menu") @IdClass(MenuGroupDisabledMenuKey::class)
class MenuGroupDisabledMenuEntity(
    @Id var menuGroupId: UUID,
    @Id var menuKey: String,
)

data class MenuGroupDisabledMenuKey(var menuGroupId: UUID? = null, var menuKey: String? = null) : Serializable

/** 기관당 메뉴 그룹 하나 */
@Entity @Table(name = "institution_menu_group")
class InstitutionMenuGroupEntity(
    @Id var institutionId: UUID,
    var menuGroupId: UUID,
    var assignedAt: Instant,
)

/** SAAS-003 K-비대면 바우처 (기관당 하나) */
@Entity @Table(name = "voucher_registration")
class VoucherRegistrationEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var voucherCode: String,
    var supportRatio: Int,
    var status: String = "ACTIVE",
    var startedOn: LocalDate?,
    var endedOn: LocalDate?,
    var memo: String?,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** 바우처 월별 정산 (period_month = YYYY-MM, char(7)) */
@Entity @Table(name = "voucher_settlement")
class VoucherSettlementEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    @JdbcTypeCode(SqlTypes.CHAR) var periodMonth: String,
    var billedAmount: Int,
    var supportAmount: Int,
    var status: String = "PENDING",
    var settledOn: LocalDate? = null,
    var memo: String? = null,
    var createdAt: Instant,
)

/** SAAS-004 CS/문의 티켓 */
@Entity @Table(name = "support_ticket")
class SupportTicketEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var createdBy: UUID,
    var subject: String,
    var category: String,
    var status: String = "OPEN",
    var assigneeId: UUID? = null,
    var lastMessageAt: Instant,
    var createdAt: Instant,
    var updatedAt: Instant,
)

@Entity @Table(name = "support_message")
class SupportMessageEntity(
    @Id var id: UUID,
    var ticketId: UUID,
    var authorId: UUID,
    var fromOperator: Boolean,
    var body: String,
    var createdAt: Instant,
)

/** SAAS-005 공지/점검 */
@Entity @Table(name = "platform_notice")
class PlatformNoticeEntity(
    @Id var id: UUID,
    var kind: String,
    var title: String,
    var body: String,
    var startsAt: Instant,
    var endsAt: Instant?,
    var cancelled: Boolean = false,
    var createdBy: UUID,
    var createdAt: Instant,
)

/** SAAS-007 CAC 계산용 월별 마케팅·영업 비용 (month = YYYY-MM, char(7)) */
@Entity @Table(name = "platform_marketing_cost")
class PlatformMarketingCostEntity(
    @Id @JdbcTypeCode(SqlTypes.CHAR) var month: String,
    var amount: Long,
    var updatedBy: UUID,
    var updatedAt: Instant,
)
