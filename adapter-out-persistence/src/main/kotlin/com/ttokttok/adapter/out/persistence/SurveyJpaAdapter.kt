package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.adapter.out.persistence.entity.SurveyEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyQuestionEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyRecipientEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyResponseEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyStudentKey
import com.ttokttok.adapter.out.persistence.repository.StudentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SurveyJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SurveyQuestionJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SurveyRecipientJpaRepository
import com.ttokttok.adapter.out.persistence.repository.SurveyResponseJpaRepository
import com.ttokttok.application.port.`in`.AnswerInput
import com.ttokttok.application.port.`in`.QuestionType
import com.ttokttok.application.port.`in`.SurveyQuestionView
import com.ttokttok.application.port.`in`.SurveyStatus
import com.ttokttok.application.port.`in`.SurveyView
import com.ttokttok.application.port.out.NewQuestion
import com.ttokttok.application.port.out.SurveyPort
import com.ttokttok.application.port.out.SurveyResponseRow
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 설문 (NTC-010) */
@Component
class SurveyJpaAdapter(
    private val surveys: SurveyJpaRepository,
    private val questions: SurveyQuestionJpaRepository,
    private val recipients: SurveyRecipientJpaRepository,
    private val responses: SurveyResponseJpaRepository,
    private val students: StudentJpaRepository,
    private val q: JpaQueries,
    private val json: ObjectMapper,
) : SurveyPort {

    private companion object {
        const val SELECT = """
            select s, i.name, a.name,
                   (select count(r) from SurveyRecipientEntity r where r.surveyId = s.id),
                   (select count(r) from SurveyResponseEntity r where r.surveyId = s.id)
              from SurveyEntity s join InstitutionEntity i on i.id = s.institutionId join UserEntity a on a.id = s.authorId
        """
    }

    private fun questionsOf(surveyIds: Collection<UUID>): Map<UUID, List<SurveyQuestionView>> {
        if (surveyIds.isEmpty()) return emptyMap()
        val stringList = object : TypeReference<List<String>>() {}
        return questions.findBySurveyIdInOrderBySurveyIdAscPositionAsc(surveyIds).groupBy({ it.surveyId }) {
            SurveyQuestionView(it.id, it.position, QuestionType.valueOf(it.type), it.text, json.readValue(it.options, stringList), it.required)
        }
    }

    private fun load(rows: List<Array<Any?>>): List<SurveyView> {
        val qs = questionsOf(rows.map { (it[0] as SurveyEntity).id })
        return rows.map { r ->
            val s = r[0] as SurveyEntity
            SurveyView(
                id = s.id, institutionId = s.institutionId, institutionName = r[1] as String, title = s.title, description = s.description,
                status = SurveyStatus.valueOf(s.status), closesAt = s.closesAt, createdAt = s.createdAt,
                authorId = s.authorId, authorName = r[2] as String, recipients = (r[3] as Number).toInt(), responses = (r[4] as Number).toInt(),
                questions = qs[s.id].orEmpty(),
            )
        }
    }

    override fun insert(
        institutionId: InstitutionId, author: UserId, title: String, description: String?, closesAt: Instant?, now: Instant,
        questions: List<NewQuestion>, studentIds: Collection<UUID>,
    ): UUID {
        val id = Uuid7.next()
        surveys.save(SurveyEntity(id, institutionId.value, author.value, title, description, "OPEN", closesAt, createdAt = now))
        questions.forEachIndexed { i, nq ->
            q.persist(SurveyQuestionEntity(Uuid7.next(), id, i, nq.type.name, nq.text, json.writeValueAsString(nq.options), nq.required))
        }
        studentIds.distinct().forEach { q.persist(SurveyRecipientEntity(id, it)) }
        return id
    }

    override fun find(id: UUID): SurveyView? = load(q.jpqlRows("$SELECT where s.id = :id", params("id" to id))).firstOrNull()

    override fun findForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<SurveyView> {
        val p = params("inst" to institutionId.value)
        val author = if (authorId != null) {
            p["author"] = authorId.value
            " and s.authorId = :author"
        } else ""
        return load(q.jpqlRows("$SELECT where s.institutionId = :inst$author order by s.createdAt desc", p, limit = limit, offset = offset))
    }

    override fun findForStudents(studentIds: Collection<UUID>): List<SurveyView> {
        if (studentIds.isEmpty()) return emptyList()
        return load(
            q.jpqlRows(
                "$SELECT where s.id in (select r.surveyId from SurveyRecipientEntity r where r.studentId in (:students)) order by s.createdAt desc",
                params("students" to studentIds), limit = 100,
            ),
        )
    }

    override fun close(id: UUID) {
        surveys.close(id)
    }

    override fun recipientStudentIds(surveyId: UUID): List<UUID> = recipients.findBySurveyId(surveyId).map { it.studentId }

    override fun respondedStudentIds(surveyId: UUID): Set<UUID> = responses.findBySurveyId(surveyId).map { it.studentId }.toSet()

    override fun isRecipient(surveyId: UUID, studentId: UUID): Boolean = recipients.existsById(SurveyStudentKey(surveyId, studentId))

    override fun insertResponse(surveyId: UUID, studentId: UUID, guardianUserId: UserId, answers: List<AnswerInput>, now: Instant): Boolean =
        responses.insertIfAbsent(
            surveyId, studentId, guardianUserId.value,
            json.writeValueAsString(answers.map { mapOf("questionId" to it.questionId, "values" to it.values) }), now,
        ) == 1

    override fun responses(surveyId: UUID): List<SurveyResponseRow> {
        val raw = object : TypeReference<List<Map<String, Any?>>>() {}
        return responses.findViews(surveyId).map { r ->
            val e = r[0] as SurveyResponseEntity
            SurveyResponseRow(
                e.studentId, r[1] as String, r[2] as String, e.submittedAt,
                json.readValue(e.answers, raw).map { a ->
                    @Suppress("UNCHECKED_CAST")
                    AnswerInput(UUID.fromString(a["questionId"] as String), (a["values"] as List<String>))
                },
            )
        }
    }

    override fun studentNames(ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return students.findAllById(ids).associate { it.id to it.name }
    }

    override fun touchReminder(surveyId: UUID, before: Instant, now: Instant): Boolean = surveys.touchReminder(surveyId, before, now) == 1
}
