package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.AnswerInput
import com.ttokttok.application.port.`in`.QuestionType
import com.ttokttok.application.port.`in`.SurveyQuestionView
import com.ttokttok.application.port.`in`.SurveyStatus
import com.ttokttok.application.port.`in`.SurveyView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

data class SurveyResponseRow(
    val studentId: UUID, val studentName: String, val guardianName: String, val submittedAt: Instant, val answers: List<AnswerInput>,
)

data class NewQuestion(val type: QuestionType, val text: String, val options: List<String>, val required: Boolean)

/** NTC-010 저장소 (JPA) */
interface SurveyPort {
    fun insert(
        institutionId: InstitutionId, author: UserId, title: String, description: String?, closesAt: Instant?, now: Instant,
        questions: List<NewQuestion>, studentIds: Collection<UUID>,
    ): UUID
    fun find(id: UUID): SurveyView?
    fun findForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<SurveyView>
    fun findForStudents(studentIds: Collection<UUID>): List<SurveyView>
    fun close(id: UUID)
    fun recipientStudentIds(surveyId: UUID): List<UUID>
    fun respondedStudentIds(surveyId: UUID): Set<UUID>
    fun isRecipient(surveyId: UUID, studentId: UUID): Boolean
    /** 이미 응답한 원생이면 false */
    fun insertResponse(surveyId: UUID, studentId: UUID, guardianUserId: UserId, answers: List<AnswerInput>, now: Instant): Boolean
    fun responses(surveyId: UUID): List<SurveyResponseRow>
    fun studentNames(ids: Collection<UUID>): Map<UUID, String>
    /** 재푸시 쿨타임 확인·갱신: 마지막 재푸시가 [before] 이전이거나 없으면 now 로 갱신하고 true */
    fun touchReminder(surveyId: UUID, before: Instant, now: Instant): Boolean
}
