package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.AnswerInput
import com.ttokttok.application.port.`in`.ParentSurveyUseCase
import com.ttokttok.application.port.`in`.QuestionResult
import com.ttokttok.application.port.`in`.QuestionType
import com.ttokttok.application.port.`in`.StaffSurveyUseCase
import com.ttokttok.application.port.`in`.SurveyQuestionView
import com.ttokttok.application.port.`in`.SurveyResults
import com.ttokttok.application.port.`in`.SurveyView
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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

private fun SurveyQuestionView.toMap() = mapOf("id" to id, "position" to position, "type" to type.name, "text" to text, "options" to options, "required" to required)

internal fun SurveyView.toResponse() = mapOf(
    "id" to id, "institutionId" to institutionId, "institutionName" to institutionName, "title" to title, "description" to description,
    "status" to status.name, "closesAt" to closesAt, "createdAt" to createdAt, "author" to mapOf("id" to authorId, "name" to authorName),
    "recipients" to recipients, "responses" to responses, "questions" to questions.map { it.toMap() },
)

private fun QuestionResult.toResponse() = mapOf(
    "question" to question.toMap(), "counts" to counts, "average" to average, "texts" to texts.map { mapOf("studentName" to it.studentName, "text" to it.text) },
)

private fun SurveyResults.toResponse() = mapOf(
    "survey" to survey.toResponse(), "questions" to questions.map { it.toResponse() },
    "respondents" to respondents.map { mapOf("studentId" to it.studentId, "studentName" to it.studentName, "guardianName" to it.guardianName, "submittedAt" to it.submittedAt) },
    "nonRespondents" to nonRespondents,
)

/** NTC-010 설문조사 — 관리자 웹·교사 앱 */
@RestController
@RequestMapping("/api/v1/surveys")
class SurveyController(private val surveys: StaffSurveyUseCase) {
    data class QuestionRequest(
        val type: QuestionType,
        @field:NotBlank @field:Size(max = 300) val text: String,
        @field:Size(max = 10) val options: List<String> = emptyList(),
        val required: Boolean = true,
    )

    data class CreateRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 1000) val description: String? = null,
        val closesAt: Instant? = null,
        @field:Size(min = 1, max = 200) val targets: List<NoticeTargetRequest>,
        @field:Size(min = 1, max = 20) val questions: List<QuestionRequest>,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateRequest) =
        surveys.create(
            jwt.userId(), inst(institutionId),
            StaffSurveyUseCase.Create(
                req.title, req.description, req.closesAt, req.targets.map { it.toDomain() },
                req.questions.map { StaffSurveyUseCase.QuestionInput(it.type, it.text, it.options, it.required) },
            ),
        ).toResponse()

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ) = surveys.list(jwt.userId(), inst(institutionId), page, size).map { it.toResponse() }

    @GetMapping("/{id}/results")
    fun results(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        surveys.results(jwt.userId(), inst(institutionId), id).toResponse()

    @PostMapping("/{id}/close")
    fun close(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        surveys.close(jwt.userId(), inst(institutionId), id).toResponse()

    @PostMapping("/{id}/remind")
    fun remind(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        mapOf("pushRecipients" to surveys.remind(jwt.userId(), inst(institutionId), id))
}

/** NTC-010 설문조사 — 학부모 앱 */
@RestController
@RequestMapping("/api/v1/me/surveys")
class MeSurveyController(private val surveys: ParentSurveyUseCase) {
    data class AnswerRequest(val questionId: UUID, @field:Size(max = 10) val values: List<String> = emptyList())
    data class SubmitRequest(val studentId: UUID, @field:Size(max = 20) val answers: List<AnswerRequest>)

    private fun ParentSurveyUseCase.ParentSurveyItem.toResponse() = survey.toResponse() + mapOf(
        "children" to children.map { mapOf("studentId" to it.studentId, "name" to it.studentName, "answered" to it.answered) },
    )

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt) = surveys.list(jwt.userId()).map { it.toResponse() }

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = surveys.detail(jwt.userId(), id).toResponse()

    @PostMapping("/{id}/responses")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody req: SubmitRequest) {
        surveys.submit(jwt.userId(), id, req.studentId, req.answers.map { AnswerInput(it.questionId, it.values) })
    }
}
