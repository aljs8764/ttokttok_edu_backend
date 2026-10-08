package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AlbumView
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.ParentAlbumUseCase
import com.ttokttok.application.port.`in`.StaffAlbumUseCase
import com.ttokttok.domain.file.FileId
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal fun AlbumView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "title" to title, "description" to description,
    "author" to mapOf("id" to authorId, "name" to authorName), "createdAt" to createdAt, "itemCount" to itemCount,
    "items" to items.map { it.toMap() }, "recipients" to recipients,
)

/** NTC-011 사진·영상 앨범 — 관리자 웹·교사 앱. 파일은 /files/presign(purpose=ALBUM_MEDIA) → 업로드 → complete 후 id 로 */
@RestController
@RequestMapping("/api/v1/albums")
class AlbumController(private val albums: StaffAlbumUseCase) {
    data class CreateRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 1000) val description: String? = null,
        @field:Size(min = 1, max = 30) val files: List<UUID>,
        @field:Size(min = 1, max = 200) val targets: List<NoticeTargetRequest>,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateRequest) =
        albums.create(jwt.userId(), inst(institutionId), StaffAlbumUseCase.Create(req.title, req.description, req.files.map(::FileId), req.targets.map { it.toDomain() })).toResponse()

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ) = albums.list(jwt.userId(), inst(institutionId), page, size).map { it.toResponse() }

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        albums.detail(jwt.userId(), inst(institutionId), id).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) {
        albums.delete(jwt.userId(), inst(institutionId), id)
    }
}

/** NTC-011 앨범 — 학부모 앱 */
@RestController
@RequestMapping("/api/v1/me/albums")
class MeAlbumController(private val albums: ParentAlbumUseCase, private val children: GetMyChildrenQuery) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) childId: UUID?,
        @RequestParam(required = false) before: Instant?, @RequestParam(defaultValue = "20") limit: Int,
    ) = albums.list(jwt.userId(), children.resolveFilter(jwt.userId(), childId)?.map { it.value }?.toSet(), before, limit).map { it.toResponse() }

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = albums.detail(jwt.userId(), id).toResponse()
}
