package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeTarget
import java.time.Instant
import java.util.UUID

enum class QuestionType { SINGLE, MULTI, TEXT, RATING }
enum class SurveyStatus { OPEN, CLOSED }

data class SurveyQuestionView(val id: UUID, val position: Int, val type: QuestionType, val text: String, val options: List<String>, val required: Boolean)

data class SurveyView(
    val id: UUID,
    val institutionId: UUID,
    val institutionName: String,
    val title: String,
    val description: String?,
    /** 마감 시각이 지났으면 CLOSED 로 본다 */
    val status: SurveyStatus,
    val closesAt: Instant?,
    val createdAt: Instant,
    val authorId: UUID,
    val authorName: String,
    val recipients: Int,
    val responses: Int,
    val questions: List<SurveyQuestionView>,
)

data class QuestionResult(
    val question: SurveyQuestionView,
    /** 선택지·평점별 응답 수 (SINGLE·MULTI·RATING) */
    val counts: Map<String, Int>,
    /** RATING 평균 */
    val average: Double?,
    /** TEXT 답변 */
    val texts: List<TextAnswer>,
)

data class TextAnswer(val studentName: String, val text: String)
data class Respondent(val studentId: UUID, val studentName: String, val guardianName: String, val submittedAt: Instant)

data class SurveyResults(
    val survey: SurveyView,
    val questions: List<QuestionResult>,
    val respondents: List<Respondent>,
    val nonRespondents: List<String>,
)

data class AnswerInput(val questionId: UUID, val values: List<String>)

/** NTC-010 설문 — 교직원 (교사는 담당 반 대상만, 전체는 원장·실장) */
interface StaffSurveyUseCase {
    fun create(actor: UserId, institutionId: InstitutionId, command: Create): SurveyView
    fun list(actor: UserId, institutionId: InstitutionId, page: Int, size: Int): List<SurveyView>
    fun results(actor: UserId, institutionId: InstitutionId, id: UUID): SurveyResults
    fun close(actor: UserId, institutionId: InstitutionId, id: UUID): SurveyView
    /** 미응답 보호자에게만 재푸시 (30분 쿨타임) */
    fun remind(actor: UserId, institutionId: InstitutionId, id: UUID): Int

    data class Create(
        val title: String, val description: String?, val closesAt: Instant?,
        val targets: List<NoticeTarget>, val questions: List<QuestionInput>,
    )
    data class QuestionInput(val type: QuestionType, val text: String, val options: List<String>, val required: Boolean)
}

/** NTC-010 설문 — 학부모 앱 */
interface ParentSurveyUseCase {
    /** 내 자녀가 받은 설문 (진행 중 먼저). answered: 자녀별 응답 여부 */
    fun list(parent: UserId): List<ParentSurveyItem>
    fun detail(parent: UserId, id: UUID): ParentSurveyItem
    fun submit(parent: UserId, id: UUID, studentId: UUID, answers: List<AnswerInput>)

    data class ParentSurveyItem(val survey: SurveyView, val children: List<ChildAnswerState>)
    data class ChildAnswerState(val studentId: UUID, val studentName: String, val answered: Boolean)
}
