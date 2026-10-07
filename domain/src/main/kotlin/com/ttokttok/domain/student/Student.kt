package com.ttokttok.domain.student

import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.GuardianId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import java.time.LocalDate

enum class StudentStatus { ACTIVE, PAUSED, WITHDRAWN }

data class Student(
    val id: StudentId,
    val institutionId: InstitutionId,
    val name: String,
    val birthDate: LocalDate,
    val grade: String? = null,
    val status: StudentStatus = StudentStatus.ACTIVE,
    val memo: String? = null,
    /** 가족의 아이 (스펙 7-8). 보호자 계정이 연결되기 전에는 null */
    val childId: ChildId? = null,
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "원생 이름은 필수입니다")
        if ((memo?.length ?: 0) > 2000) throw InvalidInputException("MEMO_TOO_LONG", "메모는 2000자 이내입니다")
    }

    /** STU-006 기본정보 수정 */
    fun updateInfo(name: String?, birthDate: LocalDate?, grade: String?, memo: String?): Student =
        copy(name = name?.trim() ?: this.name, birthDate = birthDate ?: this.birthDate, grade = grade ?: this.grade, memo = memo ?: this.memo)

    /**
     * STU-012 재원 상태 변경. 퇴원은 사유 필수(통계 반영), 같은 상태로의 변경은 거부.
     * 퇴원 후 복귀(재등록)는 ACTIVE 로 변경.
     */
    fun changeStatus(to: StudentStatus, reason: WithdrawalReason?, effectiveDate: LocalDate, note: String?): StatusTransition {
        if (to == status) throw ConflictException("SAME_STATUS", "이미 $status 상태입니다")
        if (to == StudentStatus.WITHDRAWN && reason == null) throw InvalidInputException("REASON_REQUIRED", "퇴원 사유를 선택하세요")
        return StatusTransition(
            copy(status = to),
            StudentStatusChange(id, institutionId, status, to, reason, effectiveDate, note?.take(500)),
        )
    }

    data class StatusTransition(val student: Student, val change: StudentStatusChange)
}

/** 퇴원 사유 (STAT-006 퇴원 사유 통계 분류) */
enum class WithdrawalReason { MOVING, GRADES, OTHER_ACADEMY, SCHEDULE, COST, GRADUATION, OTHER }

data class StudentStatusChange(
    val studentId: StudentId,
    val institutionId: InstitutionId,
    val from: StudentStatus,
    val to: StudentStatus,
    val reason: WithdrawalReason?,
    val effectiveDate: LocalDate,
    val note: String?,
)

/** 반 소속 이력. toDate == null 이면 현재 소속. */
data class Enrollment(
    val studentId: StudentId,
    val classroomId: ClassroomId,
    val fromDate: LocalDate,
    val toDate: LocalDate? = null,
) {
    val isCurrent get() = toDate == null
}

enum class GuardianLinkStatus { PENDING, LINKED, UNLINKED }

/**
 * 학생–보호자 매핑. 관리자가 번호로 등록(PENDING)하고,
 * 같은 번호로 학부모가 가입하면 LINKED 된다. 모든 알림의 수신 대상이 여기서 결정된다.
 */
data class Guardian(
    val id: GuardianId,
    val institutionId: InstitutionId,
    val studentId: StudentId,
    val phone: PhoneNumber,
    val relation: String?,
    val isPrimary: Boolean,
    val userId: UserId? = null,
    val linkStatus: GuardianLinkStatus = GuardianLinkStatus.PENDING,
) {
    fun linkTo(user: UserId): Guardian = copy(userId = user, linkStatus = GuardianLinkStatus.LINKED)
    fun unlink(): Guardian = copy(userId = null, linkStatus = GuardianLinkStatus.UNLINKED)
}
