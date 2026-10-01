package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.InvitationEntity
import com.ttokttok.adapter.out.persistence.entity.JoinRequestEntity
import com.ttokttok.adapter.out.persistence.entity.StudentImportJobEntity
import com.ttokttok.adapter.out.persistence.entity.StudentStatusHistoryEntity
import com.ttokttok.adapter.out.persistence.repository.InvitationJpaRepository
import com.ttokttok.adapter.out.persistence.repository.JoinRequestJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentImportJobJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentStatusHistoryJpaRepository
import com.ttokttok.application.port.out.ImportCandidate
import com.ttokttok.application.port.out.ImportError
import com.ttokttok.application.port.out.ImportJob
import com.ttokttok.application.port.out.ImportJobPort
import com.ttokttok.application.port.out.ImportJobStatus
import com.ttokttok.application.port.out.InvitationPort
import com.ttokttok.application.port.out.JoinRequestPort
import com.ttokttok.application.port.out.StudentStatusHistoryPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.invitation.Invitation
import com.ttokttok.domain.invitation.InvitationId
import com.ttokttok.domain.invitation.JoinRequest
import com.ttokttok.domain.invitation.JoinRequestId
import com.ttokttok.domain.invitation.JoinRequestStatus
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.student.StudentStatusChange
import com.ttokttok.domain.student.WithdrawalReason
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Component
class StudentStatusHistoryAdapter(private val repo: StudentStatusHistoryJpaRepository) : StudentStatusHistoryPort {
    override fun save(change: StudentStatusChange, actor: UserId, at: Instant) {
        repo.save(
            StudentStatusHistoryEntity(
                null, change.institutionId.value, change.studentId.value, change.from.name, change.to.name,
                change.reason?.name, change.effectiveDate, change.note, actor.value, at,
            ),
        )
    }

    override fun findByStudent(studentId: StudentId) = repo.findByStudentId(studentId.value).map {
        StudentStatusChange(
            StudentId(it.studentId), InstitutionId(it.institutionId), StudentStatus.valueOf(it.fromStatus), StudentStatus.valueOf(it.toStatus),
            it.reason?.let(WithdrawalReason::valueOf), it.effectiveDate, it.note,
        )
    }
}

@Component
class InvitationAdapter(private val repo: InvitationJpaRepository, private val crypto: FieldCrypto) : InvitationPort {
    override fun save(invitation: Invitation): Invitation {
        repo.save(
            InvitationEntity(
                invitation.id.value, invitation.institutionId.value, invitation.token,
                crypto.encrypt(invitation.phone.digits), crypto.hash(invitation.phone.digits), invitation.invitedBy.value, invitation.expiresAt,
            ),
        )
        return invitation
    }

    override fun findByToken(token: String) = repo.findByToken(token)?.let {
        Invitation(InvitationId(it.id), InstitutionId(it.institutionId), it.token, PhoneNumber.of(crypto.decrypt(it.phoneEnc)), UserId(it.invitedBy), it.expiresAt)
    }
}

@Component
class JoinRequestAdapter(private val repo: JoinRequestJpaRepository, private val crypto: FieldCrypto) : JoinRequestPort {
    override fun save(request: JoinRequest): JoinRequest {
        repo.save(
            JoinRequestEntity(
                request.id.value, request.institutionId.value, request.invitationId.value, request.childName,
                crypto.encrypt(request.birthDate.toString()), request.guardianName, crypto.encrypt(request.guardianPhone.digits),
                request.relation, request.status.name, request.classroomId?.value, request.decidedBy?.value, request.decidedAt,
                request.rejectReason, request.submittedAt,
            ),
        )
        return request
    }

    override fun find(id: JoinRequestId, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()

    override fun findByInstitution(institutionId: InstitutionId, status: JoinRequestStatus?) =
        (if (status == null) repo.findByInstitutionId(institutionId.value) else repo.findByInstitutionIdAndStatus(institutionId.value, status.name))
            .map { it.toDomain() }

    private fun JoinRequestEntity.toDomain() = JoinRequest(
        JoinRequestId(id), InstitutionId(institutionId), InvitationId(invitationId), childName, LocalDate.parse(crypto.decrypt(birthEnc)),
        guardianName, PhoneNumber.of(crypto.decrypt(guardianPhoneEnc)), relation, submittedAt, JoinRequestStatus.valueOf(status),
        classroomId?.let { ClassroomId(it) }, decidedBy?.let { UserId(it) }, decidedAt, rejectReason,
    )
}

/** 업로드 검증 결과 — 연락처가 담겨 본문 전체를 AES-GCM으로 암호화해 저장 */
@Component
class ImportJobAdapter(private val repo: StudentImportJobJpaRepository, private val crypto: FieldCrypto) : ImportJobPort {
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    override fun save(job: ImportJob): ImportJob {
        val body = Body(
            job.candidates.map { Row(it.rowNumber, it.name, it.birthDate, it.classroomName, it.guardianPhone.digits, it.relation, it.grade, it.memo) },
            job.errors.map { Err(it.rowNumber, it.column, it.message) },
        )
        repo.save(
            StudentImportJobEntity(
                job.id, job.institutionId.value, job.createdBy.value, job.status.name, job.totalRows,
                crypto.encrypt(json.writeValueAsString(body)), job.createdAt,
            ),
        )
        return job
    }

    override fun find(id: UUID, institutionId: InstitutionId) = repo.findByIdAndInstitutionId(id, institutionId.value)?.let { e ->
        val body = json.readValue<Body>(crypto.decrypt(e.bodyEnc))
        ImportJob(
            e.id, InstitutionId(e.institutionId), UserId(e.createdBy), ImportJobStatus.valueOf(e.status),
            body.rows.map { ImportCandidate(it.rowNumber, it.name, it.birthDate, it.classroomName, PhoneNumber.of(it.phone), it.relation, it.grade, it.memo) },
            body.errors.map { ImportError(it.rowNumber, it.column, it.message) },
            e.totalRows, e.createdAt,
        )
    }

    data class Body(val rows: List<Row>, val errors: List<Err>)
    data class Row(
        val rowNumber: Int, val name: String, val birthDate: LocalDate, val classroomName: String,
        val phone: String, val relation: String?, val grade: String?, val memo: String?,
    )
    data class Err(val rowNumber: Int, val column: String, val message: String)
}
