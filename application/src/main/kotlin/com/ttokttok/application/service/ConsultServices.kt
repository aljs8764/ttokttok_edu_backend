package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ConsultSlotView
import com.ttokttok.application.port.`in`.ConsultStatus
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.ParentConsultUseCase
import com.ttokttok.application.port.`in`.StaffConsultUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.ConsultSlotPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.messaging.TeacherPushRequested
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

private val KO_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일(E) HH:mm", Locale.KOREAN)

internal fun consultWhen(at: Instant, zone: ZoneId): String = KO_TIME.format(at.atZone(zone))

@Service
class StaffConsultService(
    private val guard: AccessGuard,
    private val slots: ConsultSlotPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : StaffConsultUseCase {

    @Transactional
    override fun open(actor: UserId, institutionId: InstitutionId, command: StaffConsultUseCase.Open): List<ConsultSlotView> {
        val m = guard.requireStaff(actor, institutionId)
        val teacher = command.teacherId ?: actor.value
        if (teacher != actor.value) {
            if (!m.role.isManager) throw ForbiddenException("다른 선생님의 상담 시간은 원장·실장만 열 수 있습니다")
            if (!slots.isStaff(institutionId, teacher)) throw InvalidInputException("INVALID_TEACHER", "이 기관의 교직원이 아닙니다")
        }
        if (command.minutes !in 10..120) throw InvalidInputException("INVALID_MINUTES", "상담 시간은 10~120분입니다")
        if (command.count !in 1..12) throw InvalidInputException("INVALID_COUNT", "한 번에 1~12개까지 만들 수 있습니다")
        if (command.breakMinutes !in 0..60) throw InvalidInputException("INVALID_BREAK", "쉬는 시간은 0~60분입니다")
        val location = command.location?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > 100) throw InvalidInputException("TOO_LONG", "장소는 100자까지입니다")
        }
        val start = try {
            LocalTime.parse(command.startTime)
        } catch (e: DateTimeParseException) {
            throw InvalidInputException("INVALID_TIME", "시작 시각은 HH:mm 형식입니다")
        }
        val zone = clock.zone()
        val now = clock.now()
        var at = command.date.atTime(start).atZone(zone).toInstant()
        if (at.isBefore(now)) throw InvalidInputException("PAST_TIME", "지난 시각에는 상담을 열 수 없습니다")
        if (command.date.isAfter(clock.today().plusDays(90))) throw InvalidInputException("TOO_FAR", "90일 안의 날짜만 열 수 있습니다")
        val ids = mutableListOf<UUID>()
        repeat(command.count) {
            val end = at.plusSeconds(command.minutes * 60L)
            slots.insert(institutionId, teacher, at, end, command.mode, location, actor, now)?.let(ids::add)
            at = end.plusSeconds(command.breakMinutes * 60L)
        }
        if (ids.isEmpty()) throw ConflictException("ALREADY_OPEN", "같은 시각에 이미 상담 시간이 열려 있습니다")
        return slots.findAll(ids).sortedBy { it.startsAt }
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, from: LocalDate, to: LocalDate, teacherId: UUID?): List<ConsultSlotView> {
        val m = guard.requireStaff(actor, institutionId)
        if (to.isBefore(from) || to.isAfter(from.plusDays(92))) throw InvalidInputException("INVALID_RANGE", "기간은 최대 3개월입니다")
        val zone = clock.zone()
        // 교사는 자기 것만
        val teacher = if (m.role.isManager) teacherId else actor.value
        return slots.list(institutionId, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), teacher)
    }

    @Transactional
    override fun cancel(actor: UserId, institutionId: InstitutionId, slotId: UUID, reason: String?): ConsultSlotView {
        val slot = owned(actor, institutionId, slotId)
        if (slot.status == ConsultStatus.CANCELED || slot.status == ConsultStatus.DONE) throw ConflictException("ALREADY_CLOSED", "이미 끝났거나 취소된 상담입니다")
        val now = clock.now()
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        slots.setStatus(slotId, "CANCELED", why, now)
        if (slot.status == ConsultStatus.BOOKED && slot.bookedById != null) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "CHAT_CONSULT_CANCELED", "상담이 취소되었어요",
                    "${consultWhen(slot.startsAt, clock.zone())} ${slot.teacherName} 선생님 상담이 취소되었습니다${why?.let { " ($it)" } ?: ""}",
                    mapOf("type" to "consult", "slotId" to slotId.toString()), listOf(UserId(slot.bookedById)), now,
                ),
            )
        }
        return slots.find(slotId)!!
    }

    @Transactional
    override fun complete(actor: UserId, institutionId: InstitutionId, slotId: UUID): ConsultSlotView {
        val slot = owned(actor, institutionId, slotId)
        if (slot.status != ConsultStatus.BOOKED) throw ConflictException("NOT_BOOKED", "예약된 상담만 완료 처리할 수 있습니다")
        slots.setStatus(slotId, "DONE", null, clock.now())
        return slots.find(slotId)!!
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, slotId: UUID) {
        owned(actor, institutionId, slotId)
        if (!slots.deleteOpen(slotId)) throw ConflictException("HAS_BOOKING", "예약이 있는 상담은 지울 수 없습니다. 취소해 주세요")
    }

    private fun owned(actor: UserId, institutionId: InstitutionId, slotId: UUID): ConsultSlotView {
        val m = guard.requireStaff(actor, institutionId)
        val slot = slots.find(slotId)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("상담 시간")
        if (!m.role.isManager && slot.teacherId != actor.value) throw ForbiddenException("본인의 상담만 바꿀 수 있습니다")
        return slot
    }
}

