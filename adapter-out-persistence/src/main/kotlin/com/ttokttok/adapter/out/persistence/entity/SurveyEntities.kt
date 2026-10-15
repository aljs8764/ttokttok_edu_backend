package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** NTC-010 설문 (V13) */
@Entity @Table(name = "survey")
class SurveyEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var authorId: UUID,
    var title: String,
    var description: String?,
    var status: String,
    var closesAt: Instant?,
    var lastRemindedAt: Instant? = null,
    var createdAt: Instant,
)

@Entity @Table(name = "survey_question")
class SurveyQuestionEntity(
    @Id var id: UUID,
    var surveyId: UUID,
    var position: Int,
    var type: String,
    var text: String,
    @JdbcTypeCode(SqlTypes.JSON) var options: String,
    var required: Boolean,
)

/** 발송 시점 수신 원생 스냅샷 */
@Entity @Table(name = "survey_recipient") @IdClass(SurveyStudentKey::class)
class SurveyRecipientEntity(
    @Id var surveyId: UUID,
    @Id var studentId: UUID,
)

/** 원생당 응답 1건 (보호자 중 먼저 낸 사람) */
@Entity @Table(name = "survey_response") @IdClass(SurveyStudentKey::class)
class SurveyResponseEntity(
    @Id var surveyId: UUID,
    @Id var studentId: UUID,
    var guardianUserId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) var answers: String,
    var submittedAt: Instant,
)

data class SurveyStudentKey(var surveyId: UUID? = null, var studentId: UUID? = null) : Serializable
