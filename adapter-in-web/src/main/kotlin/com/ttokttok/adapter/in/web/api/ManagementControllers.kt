package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ChangeStudentStatusUseCase
import com.ttokttok.application.port.`in`.ClassroomSummaryQuery
import com.ttokttok.application.port.`in`.CreateClassroomUseCase
import com.ttokttok.application.port.`in`.CreateStaffUseCase
import com.ttokttok.application.port.`in`.GetStudentDetailQuery
import com.ttokttok.application.port.`in`.ListStaffQuery
import com.ttokttok.application.port.`in`.ManageClassroomUseCase
import com.ttokttok.application.port.`in`.ManageGuardianUseCase
import com.ttokttok.application.port.`in`.MoveStudentClassUseCase
import com.ttokttok.application.port.`in`.RegisterStudentUseCase
import com.ttokttok.application.port.`in`.SearchStudentsQuery
import com.ttokttok.application.port.`in`.StudentDetailView
import com.ttokttok.application.port.`in`.StudentImportUseCase
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.application.port.`in`.UpdateStudentUseCase
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.student.WithdrawalReason
import com.ttokttok.domain.user.Role
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ───────── 공통 응답 ─────────

data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Int)

internal fun <T, R> PageResult<T>.toResponse(f: (T) -> R) = PageResponse(items.map(f), page, size, totalElements, totalPages)

data class StudentResponse(
    val id: UUID, val name: String, val birthDate: LocalDate?, val grade: String?, val status: String,
    val classroomIds: List<UUID>, val guardians: List<GuardianResponse>,
)
data class GuardianResponse(val id: UUID, val phone: String, val relation: String?, val isPrimary: Boolean, val linkStatus: String)

internal fun StudentView.toResponse() = StudentResponse(
    id.value, name, birthDate, grade, status.name, classroomIds.map { it.value },
    guardians.map { GuardianResponse(it.id.value, it.phoneMasked, it.relation, it.isPrimary, it.linkStatus.name) },
)

private val XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

internal fun xlsx(bytes: ByteArray, filename: String): ResponseEntity<ByteArray> =
    ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
        .contentType(XLSX)
        .body(bytes)

// ───────── 원생 (STU-001~008, 012) ─────────

