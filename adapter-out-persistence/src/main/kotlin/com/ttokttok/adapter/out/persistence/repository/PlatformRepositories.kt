package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.InstitutionMenuGroupEntity
import com.ttokttok.adapter.out.persistence.entity.MenuGroupDisabledMenuEntity
import com.ttokttok.adapter.out.persistence.entity.MenuGroupDisabledMenuKey
import com.ttokttok.adapter.out.persistence.entity.MenuGroupEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformAdminEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformMarketingCostEntity
import com.ttokttok.adapter.out.persistence.entity.PlatformNoticeEntity
import com.ttokttok.adapter.out.persistence.entity.SupportMessageEntity
import com.ttokttok.adapter.out.persistence.entity.SupportTicketEntity
import com.ttokttok.adapter.out.persistence.entity.VoucherRegistrationEntity
import com.ttokttok.adapter.out.persistence.entity.VoucherSettlementEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface PlatformAdminJpaRepository : JpaRepository<PlatformAdminEntity, UUID> {
    /** (user id, 이름, 이메일, 지정 시각) */
    @Query("select u.id, u.name, u.email, a.createdAt from PlatformAdminEntity a join UserEntity u on u.id = a.userId order by a.createdAt")
    fun findViews(): List<Array<Any?>>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into platform_admin (user_id, created_at) values (:u, :now) on conflict (user_id) do nothing", nativeQuery = true)
    fun insertIfAbsent(@Param("u") userId: UUID, @Param("now") now: Instant): Int
}

interface MenuGroupJpaRepository : JpaRepository<MenuGroupEntity, UUID> {
    fun findAllByOrderByName(): List<MenuGroupEntity>

    /** menu_group_disabled_menu·institution_menu_group 은 DB 의 on delete cascade 로 함께 지워진다 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MenuGroupEntity g where g.id = :id")
    fun deleteGroup(@Param("id") id: UUID): Int
}

interface MenuGroupDisabledMenuJpaRepository : JpaRepository<MenuGroupDisabledMenuEntity, MenuGroupDisabledMenuKey> {
    fun findByMenuGroupIdIn(groupIds: Collection<UUID>): List<MenuGroupDisabledMenuEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MenuGroupDisabledMenuEntity d where d.menuGroupId = :g")
    fun deleteByGroup(@Param("g") groupId: UUID): Int
}

interface InstitutionMenuGroupJpaRepository : JpaRepository<InstitutionMenuGroupEntity, UUID> {
    /** (그룹 id, 기관 id, 기관명) */
    @Query(
        """select m.menuGroupId, i.id, i.name from InstitutionMenuGroupEntity m join InstitutionEntity i on i.id = m.institutionId
            where m.menuGroupId in (:ids) order by i.name""",
    )
    fun findInstitutions(@Param("ids") groupIds: Collection<UUID>): List<Array<Any?>>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InstitutionMenuGroupEntity m where m.menuGroupId = :g")
    fun deleteByGroup(@Param("g") groupId: UUID): Int
}

interface VoucherRegistrationJpaRepository : JpaRepository<VoucherRegistrationEntity, UUID> {
    fun findByInstitutionId(institutionId: UUID): VoucherRegistrationEntity?

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from VoucherRegistrationEntity v where v.institutionId = :i")
    fun deleteByInstitution(@Param("i") institutionId: UUID): Int
}

interface VoucherSettlementJpaRepository : JpaRepository<VoucherSettlementEntity, UUID> {
    /** 이미 그 달 정산이 있으면 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into voucher_settlement (id, institution_id, period_month, billed_amount, support_amount, created_at)
                   values (:id, :i, :m, :b, :s, :now) on conflict (institution_id, period_month) do nothing""",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID, @Param("i") institutionId: UUID, @Param("m") month: String, @Param("b") billed: Int,
        @Param("s") support: Int, @Param("now") now: Instant,
    ): Int
}

interface SupportTicketJpaRepository : JpaRepository<SupportTicketEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SupportTicketEntity t set t.lastMessageAt = :now, t.updatedAt = :now where t.id = :id")
    fun touch(@Param("id") id: UUID, @Param("now") now: Instant): Int
}

interface SupportMessageJpaRepository : JpaRepository<SupportMessageEntity, UUID> {
    /** 메시지 + 작성자명 */
    @Query("select m, u.name from SupportMessageEntity m join UserEntity u on u.id = m.authorId where m.ticketId = :t order by m.createdAt, m.id")
    fun findViews(@Param("t") ticketId: UUID): List<Array<Any?>>
}

interface PlatformNoticeJpaRepository : JpaRepository<PlatformNoticeEntity, UUID> {
    fun findAllByOrderByStartsAtDesc(pageable: Pageable): List<PlatformNoticeEntity>

    @Query(
        """select n from PlatformNoticeEntity n
            where n.cancelled = false and n.startsAt <= :now and (n.endsAt is null or n.endsAt > :now) order by n.startsAt desc""",
    )
    fun findActive(@Param("now") now: Instant): List<PlatformNoticeEntity>
}

interface PlatformMarketingCostJpaRepository : JpaRepository<PlatformMarketingCostEntity, String>
