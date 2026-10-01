package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.InviteParentUseCase
import com.ttokttok.application.port.`in`.JoinByInvitationUseCase
import com.ttokttok.application.port.`in`.ReviewJoinRequestUseCase
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.InvitationPort
import com.ttokttok.application.port.out.JoinRequestPort
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.invitation.Invitation
import com.ttokttok.domain.invitation.JoinRequest
import com.ttokttok.domain.invitation.JoinRequestId
import com.ttokttok.domain.invitation.JoinRequestStatus
import com.ttokttok.domain.messaging.GuardianMessageRequested
import com.ttokttok.domain.messaging.MessageTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** STU-005 학부모 번호로 정보 입력 링크 발송 (알림톡) */
@Service
class InviteParentService(
    private val guard: AccessGuard,
    private val invitations: InvitationPort,
    private val institutions: InstitutionPort,
    private val outbox: OutboxPort,
    private val links: AppLinksPort,
    private val clock: ClockPort,
) : InviteParentUseCase {
    @Transactional
    override fun invite(actor: UserId, institutionId: InstitutionId, phone: String): InviteParentUseCase.InvitationView {
        guard.requireManager(actor, institutionId)
        val inst = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        val inv = invitations.save(Invitation.issue(institutionId, PhoneNumber.of(phone), actor, clock.now()))
        outbox.publish(
            GuardianMessageRequested(
                institutionId, inv.phone, MessageTemplate.JOIN_INVITE,
                mapOf("institutionName" to inst.name, "joinUrl" to links.joinUrl(inv.token), "expiresAt" to inv.expiresAt.toString()),
                clock.now(),
            ),
        )
        return InviteParentUseCase.InvitationView(inv.token, inv.expiresAt)
    }
}

/** 공개 진입점: 토큰이 곧 권한. 번호는 초대 시점 번호로 고정(학부모가 바꿀 수 없음) */
@Service
class JoinByInvitationService(
    private val invitations: InvitationPort,
    private val institutions: InstitutionPort,
    private val requests: JoinRequestPort,
    private val clock: ClockPort,
) : JoinByInvitationUseCase {

    @Transactional(readOnly = true)
    override fun info(token: String): JoinByInvitationUseCase.InvitationInfo {
        val inv = load(token)
        val inst = institutions.findById(inv.institutionId) ?: throw NotFoundException("기관")
        return JoinByInvitationUseCase.InvitationInfo(inst.name, inv.phone.masked, inv.expiresAt)
    }

    @Transactional
    override fun submit(command: JoinByInvitationUseCase.Command): JoinRequestId {
        val inv = load(command.token)
        val req = JoinRequest(
            JoinRequestId.new(), inv.institutionId, inv.id, command.childName.trim(), command.birthDate,
            command.guardianName.trim(), inv.phone, command.relation?.trim(), clock.now(),
        )
        return requests.save(req).id
    }

    private fun load(token: String): Invitation {
        val inv = invitations.findByToken(token) ?: throw NotFoundException("초대 링크")
        inv.ensureUsable(clock.now())
        return inv
    }
}

/** STU-004 가입 대기자 승인/거절/반배정 */
@Service
class ReviewJoinRequestService(
    private val guard: AccessGuard,
    private val requests: JoinRequestPort,
    private val classrooms: ClassroomPort,
    private val institutions: InstitutionPort,
    private val creator: StudentCreator,
    private val outbox: OutboxPort,
    private val clock: ClockPort,
) : ReviewJoinRequestUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, status: JoinRequestStatus?): List<ReviewJoinRequestUseCase.JoinRequestView> {
        guard.requireManager(actor, institutionId)
        return requests.findByInstitution(institutionId, status).sortedByDescending { it.submittedAt }.map {
            ReviewJoinRequestUseCase.JoinRequestView(
                it.id, it.childName, it.birthDate, it.guardianName, it.guardianPhone.masked, it.relation, it.status, it.submittedAt,
            )
        }
    }

    @Transactional
    override fun approve(actor: UserId, institutionId: InstitutionId, id: JoinRequestId, classroomId: ClassroomId): StudentView {
        guard.requireManager(actor, institutionId)
        val req = requests.find(id, institutionId) ?: throw NotFoundException("가입 요청")
        val classroom = classrooms.find(classroomId, institutionId) ?: throw NotFoundException("반")
        val approved = req.approve(classroom.id, actor, clock.now())
        // 학부모가 직접 링크로 들어온 경우라 설치 안내 대신 승인 알림을 보낸다
        val created = creator.create(
            institutionId, classroom, req.childName, req.birthDate, null, null,
            listOf(StudentCreator.GuardianSpec(req.guardianPhone, req.relation, true)), sendInstallGuide = false,
        )
        requests.save(approved)
        notify(institutionId, req, MessageTemplate.JOIN_APPROVED, mapOf("classroomName" to classroom.name))
        return toView(created.student, listOf(classroom.id), created.guardians, masked = false)
    }

    @Transactional
    override fun reject(actor: UserId, institutionId: InstitutionId, id: JoinRequestId, reason: String?) {
        guard.requireManager(actor, institutionId)
        val req = requests.find(id, institutionId) ?: throw NotFoundException("가입 요청")
        requests.save(req.reject(reason, actor, clock.now()))
        notify(institutionId, req, MessageTemplate.JOIN_REJECTED, mapOf("reason" to (reason ?: "")))
    }

    private fun notify(institutionId: InstitutionId, req: JoinRequest, template: MessageTemplate, extra: Map<String, String>) {
        val inst = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        outbox.publish(
            GuardianMessageRequested(
                institutionId, req.guardianPhone, template,
                mapOf("institutionName" to inst.name, "childName" to req.childName) + extra, clock.now(),
            ),
        )
    }
}