@RestController
@RequestMapping("/api/v1/students")
class StudentController(
    private val registerStudent: RegisterStudentUseCase,
    private val searchStudents: SearchStudentsQuery,
    private val detail: GetStudentDetailQuery,
    private val updateStudent: UpdateStudentUseCase,
    private val moveClass: MoveStudentClassUseCase,
    private val changeStatus: ChangeStudentStatusUseCase,
    private val guardians: ManageGuardianUseCase,
) {
    data class GuardianRequest(@field:NotBlank val phone: String, val relation: String? = null, val isPrimary: Boolean = true)
    data class RegisterStudentRequest(
        @field:NotBlank val name: String,
        val birthDate: LocalDate,
        val grade: String? = null,
        val memo: String? = null,
        val classroomId: UUID,
        @field:NotEmpty @field:Valid val guardians: List<GuardianRequest>,
        val sendInstallGuide: Boolean = true,
    )
    data class UpdateStudentRequest(val name: String? = null, val birthDate: LocalDate? = null, val grade: String? = null, val memo: String? = null)
    data class MoveClassRequest(val toClassroomId: UUID, val fromClassroomId: UUID? = null, val effectiveDate: LocalDate? = null)
    data class ChangeStatusRequest(val status: StudentStatus, val reason: WithdrawalReason? = null, val effectiveDate: LocalDate? = null, val note: String? = null)
    data class AddGuardianRequest(@field:NotBlank val phone: String, val relation: String? = null, val isPrimary: Boolean = false, val sendInstallGuide: Boolean = true)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: RegisterStudentRequest) =
        registerStudent.register(
            RegisterStudentUseCase.Command(
                jwt.userId(), inst(institutionId), req.name, req.birthDate, req.grade, ClassroomId(req.classroomId),
                req.guardians.map { RegisterStudentUseCase.GuardianInput(it.phone, it.relation, it.isPrimary) },
                req.memo, req.sendInstallGuide,
            ),
        ).toResponse()

    /** STU-001: 20건 페이징, 반·재원상태 필터, 이름·보호자 번호 뒷 4자리 검색 */
    @GetMapping
    fun search(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) classId: UUID?,
        @RequestParam(required = false) status: StudentStatus?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = searchStudents.search(
        SearchStudentsQuery.Command(jwt.userId(), inst(institutionId), classId?.let { ClassroomId(it) }, status, keyword, page, size),
    ).toResponse { it.toResponse() }

    @GetMapping("/{studentId}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable studentId: UUID) =
        detail.get(jwt.userId(), inst(institutionId), StudentId(studentId)).toResponse()

    @PatchMapping("/{studentId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable studentId: UUID, @RequestBody req: UpdateStudentRequest,
    ) = updateStudent.update(
        UpdateStudentUseCase.Command(jwt.userId(), inst(institutionId), StudentId(studentId), req.name, req.birthDate, req.grade, req.memo),
    ).toResponse()

    @PostMapping("/{studentId}/class-move")
    fun moveClass(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable studentId: UUID, @RequestBody req: MoveClassRequest,
    ) = moveClass.move(
        MoveStudentClassUseCase.Command(
            jwt.userId(), inst(institutionId), StudentId(studentId), req.fromClassroomId?.let { ClassroomId(it) }, ClassroomId(req.toClassroomId), req.effectiveDate,
        ),
    ).toResponse()

    @PostMapping("/{studentId}/status")
    fun changeStatus(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable studentId: UUID, @RequestBody req: ChangeStatusRequest,
    ) = changeStatus.change(
        ChangeStudentStatusUseCase.Command(jwt.userId(), inst(institutionId), StudentId(studentId), req.status, req.reason, req.effectiveDate, req.note),
    ).toResponse()

    @PostMapping("/{studentId}/guardians")
    @ResponseStatus(HttpStatus.CREATED)
    fun addGuardian(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable studentId: UUID, @Valid @RequestBody req: AddGuardianRequest,
    ) = guardians.add(
        ManageGuardianUseCase.AddCommand(jwt.userId(), inst(institutionId), StudentId(studentId), req.phone, req.relation, req.isPrimary, req.sendInstallGuide),
    ).toResponse()

    /** STU-007 학부모 계정 연결 해제 */
    @DeleteMapping("/{studentId}/guardians/{guardianId}")
    fun unlinkGuardian(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable studentId: UUID, @PathVariable guardianId: UUID,
    ) = guardians.unlink(jwt.userId(), inst(institutionId), StudentId(studentId), GuardianId(guardianId)).toResponse()

    private fun StudentDetailView.toResponse() = mapOf(
        "student" to student.toResponse(),
        "memo" to memo,
        "classHistory" to classHistory.map {
            mapOf("classroomId" to it.classroomId.value, "classroomName" to it.classroomName, "fromDate" to it.fromDate, "toDate" to it.toDate)
        },
        "statusHistory" to statusHistory.map {
            mapOf("from" to it.from, "to" to it.to, "reason" to it.reason, "effectiveDate" to it.effectiveDate, "note" to it.note)
        },
        "siblings" to siblings.map { mapOf("studentId" to it.studentId.value, "name" to it.name) },
    )
}

/** STU-002 엑셀 일괄 업로드: 템플릿 → 업로드(검증) → 오류행 확인 → 확정 */
@RestController
@RequestMapping("/api/v1/students/import")
class StudentImportController(private val importer: StudentImportUseCase) {
    data class CommitRequest(val sendInstallGuide: Boolean = true)

    @GetMapping("/template")
    fun template(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        xlsx(importer.template(jwt.userId(), inst(institutionId)), "똑똑_원생등록_템플릿.xlsx")

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestPart("file") file: MultipartFile) =
        importer.validate(jwt.userId(), inst(institutionId), file.bytes)

    @GetMapping("/{jobId}")
    fun result(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable jobId: UUID) =
        importer.result(jwt.userId(), inst(institutionId), jobId)

    @GetMapping("/{jobId}/errors")
    fun errors(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable jobId: UUID) =
        xlsx(importer.errorReport(jwt.userId(), inst(institutionId), jobId), "원생등록_오류행.xlsx")

