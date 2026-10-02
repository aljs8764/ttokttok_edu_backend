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
    private val clock: com.ttokttok.application.port.out.ClockPort,
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

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, id: DestinationId, name: String, type: com.ttokttok.domain.destination.DestinationType): Destination {
        guard.requireManager(actor, institutionId)
        val d = destinations.findByInstitution(institutionId).firstOrNull { it.id == id } ?: throw com.ttokttok.domain.common.NotFoundException("목적지")
        return destinations.save(d.rename(name, type))
    }

    /** 지난 하원 기록의 목적지 이름은 그대로 보이도록 소프트 삭제 */
    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: DestinationId) {
        guard.requireManager(actor, institutionId)
        if (destinations.findByInstitution(institutionId).none { it.id == id }) throw com.ttokttok.domain.common.NotFoundException("목적지")
        destinations.softDelete(id, institutionId, clock.now())
    }

    @Transactional
    override fun reorder(actor: UserId, institutionId: InstitutionId, orderedIds: List<DestinationId>): List<Destination> {
        guard.requireManager(actor, institutionId)
        val current = destinations.findByInstitution(institutionId).associateBy { it.id }
        if (orderedIds.toSet() != current.keys || orderedIds.size != current.size)
            throw InvalidInputException("INVALID_ORDER", "현재 목적지 전체를 한 번씩 담아 보내야 합니다")
        return orderedIds.mapIndexed { i, id -> destinations.save(current.getValue(id).copy(sortOrder = i)) }
    }
}
