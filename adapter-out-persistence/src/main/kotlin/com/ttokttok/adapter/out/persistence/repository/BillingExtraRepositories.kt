package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.DiscountRuleEntity
import com.ttokttok.adapter.out.persistence.entity.RefundRecordEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface DiscountRuleJpaRepository : JpaRepository<DiscountRuleEntity, UUID> {
    fun findByInstitutionIdOrderByKindAscThresholdAscCreatedAtAsc(institutionId: UUID): List<DiscountRuleEntity>
    fun findByInstitutionIdAndActiveOrderByKindAscThresholdAscCreatedAtAsc(institutionId: UUID, active: Boolean): List<DiscountRuleEntity>
}

interface RefundRecordJpaRepository : JpaRepository<RefundRecordEntity, UUID> {
    /** 환불 + 청구서 제목·원생명·처리자명 */
    @Query(
        """select r, i.title, s.name, u.name from RefundRecordEntity r
             join InvoiceEntity i on i.id = r.invoiceId
             join StudentEntity s on s.id = r.studentId
             join UserEntity u on u.id = r.createdBy
            where r.id = :id""",
    )
    fun findView(@Param("id") id: UUID): List<Array<Any?>>

    @Query(
        """select r, i.title, s.name, u.name from RefundRecordEntity r
             join InvoiceEntity i on i.id = r.invoiceId
             join StudentEntity s on s.id = r.studentId
             join UserEntity u on u.id = r.createdBy
            where r.institutionId = :i and r.refundedOn between :f and :t
            order by r.refundedOn desc, r.createdAt desc""",
    )
    fun findViews(@Param("i") institutionId: UUID, @Param("f") from: LocalDate, @Param("t") to: LocalDate): List<Array<Any?>>

    /** 청구서 + 원생명·원생 상태·환불 기록 수 */
    @Query(
        """select i, s.name, s.status, (select count(r) from RefundRecordEntity r where r.invoiceId = i.id)
             from InvoiceEntity i join StudentEntity s on s.id = i.studentId
            where i.id = :id""",
    )
    fun findInvoice(@Param("id") invoiceId: UUID): List<Array<Any?>>
}
