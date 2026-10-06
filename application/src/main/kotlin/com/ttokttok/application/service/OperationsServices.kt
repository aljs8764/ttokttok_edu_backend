package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AuditLogQuery
import com.ttokttok.application.port.`in`.AuditLogView
import com.ttokttok.application.port.`in`.CleanupStaleFilesUseCase
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.AuditSearchCriteria
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.ObjectStoragePort
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.application.port.out.StoredFilePort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.user.Role
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class AuditLogQueryService(
    private val guard: AccessGuard,
    private val audit: AuditLogPort,
    private val users: UserPort,
) : AuditLogQuery {

    @Transactional(readOnly = true)
    override fun search(
        actor: UserId, institutionId: InstitutionId, action: String?, actorFilter: UserId?,
        from: Instant?, to: Instant?, page: Int, size: Int,
    ): PageResult<AuditLogView> {
        guard.require(actor, institutionId, Role.OWNER)
        if (page < 0 || size !in 1..100) throw InvalidInputException("INVALID_PAGE", "page ≥ 0, size 1~100")
        if (from != null && to != null && from.isAfter(to)) throw InvalidInputException("INVALID_RANGE", "기간이 올바르지 않습니다")
        val result = audit.search(AuditSearchCriteria(institutionId, action?.trim()?.uppercase()?.ifEmpty { null }, actorFilter, from, to, page, size))
        val names = result.items.map { it.entry.actorId }.distinct()
            .associateWith { id -> actorDisplayName(id) { users.findById(it)?.name } }
        return result.map { r ->
            AuditLogView(r.id, r.entry.action, r.entry.resource, r.entry.resourceId, r.entry.actorId, names.getValue(r.entry.actorId), r.entry.diff, r.entry.at)
        }
    }
}

@Service
class CleanupStaleFilesService(
    private val files: StoredFilePort,
    private val storage: ObjectStoragePort,
    private val clock: ClockPort,
) : CleanupStaleFilesUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    /** S3 객체를 먼저 지우고 행을 지운다 — 중간에 실패해도 다음 실행이 이어서 정리 */
    @Transactional
    override fun cleanup(limit: Int): Int {
        val stale = files.findPendingBefore(clock.now().minus(STALE_AFTER), limit)
        stale.forEach { f ->
            storage.delete(f.storageKey)
            files.delete(f.id)
        }
        if (stale.isNotEmpty()) log.info("미확정 업로드 {}건 정리", stale.size)
        return stale.size
    }

    companion object { val STALE_AFTER: Duration = Duration.ofHours(24) }
}
