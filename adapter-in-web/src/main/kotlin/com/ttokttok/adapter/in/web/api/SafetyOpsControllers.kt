package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.CurriculumUseCase
import com.ttokttok.application.port.`in`.EventFollowUpUseCase
import com.ttokttok.application.port.`in`.MissingAlertPolicy
import com.ttokttok.application.port.`in`.MissingAlertUseCase
import com.ttokttok.application.port.`in`.WorkAnalyticsQuery
import com.ttokttok.application.port.out.ClockPort
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

// ───────── ATT-010 미등원 초과 경고 ─────────
@RestController
class MissingAlertController(private val missing: MissingAlertUseCase, private val clock: ClockPort) {
    data class PolicyRequest(@field:NotNull val minutes: Int, @field:NotNull val notifyParent: Boolean)

    @GetMapping("/api/v1/institution/missing-alert")
    fun policy(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        missing.policy(jwt.userId(), inst(institutionId))

    @PutMapping("/api/v1/institution/missing-alert")
    fun setPolicy(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: PolicyRequest) =
        missing.setPolicy(jwt.userId(), inst(institutionId), MissingAlertPolicy(req.minutes, req.notifyParent))

    @GetMapping("/api/v1/attendance-missing-alerts")
    fun alerts(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestParam(required = false) date: LocalDate?) =
        missing.alerts(jwt.userId(), inst(institutionId), date ?: clock.today())
}

// ───────── STAT-007 · STF-005 ─────────
@RestController
class WorkAnalyticsController(private val work: WorkAnalyticsQuery) {
    @GetMapping("/api/v1/stats/notice-ranking")
    fun noticeRanking(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?,
    ) = work.noticeRanking(jwt.userId(), inst(institutionId), from, to)

    @GetMapping("/api/v1/stats/staff-work")
    fun staffWork(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?,
    ) = work.staffWork(jwt.userId(), inst(institutionId), from, to)
}

// ───────── CLS-005 교재·진도 ─────────
@RestController
class CurriculumController(private val curriculum: CurriculumUseCase) {
    data class TextbookRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 50) val publisher: String?,
        @field:Size(max = 300) val memo: String?,
        val archived: Boolean?,
    ) {
        fun toCommand() = CurriculumUseCase.SaveTextbook(name, publisher, memo, archived ?: false)
    }
    data class ProgressRequest(
        @field:NotNull val date: LocalDate,
        val textbookId: UUID?,
        @field:NotBlank @field:Size(max = 500) val content: String,
        @field:Size(max = 500) val homework: String?,
    ) {
        fun toCommand() = CurriculumUseCase.SaveProgress(date, textbookId, content, homework)
    }
    data class QuoteRequest(@field:NotEmpty val classroomIds: List<UUID>, val date: LocalDate?)

    @GetMapping("/api/v1/classes/{id}/textbooks")
    fun textbooks(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ) = curriculum.textbooks(jwt.userId(), inst(institutionId), id, includeArchived)

    @PostMapping("/api/v1/classes/{id}/textbooks")
    @ResponseStatus(HttpStatus.CREATED)
    fun addTextbook(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: TextbookRequest) =
        curriculum.addTextbook(jwt.userId(), inst(institutionId), id, req.toCommand())

    @PutMapping("/api/v1/textbooks/{id}")
    fun updateTextbook(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: TextbookRequest) =
        curriculum.updateTextbook(jwt.userId(), inst(institutionId), id, req.toCommand())

    @GetMapping("/api/v1/classes/{id}/progress")
    fun progress(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?,
    ) = curriculum.progress(jwt.userId(), inst(institutionId), id, from, to)

    @PostMapping("/api/v1/classes/{id}/progress")
    @ResponseStatus(HttpStatus.CREATED)
    fun addProgress(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: ProgressRequest) =
        curriculum.addProgress(jwt.userId(), inst(institutionId), id, req.toCommand())

    @PutMapping("/api/v1/progress/{id}")
    fun updateProgress(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: ProgressRequest) =
        curriculum.updateProgress(jwt.userId(), inst(institutionId), id, req.toCommand())

    @DeleteMapping("/api/v1/progress/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProgress(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        curriculum.deleteProgress(jwt.userId(), inst(institutionId), id)

    /** 알림장 작성 화면의 "진도 불러오기" */
    @PostMapping("/api/v1/curriculum/quote")
    fun quote(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: QuoteRequest) =
        mapOf("text" to curriculum.quote(jwt.userId(), inst(institutionId), req.classroomIds, req.date))
}

// ───────── EVT-009 행사 후기·사진 ─────────
@RestController
class EventFollowUpController(private val followUp: EventFollowUpUseCase) {
    data class LinkRequest(@field:NotNull val albumId: UUID)
    data class HideRequest(@field:NotNull val hidden: Boolean)
    data class ReviewRequest(@field:NotNull val studentId: UUID, @field:NotBlank @field:Size(max = 1000) val body: String)

    @GetMapping("/api/v1/events/{id}/albums")
    fun albums(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        followUp.albums(jwt.userId(), inst(institutionId), id)

    @PostMapping("/api/v1/events/{id}/albums")
    fun link(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: LinkRequest) =
        followUp.linkAlbum(jwt.userId(), inst(institutionId), id, req.albumId)

    @DeleteMapping("/api/v1/events/{id}/albums/{albumId}")
    fun unlink(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @PathVariable albumId: UUID) =
        followUp.unlinkAlbum(jwt.userId(), inst(institutionId), id, albumId)

    @GetMapping("/api/v1/events/{id}/reviews")
    fun reviews(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        followUp.reviews(jwt.userId(), inst(institutionId), id)

    @PostMapping("/api/v1/event-reviews/{id}/hidden")
    fun hide(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID, @Valid @RequestBody req: HideRequest) =
        followUp.hideReview(jwt.userId(), inst(institutionId), id, req.hidden)

    // 학부모 앱
    @GetMapping("/api/v1/me/events/{id}/albums")
    fun myAlbums(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = followUp.myAlbums(jwt.userId(), id)

    @GetMapping("/api/v1/me/events/{id}/reviews")
    fun myReviews(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = followUp.myReviews(jwt.userId(), id)

    @PutMapping("/api/v1/me/events/{id}/review")
    fun writeReview(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: ReviewRequest) =
        followUp.writeReview(jwt.userId(), id, req.studentId, req.body)

    @DeleteMapping("/api/v1/me/events/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteReview(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = followUp.deleteMyReview(jwt.userId(), id)
}