@Service
class ParentConsultService(
    private val children: GetMyChildrenQuery,
    private val slots: ConsultSlotPort,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : ParentConsultUseCase {

    private fun myStudents(parent: UserId, filter: Set<UUID>?): Set<UUID> {
        val all = children.children(parent).flatMap { c -> c.enrollments.filter { it.status != "WITHDRAWN" }.map { it.studentId.value } }.toSet()
        return if (filter == null) all else all.intersect(filter)
    }

    @Transactional(readOnly = true)
    override fun available(parent: UserId, studentIds: Set<UUID>?): List<ConsultSlotView> {
        val ids = myStudents(parent, studentIds)
        if (ids.isEmpty()) return emptyList()
        return slots.availableFor(ids, clock.now().plusSeconds(60L * MIN_LEAD_MINUTES))
    }

    @Transactional(readOnly = true)
    override fun mine(parent: UserId, studentIds: Set<UUID>?): List<ConsultSlotView> =
        slots.bookedBy(parent, studentIds?.let { myStudents(parent, it) }, clock.now().minusSeconds(30L * 86_400))

    @Transactional
    override fun book(parent: UserId, slotId: UUID, studentId: UUID, topic: String?): ConsultSlotView {
        if (studentId !in myStudents(parent, null)) throw ForbiddenException("본인 자녀가 아닙니다")
        val slot = slots.find(slotId) ?: throw NotFoundException("상담 시간")
        val now = clock.now()
        if (slot.status != ConsultStatus.OPEN) throw ConflictException("ALREADY_BOOKED", "이미 예약된 시간입니다. 다른 시간을 골라 주세요")
        if (slot.startsAt.isBefore(now.plusSeconds(60L * MIN_LEAD_MINUTES))) throw ConflictException("TOO_LATE", "상담 시작 ${MIN_LEAD_MINUTES}분 전까지만 예약할 수 있습니다")
        if (!slots.eligible(slotId, studentId)) throw ForbiddenException("이 선생님께는 예약할 수 없습니다")
        val text = topic?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 300) throw InvalidInputException("TOO_LONG", "상담 내용은 300자까지입니다") }
        if (!slots.book(slotId, studentId, parent, text, now)) throw ConflictException("ALREADY_BOOKED", "방금 다른 분이 예약했습니다. 다른 시간을 골라 주세요")
        val booked = slots.find(slotId)!!
        outbox.publish(
            TeacherPushRequested(
                InstitutionId(booked.institutionId), "CHAT_CONSULT_BOOKED", "상담 예약이 들어왔어요",
                "${consultWhen(booked.startsAt, clock.zone())} · ${booked.studentName ?: ""} 보호자${text?.let { " — ${it.take(40)}" } ?: ""}",
                mapOf("type" to "consult", "slotId" to slotId.toString()), listOf(UserId(booked.teacherId)), now,
            ),
        )
        return booked
    }

    @Transactional
    override fun cancel(parent: UserId, slotId: UUID): ConsultSlotView {
        val slot = slots.find(slotId)?.takeIf { it.bookedById == parent.value } ?: throw NotFoundException("상담 예약")
        val now = clock.now()
        if (slot.status != ConsultStatus.BOOKED) throw ConflictException("NOT_BOOKED", "취소할 수 있는 예약이 아닙니다")
        if (!slot.startsAt.isAfter(now)) throw ConflictException("ALREADY_STARTED", "이미 시작된 상담은 취소할 수 없습니다. 선생님께 연락해 주세요")
        if (!slots.release(slotId, parent, now)) throw ConflictException("NOT_BOOKED", "취소할 수 있는 예약이 아닙니다")
        outbox.publish(
            TeacherPushRequested(
                InstitutionId(slot.institutionId), "CHAT_CONSULT_CANCELED", "상담 예약이 취소되었어요",
                "${consultWhen(slot.startsAt, clock.zone())} · ${slot.studentName ?: ""} 보호자가 예약을 취소했습니다",
                mapOf("type" to "consult", "slotId" to slotId.toString()), listOf(UserId(slot.teacherId)), now,
            ),
        )
        return slot.copy(status = ConsultStatus.CANCELED)
    }

    companion object {
        /** 상담 시작 이 분 전까지만 예약 */
        const val MIN_LEAD_MINUTES = 30L
    }
}
