package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.NoticeTemplateUseCase
import com.ttokttok.application.port.`in`.NoticeTemplateView
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.NoticeTemplatePort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val MAX_TEMPLATES = 100

@Service
class NoticeTemplateService(
    private val templates: NoticeTemplatePort,
    private val guard: AccessGuard,
    private val clock: ClockPort,
) : NoticeTemplateUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, kind: NoticeKind?): List<NoticeTemplateView> {
        guard.requireStaff(actor, institutionId)
        return templates.list(institutionId, kind)
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: NoticeTemplateUseCase.Command): NoticeTemplateView {
        val m = guard.requireStaff(actor, institutionId)
        requireKindAllowed(m.role.isManager, command.kind)
        validate(command)
        if (templates.count(institutionId) >= MAX_TEMPLATES) throw ConflictException("TEMPLATE_LIMIT", "템플릿은 ${MAX_TEMPLATES}개까지 만들 수 있습니다")
        val id = templates.insert(institutionId, actor, command.kind, command.name.trim(), command.title.trim(), command.body.trim(), clock.now())
        return templates.find(id, institutionId) ?: throw NotFoundException("템플릿")
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: NoticeTemplateUseCase.Command): NoticeTemplateView {
        val m = guard.requireStaff(actor, institutionId)
        val current = templates.find(id, institutionId) ?: throw NotFoundException("템플릿")
        if (!m.role.isManager && current.createdById != actor.value) throw ForbiddenException("만든 사람만 고칠 수 있습니다")
        requireKindAllowed(m.role.isManager, command.kind)
        validate(command)
        templates.update(id, command.kind, command.name.trim(), command.title.trim(), command.body.trim(), clock.now())
        return templates.find(id, institutionId) ?: throw NotFoundException("템플릿")
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: UUID) {
        val m = guard.requireStaff(actor, institutionId)
        val current = templates.find(id, institutionId) ?: throw NotFoundException("템플릿")
        if (!m.role.isManager && current.createdById != actor.value) throw ForbiddenException("만든 사람만 지울 수 있습니다")
        templates.delete(id)
    }

    @Transactional
    override fun markUsed(actor: UserId, institutionId: InstitutionId, id: UUID) {
        guard.requireStaff(actor, institutionId)
        templates.find(id, institutionId) ?: throw NotFoundException("템플릿")
        templates.incrementUse(id)
    }

    private fun requireKindAllowed(manager: Boolean, kind: NoticeKind) {
        if (kind == NoticeKind.ANNOUNCEMENT && !manager) throw ForbiddenException("전체 공지 양식은 원장·실장만 만들 수 있습니다")
    }

    private fun validate(c: NoticeTemplateUseCase.Command) {
        if (c.name.isBlank() || c.name.trim().length > 50) throw InvalidInputException("INVALID_NAME", "이름은 1~50자입니다")
        if (c.title.isBlank() || c.title.trim().length > 100) throw InvalidInputException("INVALID_TITLE", "제목은 1~100자입니다")
        if (c.body.isBlank() || c.body.trim().length > 5000) throw InvalidInputException("INVALID_BODY", "본문은 1~5000자입니다")
    }
}
