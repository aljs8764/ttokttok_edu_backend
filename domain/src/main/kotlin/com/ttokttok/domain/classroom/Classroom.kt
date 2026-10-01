package com.ttokttok.domain.classroom

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.UserId
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class Classroom(
    val id: ClassroomId,
    val institutionId: InstitutionId,
    val name: String,
    val capacity: Int,
    val days: Set<DayOfWeek>,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val teacherIds: Set<UserId> = emptySet(),
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "반 이름은 필수입니다")
        if (capacity <= 0) throw InvalidInputException("INVALID_CAPACITY", "정원은 1명 이상입니다")
        if (days.isEmpty()) throw InvalidInputException("INVALID_DAYS", "수업 요일을 하나 이상 지정하세요")
        if (!endTime.isAfter(startTime)) throw InvalidInputException("INVALID_TIME", "종료 시간은 시작 시간 이후여야 합니다")
    }

    fun isHeldOn(date: LocalDate) = date.dayOfWeek in days

    /** CLS-001 수정. 정원을 현재 인원보다 줄일 수 없다. */
    fun update(name: String?, capacity: Int?, days: Set<DayOfWeek>?, startTime: LocalTime?, endTime: LocalTime?, currentHeadcount: Int): Classroom {
        val next = copy(
            name = name?.trim() ?: this.name, capacity = capacity ?: this.capacity, days = days ?: this.days,
            startTime = startTime ?: this.startTime, endTime = endTime ?: this.endTime,
        )
        if (next.capacity < currentHeadcount) throw InvalidInputException("CAPACITY_BELOW_HEADCOUNT", "현재 인원(${currentHeadcount}명)보다 정원을 줄일 수 없습니다")
        return next
    }

    /** STF-003 담당 교사 재배정 */
    fun assignTeachers(teachers: Set<UserId>): Classroom = copy(teacherIds = teachers)

    /** CLS-002 소속 학생이 0명일 때만 삭제 */
    fun ensureDeletable(currentHeadcount: Int) {
        if (currentHeadcount > 0) throw ConflictException("CLASS_NOT_EMPTY", "소속 학생이 ${currentHeadcount}명 있어 삭제할 수 없습니다. 먼저 반 이동하세요")
    }
    fun isTaughtBy(userId: UserId) = userId in teacherIds
}
