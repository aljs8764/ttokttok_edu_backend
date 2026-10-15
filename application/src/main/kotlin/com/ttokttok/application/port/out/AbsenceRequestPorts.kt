package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.AbsenceKind
import com.ttokttok.application.port.`in`.AbsenceRequestStatus
import com.ttokttok.application.port.`in`.AbsenceRequestView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** ATT-007 저장소 (JPA) */
interface AbsenceRequestPort {
    /** 같은 아이·날짜·종류의 살아있는 신청이 있으면 null */
    fun insert(
        institutionId: InstitutionId, studentId: UUID, guardianUserId: UserId, date: LocalDate, kind: AbsenceKind,
        expectedTime: LocalTime?, reason: String, now: Instant,
    ): UUID?
    fun find(id: UUID): AbsenceRequestView?
    fun guardianOf(id: UUID): UUID?
    fun listForGuardian(guardianUserId: UserId, studentIds: Set<UUID>?, fromDate: LocalDate?, limit: Int): List<AbsenceRequestView>
    /** teacherId 가 null 이면 기관 전체, 있으면 그 교사 담당 반의 현재 원생만 */
    fun listForStaff(institutionId: InstitutionId, teacherId: UserId?, from: LocalDate, to: LocalDate, status: AbsenceRequestStatus?): List<AbsenceRequestView>
    fun teaches(id: UUID, teacherId: UserId): Boolean
    fun setStatus(id: UUID, status: AbsenceRequestStatus, actor: UserId?, now: Instant)
    /** 알림 대상: 담당 교사들, 없으면 원장·실장 */
    fun staffRecipients(institutionId: InstitutionId, studentId: UUID): List<UserId>
}
