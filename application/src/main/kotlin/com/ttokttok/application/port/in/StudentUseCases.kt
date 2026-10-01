package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.GuardianLinkStatus
import com.ttokttok.domain.student.StudentStatus
import java.time.LocalDate

/** 원생 개별 등록 (STU-003) + 반 배정 + 보호자 매핑 (STU-007) */
interface RegisterStudentUseCase {
    fun register(command: Command): StudentView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val name: String, val birthDate: LocalDate,
        val grade: String?, val classroomId: ClassroomId, val guardians: List<GuardianInput>,
    )
    data class GuardianInput(val phone: String, val relation: String?, val isPrimary: Boolean)
}

/** 원생 목록 (STU-001 최소형). 교사는 담당 반 학생만, 보호자 연락처는 마스킹 */
interface ListStudentsQuery {
    fun list(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId?): List<StudentView>
}

data class StudentView(
    val id: StudentId,
    val name: String,
    val birthDate: LocalDate?,
    val grade: String?,
    val status: StudentStatus,
    val classroomIds: List<ClassroomId>,
    val guardians: List<GuardianView>,
)

data class GuardianView(val phoneMasked: String, val relation: String?, val isPrimary: Boolean, val linkStatus: GuardianLinkStatus)
