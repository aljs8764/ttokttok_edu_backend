package com.ttokttok.domain.classroom

import com.ttokttok.domain.common.ClassroomId
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
    fun isTaughtBy(userId: UserId) = userId in teacherIds
}
