package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.InstitutionMenusQuery
import com.ttokttok.application.port.`in`.InstitutionMenusView
import com.ttokttok.application.port.`in`.MenuGroupUseCase
import com.ttokttok.application.port.`in`.MenuGroupView
import com.ttokttok.application.port.`in`.PlatformAdminUseCase
import com.ttokttok.application.port.`in`.PlatformAdminView
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.MenuGroupPort
import com.ttokttok.application.port.out.PlatformAdminPort
import com.ttokttok.application.port.out.PlatformInstitutionPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// ───────── SAAS-009 운영사 계정 관리 ─────────
@Service
class PlatformAdminService(
    private val guard: PlatformGuard,
    private val admins: PlatformAdminPort,
    private val users: UserPort,
    private val clock: ClockPort,
) : PlatformAdminUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId): List<PlatformAdminView> {
        guard.require(actor)
        return admins.list()
    }

    @Transactional
    override fun add(actor: UserId, email: String): PlatformAdminView {
        guard.require(actor)
        // 계정이 없으면 먼저 가입(기관 가입·교직원 초대 등)해야 한다 — 운영사 전용 가입 경로는 두지 않는다
        val user = users.findByEmail(normalizeEmail(email)) ?: throw NotFoundException("계정")
        if (admins.isAdmin(user.id)) throw ConflictException("ALREADY_PLATFORM_ADMIN", "이미 운영사 계정입니다")
        admins.add(user.id, clock.now())
        return admins.list().first { it.userId == user.id.value }
    }

    @Transactional
    override fun remove(actor: UserId, userId: UUID) {
        guard.require(actor)
        if (actor.value == userId) throw ConflictException("CANNOT_REMOVE_SELF", "자기 자신의 운영사 권한은 해제할 수 없습니다")
        if (!admins.isAdmin(UserId(userId))) throw NotFoundException("운영사 계정")
        admins.remove(UserId(userId))
    }
}

// ───────── SAAS-008 기관별 메뉴 그룹 ─────────
@Service
class MenuGroupService(
    private val guard: PlatformGuard,
    private val groups: MenuGroupPort,
    private val institutions: PlatformInstitutionPort,
    private val clock: ClockPort,
) : MenuGroupUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId): List<MenuGroupView> {
        guard.require(actor)
        return groups.list()
    }

    @Transactional
    override fun create(actor: UserId, command: MenuGroupUseCase.Command): MenuGroupView {
        guard.require(actor)
        val id = Uuid7.next()
        save(id, command, exceptId = null)
        return groups.find(id)!!
    }

    @Transactional
    override fun update(actor: UserId, id: UUID, command: MenuGroupUseCase.Command): MenuGroupView {
        guard.require(actor)
        groups.find(id) ?: throw NotFoundException("메뉴 그룹")
        save(id, command, exceptId = id)
        return groups.find(id)!!
    }

    @Transactional
    override fun delete(actor: UserId, id: UUID) {
        guard.require(actor)
        groups.find(id) ?: throw NotFoundException("메뉴 그룹")
        groups.delete(id)
    }

    @Transactional
    override fun replaceInstitutions(actor: UserId, id: UUID, institutionIds: Set<UUID>): MenuGroupView {
        guard.require(actor)
        groups.find(id) ?: throw NotFoundException("메뉴 그룹")
        institutionIds.forEach { institutions.find(it) ?: throw NotFoundException("기관") }
        groups.replaceInstitutions(id, institutionIds, clock.now())
        return groups.find(id)!!
    }

    @Transactional
    override fun assign(actor: UserId, institutionId: UUID, groupId: UUID?) {
        guard.require(actor)
        institutions.find(institutionId) ?: throw NotFoundException("기관")
        if (groupId != null) groups.find(groupId) ?: throw NotFoundException("메뉴 그룹")
        groups.assign(institutionId, groupId, clock.now())
    }

    private fun save(id: UUID, command: MenuGroupUseCase.Command, exceptId: UUID?) {
        val name = command.name.trim()
        if (name.isEmpty() || name.length > 50) throw InvalidInputException("INVALID_NAME", "메뉴 그룹 이름은 1~50자입니다")
        val description = command.description?.trim()?.takeIf { it.isNotEmpty() }
        if ((description?.length ?: 0) > 200) throw InvalidInputException("INVALID_DESCRIPTION", "설명은 200자 이내입니다")
        if (command.disabledMenus.size > 100 || command.disabledMenus.any { it.length > 60 || !MENU_KEY.matches(it) })
            throw InvalidInputException("INVALID_MENU", "메뉴 값이 올바르지 않습니다")
        if (groups.existsByName(name, exceptId)) throw ConflictException("MENU_GROUP_NAME_TAKEN", "같은 이름의 메뉴 그룹이 있습니다")
        groups.save(id, name, description, command.disabledMenus, clock.now())
    }

    private companion object {
        /** 관리자 웹 경로 형태 (예: /dashboard, /attendance/monthly). 메뉴 목록 자체는 웹이 갖는다 */
        val MENU_KEY = Regex("^/[a-z0-9-]+(/[a-z0-9-]+)*$")
    }
}

@Service
class InstitutionMenusService(
    private val guard: AccessGuard,
    private val groups: MenuGroupPort,
) : InstitutionMenusQuery {
    @Transactional(readOnly = true)
    override fun menus(actor: UserId, institutionId: InstitutionId): InstitutionMenusView {
        guard.requireMemberAnyStatus(actor, institutionId)
        val g = groups.findByInstitution(institutionId.value)
        return InstitutionMenusView(g?.id, g?.name, g?.disabledMenus ?: emptySet())
    }
}
