package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AbsenceKind
import com.ttokttok.application.port.`in`.AbsenceRequestStatus
import com.ttokttok.application.port.`in`.AbsenceRequestView
import com.ttokttok.application.port.`in`.ParentAbsenceRequestUseCase
import com.ttokttok.application.port.`in`.StaffAbsenceRequestUseCase
import com.ttokttok.application.port.out.AbsenceRequestPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.messaging.TeacherPushRequested
import com.ttokttok.domain.student.GuardianLinkStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

private val KIND_LABEL = mapOf(AbsenceKind.ABSENT to "결석", AbsenceKind.LATE to "지각", AbsenceKind.EARLY_LEAVE to "조퇴")
private const val MAX_DAYS_AHEAD = 60L

@Service
class ParentAbsenceRequestService(
    private val requests: AbsenceRequestPort,
    private val guardians: GuardianPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : ParentAbsenceRequestUseCase {

    @Transactional
    override fun submit(parent: UserId, command: ParentAbsenceRequestUseCase.Submit): AbsenceRequestView {
        val link = guardians.findLinkedByUser(parent).firstOrNull { it.studentId.value == command.studentId && it.linkStatus == GuardianLinkStatus.LINKED }
            ?: throw ForbiddenException("본인 자녀가 아닙니다")
        val today = clock.today()
        if (command.date.isBefore(today)) throw InvalidInputException("PAST_DATE", "지난 날짜는 신청할 수 없습니다")
        if (command.date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) throw InvalidInputException("TOO_FAR", "${MAX_DAYS_AHEAD}일 이내만 신청할 수 있습니다")
        val reason = command.reason.trim()
        if (reason.isEmpty()) throw InvalidInputException("REASON_REQUIRED", "사유를 입력해 주세요")
        if (reason.length > 300) throw InvalidInputException("REASON_TOO_LONG", "사유는 300자까지입니다")
        if (command.kind != AbsenceKind.ABSENT && command.expectedTime == null) {
            throw InvalidInputException("TIME_REQUIRED", "${KIND_LABEL.getValue(command.kind)}은 예상 시각이 필요합니다")
        }
        val now = clock.now()
        val id = requests.insert(link.institutionId, command.studentId, parent, command.date, command.kind,
            if (command.kind == AbsenceKind.ABSENT) null else command.expectedTime, reason, now)
            ?: throw ConflictException("ALREADY_REQUESTED", "이미 같은 날짜에 같은 신청이 있습니다")
        val view = requests.find(id) ?: throw NotFoundException("신청")

        val recipients = requests.staffRecipients(link.institutionId, command.studentId)
        if (recipients.isNotEmpty()) {
            outbox.publish(
                TeacherPushRequested(
                    link.institutionId, "ABSENCE_REQUEST", "[${view.studentName}] ${KIND_LABEL.getValue(command.kind)} 사전 신청",
                    "${command.date} · $reason".take(80),
                    mapOf("type" to "absence_request", "requestId" to id.toString()), recipients, now,
                ),
            )
            realtime.userEvents(recipients, mapOf("type" to "absence.requested", "requestId" to id.toString()))
        }
        realtime.institutionEvent(link.institutionId, mapOf("type" to "absence.requested", "requestId" to id.toString()))
        return view
    }

    @Transactional(readOnly = true)
    override fun mine(parent: UserId, studentIds: Set<UUID>?, fromDate: LocalDate?, limit: Int): List<AbsenceRequestView> {
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        return requests.listForGuardian(parent, studentIds, fromDate, limit)
    }

    @Transactional
    override fun cancel(parent: UserId, id: UUID): AbsenceRequestView {
        if (requests.guardianOf(id) != parent.value) throw ForbiddenException("내 신청이 아닙니다")
        val view = requests.find(id) ?: throw NotFoundException("신청")
        if (view.status != AbsenceRequestStatus.SUBMITTED) throw ConflictException("NOT_CANCELLABLE", "이미 확인됐거나 취소된 신청입니다")
        if (view.date.isBefore(clock.today())) throw ConflictException("NOT_CANCELLABLE", "지난 날짜의 신청은 취소할 수 없습니다")
        requests.setStatus(id, AbsenceRequestStatus.CANCELLED, null, clock.now())
        return requests.find(id) ?: throw NotFoundException("신청")
    }
}

@Service
class StaffAbsenceRequestService(
    private val requests: AbsenceRequestPort,
    private val guard: AccessGuard,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : StaffAbsenceRequestUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, from: LocalDate, to: LocalDate, status: AbsenceRequestStatus?): List<AbsenceRequestView> {
        val m = guard.requireStaff(actor, institutionId)
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) throw InvalidInputException("INVALID_RANGE", "기간은 92일 이내여야 합니다")
        return requests.listForStaff(institutionId, if (m.role.isManager) null else actor, from, to, status)
    }

    @Transactional
    override fun acknowledge(actor: UserId, institutionId: InstitutionId, id: UUID): AbsenceRequestView {
        val m = guard.requireStaff(actor, institutionId)
        val view = requests.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("신청")
        if (!m.role.isManager && !requests.teaches(id, actor)) throw ForbiddenException("담당 반 원생이 아닙니다")
        if (view.status != AbsenceRequestStatus.SUBMITTED) throw ConflictException("NOT_ACKNOWLEDGEABLE", "이미 처리된 신청입니다")
        val now = clock.now()
        requests.setStatus(id, AbsenceRequestStatus.ACKNOWLEDGED, actor, now)
        val guardianId = requests.guardianOf(id)
        if (guardianId != null) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "ABSENCE_REQUEST_ACK", "[${view.institutionName}] ${KIND_LABEL.getValue(view.kind)} 신청 확인",
                    "${view.studentName} ${view.date} ${KIND_LABEL.getValue(view.kind)} 신청을 선생님이 확인했어요",
                    mapOf("type" to "absence_request", "requestId" to id.toString(), "studentId" to view.studentId.toString()),
                    listOf(UserId(guardianId)), now,
                ),
            )
            realtime.userEvents(listOf(UserId(guardianId)), mapOf("type" to "absence.acknowledged", "requestId" to id.toString()))
        }
        return requests.find(id) ?: throw NotFoundException("신청")
    }
}
