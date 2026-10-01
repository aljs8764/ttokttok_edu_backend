package com.ttokttok.application.service

import com.ttokttok.application.port.out.ClassroomPort
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
) {
    fun require(actor: UserId, institutionId: InstitutionId, vararg allowed: Role): Membership {
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
