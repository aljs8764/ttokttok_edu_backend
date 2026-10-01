package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.CreateClassroomUseCase
import com.ttokttok.application.port.`in`.CreateStaffUseCase
import com.ttokttok.application.port.`in`.ListClassroomsQuery
import com.ttokttok.application.port.`in`.ListStudentsQuery
import com.ttokttok.application.port.`in`.ManageDestinationUseCase
import com.ttokttok.application.port.`in`.RegisterStudentUseCase
import com.ttokttok.application.port.`in`.StudentView
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.destination.DestinationType
import com.ttokttok.domain.user.Role
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 업무 API 공통: 활성 기관은 X-Institution-Id 헤더, 행위자는 JWT sub */
const val INSTITUTION_HEADER = "X-Institution-Id"
internal fun Jwt.userId() = UserId(UUID.fromString(subject))
internal fun inst(id: UUID) = InstitutionId(id)

@RestController
@RequestMapping("/api/v1/staff")
class StaffController(private val createStaff: CreateStaffUseCase) {
    data class CreateStaffRequest(
        @field:NotBlank val name: String, @field:NotBlank val email: String,
        @field:NotBlank val temporaryPassword: String, val role: Role = Role.TEACHER, val title: String? = null,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateStaffRequest) =
        createStaff.create(CreateStaffUseCase.Command(jwt.userId(), inst(institutionId), req.name, req.email, req.temporaryPassword, req.role, req.title))
            .let { mapOf("userId" to it.userId.value, "name" to it.name, "email" to it.email, "role" to it.role) }
}

@RestController
@RequestMapping("/api/v1/classes")
class ClassroomController(
    private val createClassroom: CreateClassroomUseCase,
    private val listClassrooms: ListClassroomsQuery,
) {
    data class CreateClassroomRequest(
        @field:NotBlank val name: String,
        @field:Min(1) val capacity: Int,
        @field:NotEmpty val days: Set<DayOfWeek>,
        val startTime: LocalTime,
        val endTime: LocalTime,
        val teacherIds: Set<UUID> = emptySet(),
    )

    data class ClassroomResponse(
        val id: UUID, val name: String, val capacity: Int, val days: List<DayOfWeek>,
        val startTime: LocalTime, val endTime: LocalTime, val teacherIds: List<UUID>,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateClassroomRequest) =
        createClassroom.create(
            CreateClassroomUseCase.Command(
                jwt.userId(), inst(institutionId), req.name, req.capacity, req.days, req.startTime, req.endTime, req.teacherIds.map { UserId(it) }.toSet(),
            ),
        ).toResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        listClassrooms.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    private fun Classroom.toResponse() =
        ClassroomResponse(id.value, name, capacity, days.sorted(), startTime, endTime, teacherIds.map { it.value })
}

@RestController
@RequestMapping("/api/v1/destinations")
class DestinationController(private val destinations: ManageDestinationUseCase) {
    data class CreateDestinationRequest(@field:NotBlank val name: String, val type: DestinationType, val sortOrder: Int = 0)
    data class DestinationResponse(val id: UUID, val name: String, val type: DestinationType, val sortOrder: Int)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateDestinationRequest) =
        destinations.create(ManageDestinationUseCase.CreateCommand(jwt.userId(), inst(institutionId), req.name, req.type, req.sortOrder)).toResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        destinations.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    private fun Destination.toResponse() = DestinationResponse(id.value, name, type, sortOrder)
}

@RestController
@RequestMapping("/api/v1/students")
class StudentController(
    private val registerStudent: RegisterStudentUseCase,
    private val listStudents: ListStudentsQuery,
) {
    data class GuardianRequest(@field:NotBlank val phone: String, val relation: String? = null, val isPrimary: Boolean = true)
    data class RegisterStudentRequest(
        @field:NotBlank val name: String,
        val birthDate: LocalDate,
        val grade: String? = null,
        val classroomId: UUID,
        @field:NotEmpty @field:Valid val guardians: List<GuardianRequest>,
    )

    data class StudentResponse(
        val id: UUID, val name: String, val birthDate: LocalDate?, val grade: String?, val status: String,
        val classroomIds: List<UUID>, val guardians: List<GuardianResponse>,
    )
    data class GuardianResponse(val phone: String, val relation: String?, val isPrimary: Boolean, val linkStatus: String)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: RegisterStudentRequest) =
        registerStudent.register(
            RegisterStudentUseCase.Command(
                jwt.userId(), inst(institutionId), req.name, req.birthDate, req.grade, ClassroomId(req.classroomId),
                req.guardians.map { RegisterStudentUseCase.GuardianInput(it.phone, it.relation, it.isPrimary) },
            ),
        ).toResponse()

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) classId: UUID?,
    ) = listStudents.list(jwt.userId(), inst(institutionId), classId?.let { ClassroomId(it) }).map { it.toResponse() }

    private fun StudentView.toResponse() = StudentResponse(
        id.value, name, birthDate, grade, status.name, classroomIds.map { it.value },
        guardians.map { GuardianResponse(it.phoneMasked, it.relation, it.isPrimary, it.linkStatus.name) },
    )
}
