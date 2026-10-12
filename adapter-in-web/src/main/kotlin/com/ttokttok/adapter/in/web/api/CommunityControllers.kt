package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.CommunityModerationUseCase
import com.ttokttok.application.port.`in`.CommunityUseCase
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Phase 3 P3-⑦ 익명 커뮤니티 (COM-001~009). 기관 헤더 없이 사용자 단위.
 *  게시판: /community/boards, /community/me, /community/rules
 *  글: /community/boards/{key}/posts, /community/posts/{id}, 댓글: /community/posts/{id}/comments, /community/comments/{id}
 *  공감·신고: /community/{posts|comments}/{id}/like, /report
 *  내 활동: /community/me/posts, /community/me/comments
 *  운영사(COM-008): /platform/community/reports, …/{type}/{id}/resolve · sanction · lift-sanction
 */
@RestController
class CommunityController(private val community: CommunityUseCase) {
    data class PostRequest(
        @field:NotBlank val category: String,
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:NotBlank @field:Size(max = 5000) val body: String,
    )
    data class CommentRequest(val parentId: UUID?, @field:NotBlank @field:Size(max = 1000) val body: String)
    data class ReportRequest(@field:NotBlank val reason: String, @field:Size(max = 300) val memo: String?)

    private fun PostRequest.cmd() = CommunityUseCase.WritePost(category.uppercase(), title, body)

    @GetMapping("/api/v1/community/boards")
    fun boards(@AuthenticationPrincipal jwt: Jwt) = community.boards(jwt.userId())

    @GetMapping("/api/v1/community/me")
    fun me(@AuthenticationPrincipal jwt: Jwt) = community.me(jwt.userId())

    @PostMapping("/api/v1/community/rules")
    fun agree(@AuthenticationPrincipal jwt: Jwt) = community.agreeRules(jwt.userId())

    @GetMapping("/api/v1/community/boards/{key}/posts")
    fun posts(
        @AuthenticationPrincipal jwt: Jwt, @PathVariable key: String,
        @RequestParam(defaultValue = "LATEST") sort: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
    ) = community.posts(jwt.userId(), key, sort, category?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }, q, page)

    @PostMapping("/api/v1/community/boards/{key}/posts")
    @ResponseStatus(HttpStatus.CREATED)
    fun write(@AuthenticationPrincipal jwt: Jwt, @PathVariable key: String, @Valid @RequestBody req: PostRequest) =
        community.write(jwt.userId(), key, req.cmd())

    @GetMapping("/api/v1/community/posts/{id}")
    fun post(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = community.post(jwt.userId(), id)

    @PutMapping("/api/v1/community/posts/{id}")
    fun edit(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: PostRequest) =
        community.edit(jwt.userId(), id, req.cmd())

    @DeleteMapping("/api/v1/community/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePost(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = community.deletePost(jwt.userId(), id)

    @PostMapping("/api/v1/community/posts/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun comment(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: CommentRequest) =
        community.comment(jwt.userId(), id, req.parentId, req.body)

    @DeleteMapping("/api/v1/community/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = community.deleteComment(jwt.userId(), id)

    @PostMapping("/api/v1/community/posts/{id}/like")
    fun likePost(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = community.toggleLike(jwt.userId(), "POST", id)

    @PostMapping("/api/v1/community/comments/{id}/like")
    fun likeComment(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = community.toggleLike(jwt.userId(), "COMMENT", id)

    @PostMapping("/api/v1/community/posts/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reportPost(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: ReportRequest) =
        community.report(jwt.userId(), "POST", id, req.reason.uppercase(), req.memo)

    @PostMapping("/api/v1/community/comments/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reportComment(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: ReportRequest) =
        community.report(jwt.userId(), "COMMENT", id, req.reason.uppercase(), req.memo)

    @GetMapping("/api/v1/community/me/posts")
    fun myPosts(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int) = community.myPosts(jwt.userId(), page)

    @GetMapping("/api/v1/community/me/comments")
    fun myComments(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int) = community.myComments(jwt.userId(), page)
}

@RestController
class PlatformCommunityController(private val moderation: CommunityModerationUseCase) {
    data class ResolveRequest(@field:NotBlank val action: String)
    /** days: 1·7·30, null = 영구 */
    data class SanctionRequest(val days: Int?, @field:NotBlank @field:Size(max = 200) val reason: String)

    @GetMapping("/api/v1/platform/community/reports")
    fun reports(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "OPEN") status: String,
        @RequestParam(defaultValue = "0") page: Int,
    ) = moderation.reports(jwt.userId(), !status.equals("RESOLVED", ignoreCase = true), page)

    @PostMapping("/api/v1/platform/community/{type}/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resolve(@AuthenticationPrincipal jwt: Jwt, @PathVariable type: String, @PathVariable id: UUID, @Valid @RequestBody req: ResolveRequest) =
        moderation.resolve(jwt.userId(), type, id, req.action)

    @PostMapping("/api/v1/platform/community/{type}/{id}/sanction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun sanction(@AuthenticationPrincipal jwt: Jwt, @PathVariable type: String, @PathVariable id: UUID, @Valid @RequestBody req: SanctionRequest) =
        moderation.sanction(jwt.userId(), type, id, req.days, req.reason)

    @PostMapping("/api/v1/platform/community/{type}/{id}/lift-sanction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun lift(@AuthenticationPrincipal jwt: Jwt, @PathVariable type: String, @PathVariable id: UUID) =
        moderation.liftSanction(jwt.userId(), type, id)
}