    @PostMapping("/{jobId}/commit")
    fun commit(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable jobId: UUID, @RequestBody(required = false) req: CommitRequest?,
    ) = importer.commit(jwt.userId(), inst(institutionId), jobId, req?.sendInstallGuide ?: true)
}

// ───────── 반 (CLS-001~002, STF-003) ─────────

@RestController
@RequestMapping("/api/v1/classes")
class ClassroomController(
    private val createClassroom: CreateClassroomUseCase,
    private val manage: ManageClassroomUseCase,
    private val summaries: ClassroomSummaryQuery,
) {
    data class CreateClassroomRequest(
        @field:NotBlank val name: String,
        @field:Min(1) val capacity: Int,
        @field:NotEmpty val days: Set<DayOfWeek>,
        val startTime: LocalTime,
        val endTime: LocalTime,
        val teacherIds: Set<UUID> = emptySet(),
    )
    data class UpdateClassroomRequest(
        val name: String? = null, val capacity: Int? = null, val days: Set<DayOfWeek>? = null,
        val startTime: LocalTime? = null, val endTime: LocalTime? = null,
    )
    data class TeachersRequest(val teacherIds: Set<UUID>)

    data class ClassroomResponse(
        val id: UUID, val name: String, val capacity: Int, val days: List<DayOfWeek>,
        val startTime: LocalTime, val endTime: LocalTime, val teacherIds: List<UUID>,
        val headcount: Int? = null, val teacherNames: List<String>? = null,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateClassroomRequest) =
        createClassroom.create(
            CreateClassroomUseCase.Command(
                jwt.userId(), inst(institutionId), req.name, req.capacity, req.days, req.startTime, req.endTime, req.teacherIds.map { UserId(it) }.toSet(),
            ),
        ).toResponse()

    /** 반 목록 + 현재 인원 + 담당 교사 (교사는 담당 반만) */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        summaries.summaries(jwt.userId(), inst(institutionId)).map { it.classroom.toResponse(it.headcount, it.teacherNames) }

    @PatchMapping("/{classId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable classId: UUID, @RequestBody req: UpdateClassroomRequest,
    ) = manage.update(
        ManageClassroomUseCase.UpdateCommand(jwt.userId(), inst(institutionId), ClassroomId(classId), req.name, req.capacity, req.days, req.startTime, req.endTime),
    ).toResponse()

    @PutMapping("/{classId}/teachers")
    fun assignTeachers(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @PathVariable classId: UUID, @RequestBody req: TeachersRequest,
    ) = manage.assignTeachers(jwt.userId(), inst(institutionId), ClassroomId(classId), req.teacherIds.map { UserId(it) }.toSet()).toResponse()

    /** CLS-002: 소속 학생 0명일 때만 (있으면 409 CLASS_NOT_EMPTY) */
    @DeleteMapping("/{classId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable classId: UUID) {
        manage.delete(jwt.userId(), inst(institutionId), ClassroomId(classId))
    }

    private fun Classroom.toResponse(headcount: Int? = null, teacherNames: List<String>? = null) =
        ClassroomResponse(id.value, name, capacity, days.sorted(), startTime, endTime, teacherIds.map { it.value }, headcount, teacherNames)
}

// ───────── 교직원 (STF-001~002) ─────────

@RestController
@RequestMapping("/api/v1/staff")
class StaffController(
    private val createStaff: CreateStaffUseCase,
    private val listStaff: ListStaffQuery,
) {
    data class CreateStaffRequest(
        @field:NotBlank val name: String, @field:NotBlank val email: String,
        @field:NotBlank val temporaryPassword: String, val role: Role = Role.TEACHER, val title: String? = null,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateStaffRequest) =
        createStaff.create(CreateStaffUseCase.Command(jwt.userId(), inst(institutionId), req.name, req.email, req.temporaryPassword, req.role, req.title))
            .let { mapOf("userId" to it.userId.value, "name" to it.name, "email" to it.email, "role" to it.role) }

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        listStaff.list(jwt.userId(), inst(institutionId)).map { s ->
            mapOf(
                "userId" to s.userId.value, "name" to s.name, "email" to s.email, "role" to s.role, "title" to s.title,
                "classrooms" to s.classrooms.map { (id, name) -> mapOf("id" to id.value, "name" to name) },
            )
        }
}
