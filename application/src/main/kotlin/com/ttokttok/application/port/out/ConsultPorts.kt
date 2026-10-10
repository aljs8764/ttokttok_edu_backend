package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.ConsultMode
import com.ttokttok.application.port.`in`.ConsultSlotView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

interface ConsultSlotPort {
    /** 이미 같은 시각 슬롯이 있으면 건너뛰고 만든 id 만 돌려준다 */
    fun insert(institutionId: InstitutionId, teacherId: UUID, startsAt: Instant, endsAt: Instant, mode: ConsultMode, location: String?, createdBy: UserId, now: Instant): UUID?
    fun find(id: UUID): ConsultSlotView?
    fun findAll(ids: Collection<UUID>): List<ConsultSlotView>
    fun list(institutionId: InstitutionId, from: Instant, to: Instant, teacherId: UUID?): List<ConsultSlotView>
    /** 학부모가 볼 수 있는 빈 슬롯: 원생의 현재 반 담당 교사 + 원장·실장 */
    fun availableFor(studentIds: Collection<UUID>, after: Instant): List<ConsultSlotView>
    fun bookedBy(parent: UserId, studentIds: Collection<UUID>?, after: Instant): List<ConsultSlotView>
    /** OPEN 일 때만 예약. 성공하면 true */
    fun book(slotId: UUID, studentId: UUID, parent: UserId, topic: String?, now: Instant): Boolean
    /** BOOKED → OPEN (예약자 본인일 때). 성공하면 true */
    fun release(slotId: UUID, parent: UserId, now: Instant): Boolean
    fun setStatus(slotId: UUID, status: String, cancelReason: String?, now: Instant)
    fun deleteOpen(slotId: UUID): Boolean
    /** 학부모가 그 원생을 예약할 수 있는 슬롯인가 (위 availableFor 와 같은 기준) */
    fun eligible(slotId: UUID, studentId: UUID): Boolean
    fun isStaff(institutionId: InstitutionId, userId: UUID): Boolean
}
