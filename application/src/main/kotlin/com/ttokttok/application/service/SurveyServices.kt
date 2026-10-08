package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AnswerInput
import com.ttokttok.application.port.`in`.ParentSurveyUseCase
import com.ttokttok.application.port.`in`.QuestionResult
import com.ttokttok.application.port.`in`.QuestionType
import com.ttokttok.application.port.`in`.Respondent
import com.ttokttok.application.port.`in`.StaffSurveyUseCase
import com.ttokttok.application.port.`in`.SurveyQuestionView
import com.ttokttok.application.port.`in`.SurveyResults
import com.ttokttok.application.port.`in`.SurveyStatus
import com.ttokttok.application.port.`in`.SurveyView
import com.ttokttok.application.port.`in`.TextAnswer
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.NewQuestion
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.application.port.out.SurveyPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.messaging.ParentPushRequested
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.student.GuardianLinkStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val MAX_QUESTIONS = 20
private const val MAX_OPTIONS = 10
private val REMIND_COOLDOWN: Duration = Duration.ofMinutes(30)

private fun SurveyView.open(now: Instant) = status == SurveyStatus.OPEN && (closesAt == null || closesAt.isAfter(now))

@Service
class StaffSurveyService(
    private val surveys: SurveyPort,
    private val audience: NoticeAudience,
    private val guard: AccessGuard,
    private val guardians: GuardianPort,
    private val outbox: OutboxPort,
    private val realtime: RealtimePort,
    private val clock: ClockPort,
) : StaffSurveyUseCase {

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, command: StaffSurveyUseCase.Create): SurveyView {
        audience.authorize(actor, institutionId, NoticeKind.NOTE, command.targets)
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "제목은 1~100자입니다")
        if ((command.description?.length ?: 0) > 1000) throw InvalidInputException("INVALID_DESCRIPTION", "설명은 1000자까지입니다")
        val now = clock.now()
        if (command.closesAt != null && !command.closesAt.isAfter(now)) throw InvalidInputException("INVALID_CLOSE", "마감 시각은 지금 이후여야 합니다")
        if (command.targets.isEmpty()) throw InvalidInputException("TARGET_REQUIRED", "받는 대상을 지정하세요")
        if (command.questions.isEmpty() || command.questions.size > MAX_QUESTIONS) throw InvalidInputException("INVALID_QUESTIONS", "질문은 1~${MAX_QUESTIONS}개입니다")
        val questions = command.questions.map { q ->
            val text = q.text.trim()
            if (text.isEmpty() || text.length > 300) throw InvalidInputException("INVALID_QUESTION", "질문은 1~300자입니다")
            val options = q.options.map { it.trim() }.filter { it.isNotEmpty() }
            when (q.type) {
                QuestionType.SINGLE, QuestionType.MULTI -> {
                    if (options.size !in 2..MAX_OPTIONS || options.distinct().size != options.size)
                        throw InvalidInputException("INVALID_OPTIONS", "선택지는 서로 다른 2~${MAX_OPTIONS}개여야 합니다")
                    if (options.any { it.length > 100 }) throw InvalidInputException("INVALID_OPTIONS", "선택지는 100자까지입니다")
                }
                QuestionType.TEXT, QuestionType.RATING -> Unit
            }
            NewQuestion(q.type, text, if (q.type == QuestionType.SINGLE || q.type == QuestionType.MULTI) options else emptyList(), q.required)
        }
        val students = audience.expand(institutionId, command.targets)
        if (students.isEmpty()) throw InvalidInputException("NO_RECIPIENTS", "받을 원생이 없습니다")
        val id = surveys.insert(institutionId, actor, title, command.description?.trim()?.ifEmpty { null }, command.closesAt, now, questions, students.map { it.id.value })
        val view = surveys.find(id) ?: throw NotFoundException("설문")

        val users = guardians.findByStudents(students.map { it.id })
            .filter { it.linkStatus == GuardianLinkStatus.LINKED }.mapNotNull { it.userId }.distinct()
        if (users.isNotEmpty()) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "SURVEY_NEW", "[${view.institutionName}] 설문조사", title,
                    mapOf("type" to "survey", "surveyId" to id.toString()), users, now,
                ),
            )
            realtime.userEvents(users, mapOf("type" to "survey.new", "surveyId" to id.toString()))
        }
        return view
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId, page: Int, size: Int): List<SurveyView> {
        val m = guard.requireStaff(actor, institutionId)
        if (page < 0 || size !in 1..100) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~100입니다")
        val now = clock.now()
        return surveys.findForStaff(institutionId, if (m.role.isManager) null else actor, size, page * size).map { it.effective(now) }
    }

    @Transactional(readOnly = true)
    override fun results(actor: UserId, institutionId: InstitutionId, id: UUID): SurveyResults {
        val survey = access(actor, institutionId, id).effective(clock.now())
        val rows = surveys.responses(id)
        val byQuestion = rows.flatMap { r -> r.answers.map { a -> r to a } }.groupBy { it.second.questionId }
        val results = survey.questions.map { q ->
            val answers = byQuestion[q.id].orEmpty()
            val counts = LinkedHashMap<String, Int>()
            when (q.type) {
                QuestionType.SINGLE, QuestionType.MULTI -> q.options.forEach { counts[it] = 0 }
                QuestionType.RATING -> (1..5).forEach { counts[it.toString()] = 0 }
                QuestionType.TEXT -> Unit
            }
            answers.forEach { (_, a) -> if (q.type != QuestionType.TEXT) a.values.forEach { v -> counts.merge(v, 1, Int::plus) } }
            val average = if (q.type == QuestionType.RATING) {
                val nums = answers.mapNotNull { (_, a) -> a.values.firstOrNull()?.toIntOrNull() }
                if (nums.isEmpty()) null else nums.average()
            } else null
            val texts = if (q.type == QuestionType.TEXT) answers.mapNotNull { (r, a) -> a.values.firstOrNull()?.takeIf { it.isNotBlank() }?.let { TextAnswer(r.studentName, it) } } else emptyList()
            QuestionResult(q, counts, average, texts)
        }
        val responded = rows.map { it.studentId }.toSet()
        val missing = surveys.studentNames(surveys.recipientStudentIds(id).filter { it !in responded }).values.sorted()
        return SurveyResults(survey, results, rows.map { Respondent(it.studentId, it.studentName, it.guardianName, it.submittedAt) }, missing)
    }

    @Transactional
    override fun close(actor: UserId, institutionId: InstitutionId, id: UUID): SurveyView {
        access(actor, institutionId, id)
        surveys.close(id)
        return (surveys.find(id) ?: throw NotFoundException("설문")).effective(clock.now())
    }

    @Transactional
    override fun remind(actor: UserId, institutionId: InstitutionId, id: UUID): Int {
        val survey = access(actor, institutionId, id)
        val now = clock.now()
        if (!survey.open(now)) throw ConflictException("SURVEY_CLOSED", "마감된 설문입니다")
        val pending = surveys.recipientStudentIds(id).toSet() - surveys.respondedStudentIds(id)
        if (pending.isEmpty()) return 0
        if (!surveys.touchReminder(id, now.minus(REMIND_COOLDOWN), now)) throw ConflictException("REMIND_COOLDOWN", "독촉은 30분에 한 번만 보낼 수 있습니다")
        val users = guardians.findByStudents(pending.map(::StudentId))
            .filter { it.linkStatus == GuardianLinkStatus.LINKED }.mapNotNull { it.userId }.distinct()
        if (users.isNotEmpty()) {
            outbox.publish(
                ParentPushRequested(
                    institutionId, "SURVEY_REMINDER", "[${survey.institutionName}] 설문 참여 부탁드려요", survey.title,
                    mapOf("type" to "survey", "surveyId" to id.toString()), users, now,
                ),
            )
        }
        return users.size
    }

    private fun access(actor: UserId, institutionId: InstitutionId, id: UUID): SurveyView {
        val m = guard.requireStaff(actor, institutionId)
        val survey = surveys.find(id)?.takeIf { it.institutionId == institutionId.value } ?: throw NotFoundException("설문")
        if (!m.role.isManager && survey.authorId != actor.value) throw ForbiddenException("본인이 만든 설문만 볼 수 있습니다")
        return survey
    }

    private fun SurveyView.effective(now: Instant) = if (open(now)) this else copy(status = SurveyStatus.CLOSED)
}

