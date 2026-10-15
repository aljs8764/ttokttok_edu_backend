package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.InstitutionSubscriptionEntity
import com.ttokttok.adapter.out.persistence.entity.InvoiceEntity
import com.ttokttok.adapter.out.persistence.entity.InvoiceReminderLogEntity
import com.ttokttok.adapter.out.persistence.entity.InvoiceReminderLogKey
import com.ttokttok.adapter.out.persistence.entity.SubscriptionPaymentEntity
import com.ttokttok.adapter.out.persistence.entity.SubscriptionPlanEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface InvoiceJpaRepository : JpaRepository<InvoiceEntity, UUID>

interface InvoiceReminderLogJpaRepository : JpaRepository<InvoiceReminderLogEntity, InvoiceReminderLogKey> {
    /** 이미 보낸 단계면 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "insert into invoice_reminder_log (invoice_id, stage, sent_at) values (:i, :s, :now) on conflict do nothing",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("i") invoiceId: UUID, @Param("s") stage: String, @Param("now") now: Instant): Int
}

interface SubscriptionPlanJpaRepository : JpaRepository<SubscriptionPlanEntity, String> {
    fun findAllByOrderBySortOrder(): List<SubscriptionPlanEntity>
}

interface InstitutionSubscriptionJpaRepository : JpaRepository<InstitutionSubscriptionEntity, UUID> {
    /** 체험 구독이 없으면 만든다 (있으면 그대로) */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into institution_subscription (institution_id, plan_code, status, trial_ends_on, next_billing_date, created_at, updated_at)
                   values (:i, 'TRIAL', 'TRIAL', :t, :t, :now, :now) on conflict (institution_id) do nothing""",
        nativeQuery = true,
    )
    fun insertTrialIfAbsent(@Param("i") institutionId: UUID, @Param("t") trialEndsOn: LocalDate, @Param("now") now: Instant): Int

    /** 기간 말 해지 예약된 구독을 다음 결제일에 해지 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update InstitutionSubscriptionEntity s set s.status = 'CANCELED', s.canceledAt = :now, s.updatedAt = :now
            where s.status = 'ACTIVE' and s.cancelAtPeriodEnd = true and s.nextBillingDate <= :today""",
    )
    fun cancelDue(@Param("today") today: LocalDate, @Param("now") now: Instant): Int
}

interface SubscriptionPaymentJpaRepository : JpaRepository<SubscriptionPaymentEntity, UUID> {
    fun findByInstitutionIdOrderByCreatedAtDesc(institutionId: UUID, pageable: Pageable): List<SubscriptionPaymentEntity>
}
