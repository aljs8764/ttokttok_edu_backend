package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.CreateClassroomUseCase
import com.ttokttok.application.port.`in`.ListClassroomsQuery
import com.ttokttok.application.port.`in`.ManageDestinationUseCase
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateClassroomService(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
    private val memberships: MembershipPort,
) : CreateClassroomUseCase {
    @Transactional
    override fun create(command: CreateClassroomUseCase.Command): Classroom {
        guard.requireManager(command.actor, command.institutionId)
        command.teacherIds.forEach { teacherId ->
            val m = memberships.find(teacherId, command.institutionId)
            if (m == null || !m.role.isStaff) throw InvalidInputException("INVALID_TEACHER", "이 기관의 교직원만 담임으로 지정할 수 있습니다")
        }
        return classrooms.save(
            Classroom(
                ClassroomId.new(), command.institutionId, command.name.trim(), command.capacity,
                command.days, command.startTime, command.endTime, command.teacherIds,
            ),
        )
    }
}

@Service
class ListClassroomsService(
    private val guard: AccessGuard,
    private val classrooms: ClassroomPort,
) : ListClassroomsQuery {
    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<Classroom> {
        val m = guard.requireStaff(actor, institutionId)
        val all = classrooms.findByInstitution(institutionId)
        return if (m.role.isManager) all else all.filter { it.isTaughtBy(actor) }
    }
}

@Service
class ManageDestinationService(
    private val guard: AccessGuard,
    private val destinations: DestinationPort,
) : ManageDestinationUseCase {
    @Transactional
    override fun create(command: ManageDestinationUseCase.CreateCommand): Destination {
        guard.require(command.actor, command.institutionId, Role.OWNER, Role.ADMIN)
        return destinations.save(Destination(DestinationId.new(), command.institutionId, command.name.trim(), command.type, command.sortOrder))
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<Destination> {
        guard.requireStaff(actor, institutionId)
        return destinations.findByInstitution(institutionId).sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }
}
