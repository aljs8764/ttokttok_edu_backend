package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant

/** SEC-002 감사 로그 조회 (원장) */
interface AuditLogQuery {
    fun search(actor: UserId, institutionId: InstitutionId, action: String?, actorFilter: UserId?, from: Instant?, to: Instant?, page: Int, size: Int): PageResult<AuditLogView>
}

data class AuditLogView(
    val id: Long,
    val action: String,
    val resource: String,
    val resourceId: String?,
    val actorId: UserId,
    val actorName: String,
    val diff: Map<String, Any?>,
    val at: Instant,
)

/** 업로드만 요청하고 확정하지 않은 파일 정리 (매일 03:30, 24시간 경과분) */
interface CleanupStaleFilesUseCase {
    fun cleanup(limit: Int): Int
}
