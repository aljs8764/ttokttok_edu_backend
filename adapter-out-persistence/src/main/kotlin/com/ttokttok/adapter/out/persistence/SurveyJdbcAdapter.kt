package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 설문 (NTC-010) */
@Component
class SurveyJdbcAdapter(jdbc: JdbcTemplate, private val json: ObjectMapper) : SurveyPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select s.id, s.institution_id, i.name as institution_name, s.title, s.description, s.status, s.closes_at, s.created_at,
                   s.author_id, a.name as author_name,
                   (select count(*) from survey_recipient r where r.survey_id = s.id) as recipients,
                   (select count(*) from survey_response r where r.survey_id = s.id) as responses
            from survey s join institution i on i.id = s.institution_id join app_user a on a.id = s.author_id
        """

        fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
    }

    private fun ts(i: Instant) = Timestamp.from(i)

    private fun questions(surveyIds: Collection<UUID>): Map<UUID, List<SurveyQuestionView>> {
        if (surveyIds.isEmpty()) return emptyMap()
        val stringList = object : TypeReference<List<String>>() {}
        return named.query(
            "select id, survey_id, position, type, text, options, required from survey_question where survey_id in (:ids) order by survey_id, position",
            MapSqlParameterSource("ids", surveyIds),
        ) { rs, _ ->
            rs.uuid("survey_id") to SurveyQuestionView(
                rs.uuid("id"), rs.getInt("position"), QuestionType.valueOf(rs.getString("type")), rs.getString("text"),
                json.readValue(rs.getString("options"), stringList), rs.getBoolean("required"),
            )
        }.groupBy({ it.first }, { it.second })
    }

    private fun load(sql: String, p: MapSqlParameterSource): List<SurveyView> {
        val heads = named.query(sql, p) { rs, _ ->
            arrayOf<Any?>(
                rs.uuid("id"), rs.uuid("institution_id"), rs.getString("institution_name"), rs.getString("title"), rs.getString("description"),
                rs.getString("status"), rs.getTimestamp("closes_at")?.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.uuid("author_id"), rs.getString("author_name"), rs.getInt("recipients"), rs.getInt("responses"),
            )
        }
        val qs = questions(heads.map { it[0] as UUID })
        return heads.map { h ->
            SurveyView(
                id = h[0] as UUID, institutionId = h[1] as UUID, institutionName = h[2] as String, title = h[3] as String, description = h[4] as String?,
                status = SurveyStatus.valueOf(h[5] as String), closesAt = h[6] as Instant?, createdAt = h[7] as Instant,
                authorId = h[8] as UUID, authorName = h[9] as String, recipients = h[10] as Int, responses = h[11] as Int,
                questions = qs[h[0] as UUID].orEmpty(),
            )
        }
    }

    override fun insert(
        institutionId: InstitutionId, author: UserId, title: String, description: String?, closesAt: Instant?, now: Instant,
        questions: List<NewQuestion>, studentIds: Collection<UUID>,
    ): UUID {
        val id = Uuid7.next()
        named.update(
            """
            insert into survey (id, institution_id, author_id, title, description, status, closes_at, created_at)
            values (:id, :inst, :author, :title, :description, 'OPEN', :closes, :now)
            """.trimIndent(),
            MapSqlParameterSource("id", id).addValue("inst", institutionId.value).addValue("author", author.value).addValue("title", title)
                .addValue("description", description).addValue("closes", closesAt?.let(::ts), java.sql.Types.TIMESTAMP).addValue("now", ts(now)),
        )
        questions.forEachIndexed { i, q ->
            named.update(
                "insert into survey_question (id, survey_id, position, type, text, options, required) values (:id, :survey, :pos, :type, :text, cast(:options as jsonb), :required)",
                MapSqlParameterSource("id", Uuid7.next()).addValue("survey", id).addValue("pos", i).addValue("type", q.type.name)
                    .addValue("text", q.text).addValue("options", json.writeValueAsString(q.options)).addValue("required", q.required),
            )
        }
        named.batchUpdate(
            "insert into survey_recipient (survey_id, student_id) values (:survey, :student)",
            studentIds.distinct().map { MapSqlParameterSource("survey", id).addValue("student", it) }.toTypedArray(),
        )
        return id
    }

    override fun find(id: UUID): SurveyView? = load("$SELECT where s.id = :id", MapSqlParameterSource("id", id)).firstOrNull()

    override fun findForStaff(institutionId: InstitutionId, authorId: UserId?, limit: Int, offset: Int): List<SurveyView> {
        val p = MapSqlParameterSource("inst", institutionId.value).addValue("limit", limit).addValue("offset", offset)
        val author = if (authorId != null) {
            p.addValue("author", authorId.value)
            " and s.author_id = :author"
        } else ""
        return load("$SELECT where s.institution_id = :inst$author order by s.created_at desc limit :limit offset :offset", p)
    }

    override fun findForStudents(studentIds: Collection<UUID>): List<SurveyView> {
        if (studentIds.isEmpty()) return emptyList()
        return load(
            "$SELECT where s.id in (select survey_id from survey_recipient where student_id in (:students)) order by s.created_at desc limit 100",
            MapSqlParameterSource("students", studentIds),
        )
    }

    override fun close(id: UUID) {
        named.update("update survey set status = 'CLOSED' where id = :id", MapSqlParameterSource("id", id))
    }

    override fun recipientStudentIds(surveyId: UUID): List<UUID> =
        named.query("select student_id from survey_recipient where survey_id = :id", MapSqlParameterSource("id", surveyId)) { rs, _ -> rs.uuid("student_id") }

    override fun respondedStudentIds(surveyId: UUID): Set<UUID> =
        named.query("select student_id from survey_response where survey_id = :id", MapSqlParameterSource("id", surveyId)) { rs, _ -> rs.uuid("student_id") }.toSet()

    override fun isRecipient(surveyId: UUID, studentId: UUID): Boolean =
        named.queryForObject(
            "select exists (select 1 from survey_recipient where survey_id = :s and student_id = :st)",
            MapSqlParameterSource("s", surveyId).addValue("st", studentId), Boolean::class.java,
        ) ?: false

    override fun insertResponse(surveyId: UUID, studentId: UUID, guardianUserId: UserId, answers: List<AnswerInput>, now: Instant): Boolean =
        named.update(
            """
            insert into survey_response (survey_id, student_id, guardian_user_id, answers, submitted_at)
            values (:s, :st, :g, cast(:answers as jsonb), :now) on conflict (survey_id, student_id) do nothing
            """.trimIndent(),
            MapSqlParameterSource("s", surveyId).addValue("st", studentId).addValue("g", guardianUserId.value)
                .addValue("answers", json.writeValueAsString(answers.map { mapOf("questionId" to it.questionId, "values" to it.values) })).addValue("now", ts(now)),
        ) == 1

    override fun responses(surveyId: UUID): List<SurveyResponseRow> {
        val raw = object : TypeReference<List<Map<String, Any?>>>() {}
        return named.query(
            """
            select r.student_id, s.name as student_name, g.name as guardian_name, r.submitted_at, r.answers
            from survey_response r join student s on s.id = r.student_id join app_user g on g.id = r.guardian_user_id
            where r.survey_id = :id order by r.submitted_at
            """.trimIndent(),
            MapSqlParameterSource("id", surveyId),
        ) { rs, _ ->
            SurveyResponseRow(
                rs.uuid("student_id"), rs.getString("student_name"), rs.getString("guardian_name"), rs.getTimestamp("submitted_at").toInstant(),
                json.readValue(rs.getString("answers"), raw).map { a ->
                    @Suppress("UNCHECKED_CAST")
                    AnswerInput(UUID.fromString(a["questionId"] as String), (a["values"] as List<String>))
                },
            )
        }
    }

    override fun studentNames(ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return named.query("select id, name from student where id in (:ids)", MapSqlParameterSource("ids", ids)) { rs, _ -> rs.uuid("id") to rs.getString("name") }.toMap()
    }

    override fun touchReminder(surveyId: UUID, before: Instant, now: Instant): Boolean =
        named.update(
            "update survey set last_reminded_at = :now where id = :id and (last_reminded_at is null or last_reminded_at < :before)",
            MapSqlParameterSource("id", surveyId).addValue("now", ts(now)).addValue("before", ts(before)),
        ) == 1
}