@Service
class ParentSurveyService(
    private val surveys: SurveyPort,
    private val guardians: GuardianPort,
    private val clock: ClockPort,
) : ParentSurveyUseCase {

    private fun myStudents(parent: UserId) = guardians.findLinkedByUser(parent).filter { it.linkStatus == GuardianLinkStatus.LINKED }.map { it.studentId.value }.distinct()

    @Transactional(readOnly = true)
    override fun list(parent: UserId): List<ParentSurveyUseCase.ParentSurveyItem> {
        val mine = myStudents(parent)
        if (mine.isEmpty()) return emptyList()
        val now = clock.now()
        return surveys.findForStudents(mine).map { item(it, mine, now) }.sortedWith(compareByDescending<ParentSurveyUseCase.ParentSurveyItem> { it.survey.status == SurveyStatus.OPEN }.thenByDescending { it.survey.createdAt })
    }

    @Transactional(readOnly = true)
    override fun detail(parent: UserId, id: UUID): ParentSurveyUseCase.ParentSurveyItem {
        val mine = myStudents(parent)
        val survey = surveys.find(id) ?: throw NotFoundException("설문")
        if (mine.none { surveys.isRecipient(id, it) }) throw ForbiddenException("받은 설문이 아닙니다")
        return item(survey, mine, clock.now())
    }

    @Transactional
    override fun submit(parent: UserId, id: UUID, studentId: UUID, answers: List<AnswerInput>) {
        val mine = myStudents(parent)
        if (studentId !in mine) throw ForbiddenException("본인 자녀가 아닙니다")
        val survey = surveys.find(id) ?: throw NotFoundException("설문")
        if (!surveys.isRecipient(id, studentId)) throw ForbiddenException("받은 설문이 아닙니다")
        val now = clock.now()
        if (!survey.open(now)) throw ConflictException("SURVEY_CLOSED", "마감된 설문입니다")
        val checked = validate(survey.questions, answers)
        if (!surveys.insertResponse(id, studentId, parent, checked, now)) throw ConflictException("ALREADY_ANSWERED", "이미 응답했습니다")
    }

    private fun item(survey: SurveyView, mine: List<UUID>, now: Instant): ParentSurveyUseCase.ParentSurveyItem {
        val recipients = mine.filter { surveys.isRecipient(survey.id, it) }
        val responded = surveys.respondedStudentIds(survey.id)
        val names = surveys.studentNames(recipients)
        return ParentSurveyUseCase.ParentSurveyItem(
            if (survey.open(now)) survey else survey.copy(status = SurveyStatus.CLOSED),
            recipients.map { ParentSurveyUseCase.ChildAnswerState(it, names[it] ?: "", it in responded) },
        )
    }

    private fun validate(questions: List<SurveyQuestionView>, answers: List<AnswerInput>): List<AnswerInput> {
        val byId = questions.associateBy { it.id }
        if (answers.any { it.questionId !in byId }) throw InvalidInputException("UNKNOWN_QUESTION", "없는 질문입니다")
        if (answers.map { it.questionId }.distinct().size != answers.size) throw InvalidInputException("DUPLICATE_ANSWER", "같은 질문에 두 번 답할 수 없습니다")
        val given = answers.associateBy { it.questionId }
        return questions.mapNotNull { q ->
            val values = given[q.id]?.values?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (values.isEmpty()) {
                if (q.required) throw InvalidInputException("ANSWER_REQUIRED", "'${q.text}' 에 답해 주세요")
                return@mapNotNull null
            }
            when (q.type) {
                QuestionType.SINGLE -> if (values.size != 1 || values[0] !in q.options) throw InvalidInputException("INVALID_ANSWER", "'${q.text}' 의 선택이 올바르지 않습니다")
                QuestionType.MULTI -> if (values.distinct().size != values.size || !q.options.containsAll(values)) throw InvalidInputException("INVALID_ANSWER", "'${q.text}' 의 선택이 올바르지 않습니다")
                QuestionType.RATING -> if (values.size != 1 || values[0].toIntOrNull() !in 1..5) throw InvalidInputException("INVALID_ANSWER", "'${q.text}' 는 1~5점입니다")
                QuestionType.TEXT -> if (values.size != 1 || values[0].length > 1000) throw InvalidInputException("INVALID_ANSWER", "서술 답변은 1000자까지입니다")
            }
            AnswerInput(q.id, values)
        }
    }
}
