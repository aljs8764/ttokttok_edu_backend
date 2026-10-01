package com.ttokttok.domain.student

import com.ttokttok.domain.common.ClassroomId
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
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "원생 이름은 필수입니다")
    }
}

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
