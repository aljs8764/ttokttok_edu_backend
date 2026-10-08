package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.NoticeTemplateUseCase
import com.ttokttok.application.port.`in`.NoticeTemplateView
import com.ttokttok.domain.notice.NoticeKind
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal fun NoticeTemplateView.toResponse() = mapOf(
    "id" to id, "kind" to kind.name, "name" to name, "title" to title, "body" to body,
    "createdBy" to mapOf("id" to createdById, "name" to createdByName), "useCount" to useCount, "updatedAt" to updatedAt,
)

/** NTC-003 알림장 템플릿 라이브러리 (관리자 웹 + 교사 앱) */
@RestController
@RequestMapping("/api/v1/notice-templates")
class NoticeTemplateController(private val templates: NoticeTemplateUseCase) {
    data class TemplateRequest(
        val kind: NoticeKind = NoticeKind.NOTE,
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotBlank @field:Size(max = 5000) val body: String,
    ) {
        fun toCommand() = NoticeTemplateUseCase.Command(kind, name, title, body)
    }

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) kind: NoticeKind?) =
        templates.list(jwt.userId(), inst(institutionId), kind).map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: TemplateRequest) =
        templates.create(jwt.userId(), inst(institutionId), req.toCommand()).toResponse()

    @PutMapping("/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: TemplateRequest) =
        templates.update(jwt.userId(), inst(institutionId), id, req.toCommand()).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        templates.delete(jwt.userId(), inst(institutionId), id)
    }

    /** 작성 화면이 템플릿을 불러왔을 때 호출 (사용 횟수 집계) */
    @PostMapping("/{id}/use")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun use(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        templates.markUsed(jwt.userId(), inst(institutionId), id)
    }
}
