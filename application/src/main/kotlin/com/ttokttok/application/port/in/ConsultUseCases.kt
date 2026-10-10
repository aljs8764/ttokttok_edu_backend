package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// NTC-008 후반: 상담 예약

enum class ConsultMode(val label: String) { IN_PERSON("대면"), PHONE("전화"), VIDEO("화상") }
enum class ConsultStatus { OPEN, BOOKED, CANCELED, DONE }

data class ConsultSlotView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val teacherId: UUID,
    val teacherName: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val mode: ConsultMode,
    val location: String?,
    val status: ConsultStatus,
    val studentId: UUID?,
    val studentName: String?,
    val bookedById: UUID?,
    val bookedByName: String?,
    val topic: String?,
    val bookedAt: Instant?,
    val cancelReason: String?,
)

/** 교직원 쪽. 교사는 자기 슬롯만, 원장·실장은 기관 전체 */
interface StaffConsultUseCase {
    data class Open(
        val date: LocalDate,
        /** "HH:mm" 첫 슬롯 시작 */
        val startTime: String,
        val minutes: Int,
        /** 연달아 만들 슬롯 수 (1~12) */
        val count: Int,
        val breakMinutes: Int,
        val mode: ConsultMode,
        val location: String?,
        /** 원장·실장이 다른 교사 몫으로 열 때. 없으면 본인 */
        val teacherId: UUID?,
    )

    fun open(actor: UserId, institutionId: InstitutionId, command: Open): List<ConsultSlotView>
    fun list(actor: UserId, institutionId: InstitutionId, from: LocalDate, to: LocalDate, teacherId: UUID?): List<ConsultSlotView>
    /** 예약된 슬롯이면 학부모에게 알린다 */
    fun cancel(actor: UserId, institutionId: InstitutionId, slotId: UUID, reason: String?): ConsultSlotView
    fun complete(actor: UserId, institutionId: InstitutionId, slotId: UUID): ConsultSlotView
    /** 예약이 없는 슬롯만 지운다 */
    fun delete(actor: UserId, institutionId: InstitutionId, slotId: UUID)
}

/** 학부모 앱 */
interface ParentConsultUseCase {
    /** 아이가 다니는 반의 담당 교사·원장·실장이 연 앞으로의 빈 슬롯 */
    fun available(parent: UserId, studentIds: Set<UUID>?): List<ConsultSlotView>
    fun mine(parent: UserId, studentIds: Set<UUID>?): List<ConsultSlotView>
    fun book(parent: UserId, slotId: UUID, studentId: UUID, topic: String?): ConsultSlotView
    /** 상담 시작 전까지 취소 가능 → 슬롯은 다시 열린다 */
    fun cancel(parent: UserId, slotId: UUID): ConsultSlotView
}
