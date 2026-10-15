package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.SurveyEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyQuestionEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyRecipientEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyResponseEntity
import com.ttokttok.adapter.out.persistence.entity.SurveyStudentKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface SurveyJpaRepository : JpaRepository<SurveyEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SurveyEntity s set s.status = 'CLOSED' where s.id = :id")
    fun close(@Param("id") id: UUID): Int

    /** 마지막 독촉이 before 보다 이전(또는 없음)일 때만 기록 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SurveyEntity s set s.lastRemindedAt = :now where s.id = :id and (s.lastRemindedAt is null or s.lastRemindedAt < :before)")
    fun touchReminder(@Param("id") id: UUID, @Param("before") before: Instant, @Param("now") now: Instant): Int
}

interface SurveyQuestionJpaRepository : JpaRepository<SurveyQuestionEntity, UUID> {
    fun findBySurveyIdInOrderBySurveyIdAscPositionAsc(surveyIds: Collection<UUID>): List<SurveyQuestionEntity>
}

interface SurveyRecipientJpaRepository : JpaRepository<SurveyRecipientEntity, SurveyStudentKey> {
    fun findBySurveyId(surveyId: UUID): List<SurveyRecipientEntity>
}

interface SurveyResponseJpaRepository : JpaRepository<SurveyResponseEntity, SurveyStudentKey> {
    fun findBySurveyId(surveyId: UUID): List<SurveyResponseEntity>

    /** 원생당 응답 1건 — 이미 있으면 0 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """insert into survey_response (survey_id, student_id, guardian_user_id, answers, submitted_at)
                   values (:s, :st, :g, cast(:answers as jsonb), :now) on conflict (survey_id, student_id) do nothing""",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("s") surveyId: UUID, @Param("st") studentId: UUID, @Param("g") guardianUserId: UUID,
        @Param("answers") answersJson: String, @Param("now") now: Instant,
    ): Int

    /** 응답 + 원생명·보호자명 */
    @Query(
        """select r, s.name, g.name from SurveyResponseEntity r
             join StudentEntity s on s.id = r.studentId join UserEntity g on g.id = r.guardianUserId
            where r.surveyId = :id order by r.submittedAt""",
    )
    fun findViews(@Param("id") surveyId: UUID): List<Array<Any?>>
}
