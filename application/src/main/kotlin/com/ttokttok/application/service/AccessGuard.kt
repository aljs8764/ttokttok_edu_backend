package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ServiceStatus
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.InstitutionServicePort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Component

/**
 * RBAC (AUTH-008). 웹 어댑터는 "누가(actor) 어느 기관(institutionId)에서" 만 전달하고,
 * 실제 권한 판단은 여기서 한다 — 앱/웹/스케줄러 어느 입구로 들어와도 같은 규칙.
 */
@Component
class AccessGuard(
    private val memberships: MembershipPort,
    private val classrooms: ClassroomPort,
    private val service: InstitutionServicePort,
) {
    fun require(actor: UserId, institutionId: InstitutionId, vararg allowed: Role): Membership {
        val m = memberships.find(actor, institutionId) ?: throw ForbiddenException("이 기관의 구성원이 아닙니다")
        // SAAS-001: 정지·승인 대기 기관은 업무 API 를 쓸 수 없다 (운영사 문의는 가능하도록 지원 API 는 이 검사를 거치지 않는다)
        when (service.status(institutionId)) {
            ServiceStatus.ACTIVE -> Unit
            ServiceStatus.SUSPENDED -> throw ForbiddenException("서비스 이용이 정지된 기관입니다. 운영팀에 문의해 주세요")
            ServiceStatus.PENDING -> throw ForbiddenException("가입 승인 대기 중인 기관입니다")
        }
        if (allowed.isNotEmpty() && m.role !in allowed) throw ForbiddenException("${m.role} 권한으로는 할 수 없는 작업입니다")
        return m
    }

    /** 정지·승인 대기 기관이어도 통과 — 운영사 문의(SAAS-004)·서비스 상태 확인에만 쓴다 */
    fun requireMemberAnyStatus(actor: UserId, institutionId: InstitutionId, vararg allowed: Role): Membership {
        val m = memberships.find(actor, institutionId) ?: throw ForbiddenException("이 기관의 구성원이 아닙니다")
        if (allowed.isNotEmpty() && m.role !in allowed) throw ForbiddenException("${m.role} 권한으로는 할 수 없는 작업입니다")
        return m
    }

    fun requireStaff(actor: UserId, institutionId: InstitutionId): Membership =
        require(actor, institutionId, Role.OWNER, Role.ADMIN, Role.TEACHER)

    fun requireManager(actor: UserId, institutionId: InstitutionId): Membership =
        require(actor, institutionId, Role.OWNER, Role.ADMIN)

    /** 관리자는 모든 반, 교사는 담당 반만 */
    fun requireClassroomAccess(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId): Classroom {
        val m = requireStaff(actor, institutionId)
        val classroom = classrooms.find(classroomId, institutionId) ?: throw NotFoundException("반")
        if (!m.role.isManager && !classroom.isTaughtBy(actor)) throw ForbiddenException("담당 반이 아닙니다")
        return classroom
    }

    /** 대시보드·리포트 범위: 관리자는 전체 반, 교사는 담당 반 */
    fun visibleClassrooms(actor: UserId, institutionId: InstitutionId): List<Classroom> {
        val m = requireStaff(actor, institutionId)
        val all = classrooms.findByInstitution(institutionId)
        return if (m.role.isManager) all else all.filter { it.isTaughtBy(actor) }
    }
}
