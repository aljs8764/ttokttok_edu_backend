package com.ttokttok.application.port.`in`

import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.destination.DestinationType
import java.time.DayOfWeek
import java.time.LocalTime

/** 반 생성 + 담임 배정 (CLS-001, STF-003) */
interface CreateClassroomUseCase {
    fun create(command: Command): Classroom
    data class Command(
        val actor: UserId, val institutionId: InstitutionId, val name: String, val capacity: Int,
        val days: Set<DayOfWeek>, val startTime: LocalTime, val endTime: LocalTime, val teacherIds: Set<UserId>,
    )
}

/** 교사는 담당 반만, 관리자는 전체 */
interface ListClassroomsQuery {
    fun list(actor: UserId, institutionId: InstitutionId): List<Classroom>
}

/** 다음 목적지 관리 (SET-002) */
interface ManageDestinationUseCase {
    fun create(command: CreateCommand): Destination
    fun list(actor: UserId, institutionId: InstitutionId): List<Destination>
    data class CreateCommand(val actor: UserId, val institutionId: InstitutionId, val name: String, val type: DestinationType, val sortOrder: Int)
}
