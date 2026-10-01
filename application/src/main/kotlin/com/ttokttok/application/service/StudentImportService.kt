package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.StudentImportUseCase
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.ImportCandidate
import com.ttokttok.application.port.out.ImportError
import com.ttokttok.application.port.out.ImportJob
import com.ttokttok.application.port.out.ImportJobPort
import com.ttokttok.application.port.out.ImportJobStatus
import com.ttokttok.application.port.out.SpreadsheetPort
import com.ttokttok.application.port.out.SpreadsheetRow
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DomainException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * STU-002 엑셀 일괄 업로드.
 * 1) 템플릿 다운로드 → 2) 업로드 시 전 행 검증, 오류 행만 별도 표시 → 3) 정상 행만 확정 등록
 * 검증과 확정을 나눠, 원장이 오류를 보고 고칠지·정상 행만 넣을지 고를 수 있게 한다.
 */
@Service
class StudentImportService(
    private val guard: AccessGuard,
    private val sheets: SpreadsheetPort,
    private val jobs: ImportJobPort,
    private val classrooms: ClassroomPort,
    private val students: StudentPort,
    private val guardians: GuardianPort,
    private val enrollments: EnrollmentPort,
    private val creator: StudentCreator,
    private val clock: ClockPort,
) : StudentImportUseCase {

    override fun template(actor: UserId, institutionId: InstitutionId): ByteArray {
        guard.requireManager(actor, institutionId)
        val classNames = classrooms.findByInstitution(institutionId).map { it.name }.sorted()
        return sheets.write(
            sheetName = "원생등록",
            headers = HEADERS,
            rows = listOf(listOf("홍길동", "2017-03-02", classNames.firstOrNull() ?: "초등 3반", "010-1234-5678", "모", "초3", "")),
            notes = listOf(
                "* 표시는 필수입니다. 예시 행은 지우고 입력하세요. (최대 $MAX_ROWS 행)",
                "생년월일 형식: YYYY-MM-DD",
                "반명은 등록된 반 이름과 정확히 같아야 합니다: " + classNames.joinToString(", ").ifEmpty { "(아직 등록된 반이 없습니다)" },
            ),
        )
    }

    @Transactional
    override fun validate(actor: UserId, institutionId: InstitutionId, file: ByteArray): StudentImportUseCase.ImportResult {
        guard.requireManager(actor, institutionId)
        val rows = sheets.read(file, MAX_ROWS).filterNot { r -> r.cells.values.all { it.isBlank() } }
        if (rows.isEmpty()) throw InvalidInputException("EMPTY_FILE", "입력된 행이 없습니다")

        val classByName = classrooms.findByInstitution(institutionId).associateBy { it.name.trim() }
        // DB 중복: 이름+생년월일+보호자 번호가 같은 재원생
        val existingKeys = students.findByInstitution(institutionId).flatMap { s ->
            guardians.findByStudent(s.id).map { key(s.name, s.birthDate, it.phone) }
        }.toSet()

        val errors = mutableListOf<ImportError>()
        val candidates = mutableListOf<ImportCandidate>()
        val seenInFile = mutableMapOf<String, Int>()

        rows.forEach { row ->
            val rowErrors = mutableListOf<ImportError>()
            fun err(col: String, msg: String) { rowErrors += ImportError(row.rowNumber, col, msg) }

            val name = row.get(H_NAME)
            if (name.isEmpty()) err(H_NAME, "필수값 누락") else if (name.length > 50) err(H_NAME, "50자 이내")
            val birth = row.get(H_BIRTH).let { raw ->
                if (raw.isEmpty()) { err(H_BIRTH, "필수값 누락"); null }
                else try { LocalDate.parse(raw).also { if (it.isAfter(clock.today())) err(H_BIRTH, "미래 날짜") } }
                catch (e: DateTimeParseException) { err(H_BIRTH, "YYYY-MM-DD 형식이 아닙니다: $raw"); null }
            }
            val className = row.get(H_CLASS)
            if (className.isEmpty()) err(H_CLASS, "필수값 누락") else if (className !in classByName) err(H_CLASS, "등록되지 않은 반: $className")
            val phone = row.get(H_PHONE).let { raw ->
                if (raw.isEmpty()) { err(H_PHONE, "필수값 누락"); null }
                else try { PhoneNumber.of(raw) } catch (e: DomainException) { err(H_PHONE, "연락처 형식 오류: $raw"); null }
            }

            if (rowErrors.isEmpty()) {
                val k = key(name, birth!!, phone!!)
                when {
                    k in existingKeys -> err(H_NAME, "이미 등록된 원생입니다")
                    k in seenInFile -> err(H_NAME, "파일 내 ${seenInFile[k]}행과 중복")
                    else -> seenInFile[k] = row.rowNumber
                }
            }
            if (rowErrors.isEmpty()) {
                candidates += ImportCandidate(
                    row.rowNumber, name, birth!!, className, phone!!,
                    row.get(H_RELATION).ifEmpty { null }, row.get(H_GRADE).ifEmpty { null }, row.get(H_MEMO).ifEmpty { null },
                )
            }
            errors += rowErrors
        }

        val job = jobs.save(ImportJob(Uuid7.next(), institutionId, actor, ImportJobStatus.VALIDATED, candidates, errors, rows.size, clock.now()))
        return job.toResult()
    }

    @Transactional(readOnly = true)
    override fun result(actor: UserId, institutionId: InstitutionId, jobId: UUID): StudentImportUseCase.ImportResult {
        guard.requireManager(actor, institutionId)
        return load(institutionId, jobId).toResult()
    }

    /** 오류 행만 엑셀로 내려받아 고친 뒤 다시 업로드 */
    @Transactional(readOnly = true)
    override fun errorReport(actor: UserId, institutionId: InstitutionId, jobId: UUID): ByteArray {
        guard.requireManager(actor, institutionId)
        val job = load(institutionId, jobId)
        return sheets.write(
            "오류행", listOf("행 번호", "항목", "오류 내용"),
            job.errors.map { listOf(it.rowNumber.toString(), it.column, it.message) },
        )
    }

    @Transactional
    override fun commit(actor: UserId, institutionId: InstitutionId, jobId: UUID, sendInstallGuide: Boolean): StudentImportUseCase.CommitResult {
        guard.requireManager(actor, institutionId)
        val job = load(institutionId, jobId)
        if (job.status == ImportJobStatus.COMMITTED) throw ConflictException("ALREADY_COMMITTED", "이미 등록이 완료된 업로드입니다")
        if (Duration.between(job.createdAt, clock.now()) > JOB_TTL) throw ConflictException("IMPORT_EXPIRED", "검증 후 24시간이 지났습니다. 다시 업로드하세요")

        val classByName = classrooms.findByInstitution(institutionId).associateBy { it.name.trim() }
        // 정원 초과는 확정 시점에 한 번에 판단 (검증 이후 다른 등록이 있었을 수 있음)
        job.candidates.groupBy { it.classroomName }.forEach { (name, rows) ->
            val c = classByName[name] ?: throw NotFoundException("반($name)")
            val free = c.capacity - enrollments.findCurrentStudentIds(c.id).size
            if (rows.size > free) throw InvalidInputException("CLASS_FULL", "$name: 남은 자리 ${free}명, 등록 대상 ${rows.size}명")
        }
        job.candidates.forEach { r ->
            creator.create(
                institutionId, classByName.getValue(r.classroomName), r.name, r.birthDate, r.grade, r.memo,
                listOf(StudentCreator.GuardianSpec(r.guardianPhone, r.relation, true)), sendInstallGuide,
            )
        }
        jobs.save(job.copy(status = ImportJobStatus.COMMITTED))
        return StudentImportUseCase.CommitResult(job.candidates.size)
    }

    private fun load(institutionId: InstitutionId, jobId: UUID) = jobs.find(jobId, institutionId) ?: throw NotFoundException("업로드 작업")

    private fun ImportJob.toResult() = StudentImportUseCase.ImportResult(id, totalRows, candidates.size, errors, status == ImportJobStatus.COMMITTED)

    private fun SpreadsheetRow.get(header: String) = cells[header]?.trim().orEmpty()

    private fun key(name: String, birth: LocalDate, phone: PhoneNumber) = "${name.trim()}|$birth|${phone.digits}"

    companion object {
        const val MAX_ROWS = 1000
        val JOB_TTL: Duration = Duration.ofHours(24)
        const val H_NAME = "이름*"
        const val H_BIRTH = "생년월일*"
        const val H_CLASS = "반명*"
        const val H_PHONE = "보호자 연락처*"
        const val H_RELATION = "보호자 관계"
        const val H_GRADE = "학년"
        const val H_MEMO = "메모"
        val HEADERS = listOf(H_NAME, H_BIRTH, H_CLASS, H_PHONE, H_RELATION, H_GRADE, H_MEMO)
    }
}
