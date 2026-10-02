package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AuditLogQuery
import com.ttokttok.domain.common.UserId
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** SEC-002 감사 로그 조회 (원장). action 예: ATTENDANCE_STATUS_CHANGE, ATTENDANCE_EXPORT, INSTITUTION_UPDATE */
@RestController
@RequestMapping("/api/v1/audit-logs")
class AuditLogController(private val audit: AuditLogQuery) {
    @GetMapping
    fun search(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int,
    ) = audit.search(jwt.userId(), inst(institutionId), action, actorId?.let(::UserId), from, to, page, size).toResponse {
        mapOf(
            "id" to it.id, "action" to it.action, "resource" to it.resource, "resourceId" to it.resourceId,
            "actor" to mapOf("id" to it.actorId.value, "name" to it.actorName), "diff" to it.diff, "at" to it.at,
        )
    }
}
