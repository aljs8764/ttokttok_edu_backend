package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class AbsenceKind { ABSENT, LATE, EARLY_LEAVE }
enum class AbsenceRequestStatus { SUBMITTED, ACKNOWLEDGED, CANCELLED }

data class AbsenceRequestView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val studentId: UUID,
    val studentName: String,
    val guardianName: String,
    val date: LocalDate,
    val kind: AbsenceKind,
    val expectedTime: LocalTime?,
    val reason: String,
    val status: AbsenceRequestStatus,
    val acknowledgedAt: Instant?,
    val createdAt: Instant,
)

/** ATT-007 학부모 앱: 결석·지각·조퇴 사전 신청 */
interface ParentAbsenceRequestUseCase {
    fun submit(parent: UserId, command: Submit): AbsenceRequestView
    fun mine(parent: UserId, studentIds: Set<UUID>?, fromDate: LocalDate?, limit: Int): List<AbsenceRequestView>
    /** 교직원이 확인하기 전(SUBMITTED)에만, 해당 날짜가 지나기 전까지 */
    fun cancel(parent: UserId, id: UUID): AbsenceRequestView

    data class Submit(val studentId: UUID, val date: LocalDate, val kind: AbsenceKind, val reason: String, val expectedTime: LocalTime?)
}

/** ATT-007 교직원: 신청 확인 (원장·실장 전체, 교사는 담당 반 원생) */
interface StaffAbsenceRequestUseCase {
    fun list(actor: UserId, institutionId: InstitutionId, from: LocalDate, to: LocalDate, status: AbsenceRequestStatus?): List<AbsenceRequestView>
    fun acknowledge(actor: UserId, institutionId: InstitutionId, id: UUID): AbsenceRequestView
}
