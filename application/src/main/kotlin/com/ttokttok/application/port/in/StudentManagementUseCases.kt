package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.ImportError
import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.invitation.JoinRequestId
import com.ttokttok.domain.invitation.JoinRequestStatus
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.student.WithdrawalReason
import com.ttokttok.domain.user.Role
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ───────── 원생 목록·상세 (STU-001, STU-006~008, STU-012) ─────────

interface SearchStudentsQuery {
    fun search(command: Command): PageResult<StudentView>
    data class Command(
        val actor: UserId, val institutionId: InstitutionId,
        val classroomId: ClassroomId?, val status: StudentStatus?, val keyword: String?,
        val page: Int = 0, val size: Int = 20,
    )
}

interface GetStudentDetailQuery {
    fun get(actor: UserId, institutionId: InstitutionId, studentId: StudentId): StudentDetailView
}

data class StudentDetailView(
    val student: StudentView,
    val memo: String?,
    val classHistory: List<ClassHistoryItem>,
    val statusHistory: List<StatusHistoryItem>,
    /** 같은 보호자 계정에 묶인 형제자매 (STU-008) */
    val siblings: List<SiblingView>,
)

data class ClassHistoryItem(val classroomId: ClassroomId, val classroomName: String, val fromDate: LocalDate, val toDate: LocalDate?)
data class StatusHistoryItem(val from: StudentStatus, val to: StudentStatus, val reason: WithdrawalReason?, val effectiveDate: LocalDate, val note: String?)
data class SiblingView(val studentId: StudentId, val name: String)

interface UpdateStudentUseCase {
    fun update(command: Command): StudentView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId,
        val name: String?, val birthDate: LocalDate?, val grade: String?, val memo: String?,
    )
}

/** 반 이동 — 이전 소속은 종료일로 마감, 새 소속 시작 (History) */
interface MoveStudentClassUseCase {
    fun move(command: Command): StudentView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId,
        val fromClassroomId: ClassroomId?, val toClassroomId: ClassroomId, val effectiveDate: LocalDate?,
    )
}

/** 퇴원·휴원·복귀 (STU-012) */
interface ChangeStudentStatusUseCase {
    fun change(command: Command): StudentView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId,
        val status: StudentStatus, val reason: WithdrawalReason?, val effectiveDate: LocalDate?, val note: String?,
    )
}

/** 보호자 추가·연결 해제 (STU-007) */
interface ManageGuardianUseCase {
    fun add(command: AddCommand): StudentView
    fun unlink(actor: UserId, institutionId: InstitutionId, studentId: StudentId, guardianId: GuardianId): StudentView
    data class AddCommand(
        val actor: UserId, val institutionId: InstitutionId, val studentId: StudentId,
        val phone: String, val relation: String?, val isPrimary: Boolean, val sendInstallGuide: Boolean,
    )
}

// ───────── 엑셀 일괄 업로드 (STU-002) ─────────

interface StudentImportUseCase {
    fun template(actor: UserId, institutionId: InstitutionId): ByteArray
    fun validate(actor: UserId, institutionId: InstitutionId, file: ByteArray): ImportResult
    fun result(actor: UserId, institutionId: InstitutionId, jobId: UUID): ImportResult
    fun errorReport(actor: UserId, institutionId: InstitutionId, jobId: UUID): ByteArray
    fun commit(actor: UserId, institutionId: InstitutionId, jobId: UUID, sendInstallGuide: Boolean): CommitResult

    data class ImportResult(val jobId: UUID, val totalRows: Int, val validRows: Int, val errors: List<ImportError>, val committed: Boolean)
    data class CommitResult(val created: Int)
}

// ───────── 초대·가입 승인 (STU-004, STU-005) ─────────

interface InviteParentUseCase {
    fun invite(actor: UserId, institutionId: InstitutionId, phone: String): InvitationView
    data class InvitationView(val token: String, val expiresAt: Instant)
}

/** 공개(토큰) 진입점 — 로그인 전 학부모가 링크를 열어 자녀 정보를 제출 */
interface JoinByInvitationUseCase {
    fun info(token: String): InvitationInfo
    fun submit(command: Command): JoinRequestId
    data class InvitationInfo(val institutionName: String, val phoneMasked: String, val expiresAt: Instant)
    data class Command(val token: String, val childName: String, val birthDate: LocalDate, val guardianName: String, val relation: String?)
}

interface ReviewJoinRequestUseCase {
    fun list(actor: UserId, institutionId: InstitutionId, status: JoinRequestStatus?): List<JoinRequestView>
    fun approve(actor: UserId, institutionId: InstitutionId, id: JoinRequestId, classroomId: ClassroomId): StudentView
    fun reject(actor: UserId, institutionId: InstitutionId, id: JoinRequestId, reason: String?)

    data class JoinRequestView(
        val id: JoinRequestId, val childName: String, val birthDate: LocalDate,
        val guardianName: String, val guardianPhoneMasked: String, val relation: String?,
        val status: JoinRequestStatus, val submittedAt: Instant,
    )
}

// ───────── 반·교직원 (CLS-001~002, STF-001, STF-003) ─────────

interface ManageClassroomUseCase {
    fun update(command: UpdateCommand): Classroom
    fun assignTeachers(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, teacherIds: Set<UserId>): Classroom
    fun delete(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId)
    data class UpdateCommand(
        val actor: UserId, val institutionId: InstitutionId, val classroomId: ClassroomId,
        val name: String?, val capacity: Int?, val days: Set<DayOfWeek>?, val startTime: LocalTime?, val endTime: LocalTime?,
    )
}

/** 반 목록에 현재 인원을 함께 (CLS-002) */
interface ClassroomSummaryQuery {
    fun summaries(actor: UserId, institutionId: InstitutionId): List<ClassroomSummary>
    data class ClassroomSummary(val classroom: Classroom, val headcount: Int, val teacherNames: List<String>)
}

interface ListStaffQuery {
    fun list(actor: UserId, institutionId: InstitutionId): List<StaffItem>
    data class StaffItem(
        val userId: UserId, val name: String, val email: String?, val role: Role, val title: String?,
        val classrooms: List<Pair<ClassroomId, String>>,
    )
}

// ───────── 계정 (AUTH-004, AUTH-005) ─────────

interface PasswordUseCase {
    /** 가입 이메일로 임시 비밀번호 발송. 계정 존재 여부는 응답으로 드러내지 않는다 */
    fun issueTemporary(email: String)
    fun change(actor: UserId, currentPassword: String, newPassword: String)
}
