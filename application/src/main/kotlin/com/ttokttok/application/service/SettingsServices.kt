package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.AttendanceEvidenceUseCase
import com.ttokttok.application.port.`in`.AttendanceView
import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.FileRef
import com.ttokttok.application.port.`in`.FileUseCase
import com.ttokttok.application.port.`in`.InstitutionSettingsUseCase
import com.ttokttok.application.port.`in`.InstitutionView
import com.ttokttok.application.port.`in`.SessionUseCase
import com.ttokttok.application.port.`in`.StaffInvitationUseCase
import com.ttokttok.application.port.`in`.TermsUseCase
import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.MembershipPort
import com.ttokttok.application.port.out.ObjectStoragePort
import com.ttokttok.application.port.out.PasswordHasherPort
import com.ttokttok.application.port.out.PresignedUrl
import com.ttokttok.application.port.out.RefreshTokenPort
import com.ttokttok.application.port.out.RefreshTokenRecord
import com.ttokttok.application.port.out.SendEmailPort
import com.ttokttok.application.port.out.StaffInvitationPort
import com.ttokttok.application.port.out.StoredFilePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.application.port.out.TermsPort
import com.ttokttok.application.port.out.UserPort
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.file.FileStatus
import com.ttokttok.domain.file.StoredFile
import com.ttokttok.domain.staff.StaffInvitation
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.TermsAgreement
import com.ttokttok.domain.terms.TermsAudience
import com.ttokttok.domain.terms.TermsId
import com.ttokttok.domain.terms.TermsPolicy
import com.ttokttok.domain.user.Membership
import com.ttokttok.domain.user.Role
import com.ttokttok.domain.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

// ───────── 파일 ─────────

/** 다른 기능이 파일 id 를 화면용 참조(이름·크기·5분 URL)로 바꿀 때 쓴다 */
@Component
class FileRefResolver(
    private val files: StoredFilePort,
    private val storage: ObjectStoragePort,
) {
    fun refs(ids: Collection<FileId>, withUrl: Boolean): List<FileRef> {
        if (ids.isEmpty()) return emptyList()
        val byId = files.findAllByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }.filter { it.status == FileStatus.UPLOADED }.map { f ->
            FileRef(f.id, f.originalName, f.mime, f.size, if (withUrl) storage.presignDownload(f.storageKey, f.originalName, f.mime).url else null)
        }
    }

    fun ref(id: FileId?, withUrl: Boolean): FileRef? = id?.let { refs(listOf(it), withUrl).firstOrNull() }

    /** 참조 저장 전 검증: 같은 기관·업로드 완료·용도 일치 */
    fun requireUsable(ids: Collection<FileId>, institutionId: InstitutionId, purpose: FilePurpose) {
        if (ids.isEmpty()) return
        val found = files.findAllByIds(ids).associateBy { it.id }
        ids.forEach { id -> (found[id] ?: throw NotFoundException("파일")).requireUsableFor(institutionId, purpose) }
    }
}

@Service
class FileService(
    private val guard: AccessGuard,
    private val files: StoredFilePort,
    private val storage: ObjectStoragePort,
    private val resolver: FileRefResolver,
    private val clock: ClockPort,
) : FileUseCase {

    @Transactional
    override fun presign(actor: UserId, institutionId: InstitutionId, purpose: FilePurpose, filename: String, mime: String, size: Long): FileUseCase.PresignResult {
        val m = guard.requireStaff(actor, institutionId)
        if (purpose in setOf(FilePurpose.LOGO, FilePurpose.SEAL) && m.role != Role.OWNER) throw ForbiddenException("로고·직인은 원장만 올릴 수 있습니다")
        val file = files.save(StoredFile.request(institutionId, actor, purpose, filename, mime, size, clock.now()))
        return FileUseCase.PresignResult(file.id, storage.presignUpload(file.storageKey, file.mime, file.size))
    }

    @Transactional
    override fun complete(actor: UserId, institutionId: InstitutionId, id: FileId): FileRef {
        val m = guard.requireStaff(actor, institutionId)
        val file = files.find(id)?.takeIf { it.institutionId == institutionId } ?: throw NotFoundException("파일")
        if (file.uploaderId != actor && !m.role.isManager) throw ForbiddenException("본인이 올린 파일만 확정할 수 있습니다")
        val actual = storage.sizeOf(file.storageKey) ?: throw ConflictException("NOT_UPLOADED", "아직 업로드되지 않았습니다")
        files.save(file.complete(actual))
        return resolver.ref(id, withUrl = false)!!
    }

    @Transactional(readOnly = true)
    override fun download(actor: UserId, institutionId: InstitutionId, id: FileId): PresignedUrl {
        guard.requireStaff(actor, institutionId)
        val file = files.find(id)?.takeIf { it.institutionId == institutionId && it.status == FileStatus.UPLOADED } ?: throw NotFoundException("파일")
        return storage.presignDownload(file.storageKey, file.originalName, file.mime)
    }
}

// ───────── SET-001 기관 정보 ─────────

@Service
class InstitutionSettingsService(
    private val guard: AccessGuard,
    private val institutions: InstitutionPort,
    private val resolver: FileRefResolver,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : InstitutionSettingsUseCase {

    @Transactional(readOnly = true)
    override fun get(actor: UserId, institutionId: InstitutionId): InstitutionView {
        guard.requireStaff(actor, institutionId)
        return view(institutions.findById(institutionId) ?: throw NotFoundException("기관"))
    }

    @Transactional
    override fun update(actor: UserId, institutionId: InstitutionId, command: InstitutionSettingsUseCase.UpdateCommand): InstitutionView {
        guard.require(actor, institutionId, Role.OWNER)
        val current = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        resolver.requireUsable(listOfNotNull(command.logoFileId), institutionId, FilePurpose.LOGO)
        resolver.requireUsable(listOfNotNull(command.sealFileId), institutionId, FilePurpose.SEAL)
        val updated = institutions.save(
            current.copy(
                name = command.name.trim(), ownerName = command.ownerName.trim(),
                address = command.address?.trim()?.ifEmpty { null }, phone = command.phone?.trim()?.ifEmpty { null },
                lateThresholdMinutes = command.lateThresholdMinutes, earlyLeaveThresholdMinutes = command.earlyLeaveThresholdMinutes,
                logoFileId = command.logoFileId, sealFileId = command.sealFileId,
                type = command.type ?: current.type,
            ),
        )
        audit.record(
            AuditEntry(
                institutionId, actor, "INSTITUTION_UPDATE", "institution", institutionId.value.toString(),
                mapOf(
                    "lateThreshold" to mapOf("from" to current.lateThresholdMinutes, "to" to updated.lateThresholdMinutes),
                    "earlyLeaveThreshold" to mapOf("from" to current.earlyLeaveThresholdMinutes, "to" to updated.earlyLeaveThresholdMinutes),
                    "nameChanged" to (current.name != updated.name),
                ),
                clock.now(),
            ),
        )
        return view(updated)
    }

    private fun view(i: com.ttokttok.domain.institution.Institution) = InstitutionView(
        i.id, i.name, i.ownerName, i.address, i.phone, i.lateThresholdMinutes, i.earlyLeaveThresholdMinutes,
        resolver.ref(i.logoFileId, withUrl = true), resolver.ref(i.sealFileId, withUrl = true), i.type,
    )
}

// ───────── ATT-003 결석 증빙 ─────────

@Service
class AttendanceEvidenceService(
    private val guard: AccessGuard,
    private val attendance: AttendancePort,
    private val students: StudentPort,
    private val resolver: FileRefResolver,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : AttendanceEvidenceUseCase {

    @Transactional
    override fun attach(actor: UserId, institutionId: InstitutionId, dayId: AttendanceDayId, fileId: FileId?): AttendanceView {
        val day = attendance.findDayById(dayId)?.takeIf { it.institutionId == institutionId } ?: throw NotFoundException("출결 기록")
        guard.requireClassroomAccess(actor, institutionId, day.classroomId)
        resolver.requireUsable(listOfNotNull(fileId), institutionId, FilePurpose.ABSENCE_EVIDENCE)
        val saved = attendance.saveDay(day.withEvidence(fileId))
        val student = students.find(day.studentId, institutionId) ?: throw NotFoundException("원생")
        audit.record(
            AuditEntry(
                institutionId, actor, "ATTENDANCE_EVIDENCE", "attendance_day", dayId.value.toString(),
                mapOf("student" to student.name, "date" to day.date.toString(), "fileId" to fileId?.value?.toString()), clock.now(),
            ),
        )
        return toAttendanceView(saved, student.name, null)
    }
}

// ───────── STF-002 교직원 이메일 초대 ─────────

@Service
class StaffInvitationService(
    private val guard: AccessGuard,
    private val invitations: StaffInvitationPort,
    private val institutions: InstitutionPort,
    private val users: UserPort,
    private val memberships: MembershipPort,
    private val hasher: PasswordHasherPort,
    private val email: SendEmailPort,
    private val links: AppLinksPort,
    private val assembler: AuthenticatedUserAssembler,
    private val clock: ClockPort,
) : StaffInvitationUseCase {

    @Transactional
    override fun invite(actor: UserId, institutionId: InstitutionId, email: String, name: String, role: Role): StaffInvitationUseCase.StaffInvitationView {
        guard.require(actor, institutionId, Role.OWNER)
        val now = clock.now()
        val inv = StaffInvitation.issue(institutionId, email, name, role, actor, now)
        users.findByEmail(inv.email)?.let { u ->
            if (memberships.find(u.id, institutionId) != null) throw ConflictException("ALREADY_MEMBER", "이미 소속된 직원입니다")
        }
        // 같은 이메일로 대기 중인 초대는 새 초대로 대체
        invitations.findByInstitution(institutionId).filter { it.email == inv.email && it.isUsable(now) }
            .forEach { invitations.save(it.revoke(now)) }
        invitations.save(inv)
        val instName = institutions.findById(institutionId)?.name ?: ""
        this.email.send(
            inv.email, "[똑똑] $instName 교직원 초대",
            "${inv.name}님, $instName 에서 똑똑 ${roleLabel(role)} 계정으로 초대했습니다.\n" +
                "아래 링크에서 7일 안에 수락해 주세요.\n${links.staffInviteUrl(inv.token)}",
        )
        return inv.toView(now)
    }

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<StaffInvitationUseCase.StaffInvitationView> {
        guard.requireManager(actor, institutionId)
        val now = clock.now()
        return invitations.findByInstitution(institutionId).sortedByDescending { it.createdAt }.map { it.toView(now) }
    }

    @Transactional
    override fun revoke(actor: UserId, institutionId: InstitutionId, id: StaffInvitationId) {
        guard.require(actor, institutionId, Role.OWNER)
        val inv = invitations.find(id, institutionId) ?: throw NotFoundException("초대")
        invitations.save(inv.revoke(clock.now()))
    }

    @Transactional(readOnly = true)
    override fun info(token: String): StaffInvitationUseCase.PublicInvitationInfo {
        val inv = usable(token)
        return StaffInvitationUseCase.PublicInvitationInfo(
            institutionName = institutions.findById(inv.institutionId)?.name ?: "",
            name = inv.name, email = inv.email, role = inv.role,
            existingAccount = users.findByEmail(inv.email) != null, expiresAt = inv.expiresAt,
        )
    }

    @Transactional
    override fun accept(token: String, password: String): AuthenticatedUser {
        val inv = usable(token)
        val now = clock.now()
        val existing = users.findByEmail(inv.email)
        val user = if (existing != null) {
            // 이미 다른 기관에서 쓰는 계정: 본인 확인 후 소속만 추가
            if (!hasher.matches(password, existing.passwordHash)) throw UnauthenticatedException("기존 계정 비밀번호가 올바르지 않습니다")
            existing
        } else {
            validatePassword(password)
            users.save(User(UserId.new(), inv.name, inv.email, null, hasher.hash(password), mustChangePassword = false))
        }
        if (memberships.find(user.id, inv.institutionId) != null) throw ConflictException("ALREADY_MEMBER", "이미 소속된 직원입니다")
        memberships.save(Membership(MembershipId.new(), user.id, inv.institutionId, inv.role))
        invitations.save(inv.accept(now))
        return assembler.assemble(user)
    }

    private fun usable(token: String): StaffInvitation {
        val inv = invitations.findByToken(token) ?: throw NotFoundException("초대")
        if (!inv.isUsable(clock.now())) throw ConflictException("INVITATION_EXPIRED", "만료되었거나 이미 사용된 초대입니다")
        return inv
    }

    private fun StaffInvitation.toView(now: Instant) = StaffInvitationUseCase.StaffInvitationView(
        id, email, name, role,
        status = when {
            acceptedAt != null -> "ACCEPTED"
            revokedAt != null -> "REVOKED"
            !now.isBefore(expiresAt) -> "EXPIRED"
            else -> "PENDING"
        },
        expiresAt = expiresAt, acceptedAt = acceptedAt,
    )

    private fun roleLabel(role: Role) = if (role == Role.ADMIN) "실장" else "교사"
}

// ───────── 세션 (refresh 회전) ─────────

@Service
class SessionService(
    private val tokens: RefreshTokenPort,
    private val clock: ClockPort,
    @org.springframework.beans.factory.annotation.Value("\${ttok.jwt.refresh-ttl:P14D}") private val refreshTtl: java.time.Duration,
    @org.springframework.beans.factory.annotation.Value("\${ttok.jwt.remember-me-ttl:P30D}") private val rememberMeTtl: java.time.Duration,
) : SessionUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun start(userId: UserId, rememberMe: Boolean): SessionUseCase.Session = issue(userId, rememberMe, UUID.randomUUID())

    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    override fun rotate(jti: UUID, userId: UserId): SessionUseCase.Session {
        val now = clock.now()
        val record = tokens.find(jti)?.takeIf { it.userId == userId } ?: throw UnauthenticatedException("세션이 없습니다. 다시 로그인하세요")
        if (record.revokedAt != null) {
            // 이미 회전된 토큰이 다시 쓰였다 = 탈취 가능성 → 같은 로그인 계열 전체 폐기
            log.warn("refresh 토큰 재사용 감지: user={} family={}", userId.value, record.familyId)
            tokens.revokeFamily(record.familyId, now)
            throw UnauthenticatedException("세션이 만료되었습니다. 다시 로그인하세요")
        }
        if (!now.isBefore(record.expiresAt)) throw UnauthenticatedException("세션이 만료되었습니다. 다시 로그인하세요")
        val next = issue(userId, record.rememberMe, record.familyId)
        tokens.revoke(jti, now, next.jti)
        return next
    }

    @Transactional
    override fun end(jti: UUID) {
        val record = tokens.find(jti) ?: return
        tokens.revokeFamily(record.familyId, clock.now())
    }

    @Transactional
    override fun endAll(userId: UserId) = tokens.revokeAllForUser(userId, clock.now())

    private fun issue(userId: UserId, rememberMe: Boolean, familyId: UUID): SessionUseCase.Session {
        val jti = UUID.randomUUID()
        val expires = clock.now().plus(if (rememberMe) rememberMeTtl else refreshTtl)
        tokens.save(RefreshTokenRecord(jti, familyId, userId, rememberMe, expires))
        return SessionUseCase.Session(jti, userId, rememberMe, expires)
    }
}

// ───────── SET-005 약관 ─────────

@Service
class TermsService(
    private val terms: TermsPort,
    private val clock: ClockPort,
) : TermsUseCase {

    @Transactional(readOnly = true)
    override fun current(audience: TermsAudience): List<TermsUseCase.TermsView> =
        TermsPolicy.current(terms.findAll(), clock.now()).filter { it.type.audience == audience }.sortedBy { it.type.ordinal }.map { it.toView() }

    @Transactional(readOnly = true)
    override fun pending(userId: UserId, audience: TermsAudience): List<TermsUseCase.TermsView> =
        TermsPolicy.pendingRequired(TermsPolicy.current(terms.findAll(), clock.now()), terms.findAgreedIds(userId), audience).map { it.toView() }

    @Transactional
    override fun agree(userId: UserId, termsIds: List<TermsId>, ip: String?) {
        if (termsIds.isEmpty()) throw InvalidInputException("EMPTY_AGREEMENT", "동의할 약관을 선택하세요")
        val now = clock.now()
        val current = TermsPolicy.current(terms.findAll(), now).associateBy { it.id }
        val unknown = termsIds.filter { it !in current }
        if (unknown.isNotEmpty()) throw InvalidInputException("INVALID_TERMS", "현재 시행 중인 약관이 아닙니다")
        val already = terms.findAgreedIds(userId)
        terms.saveAgreements(termsIds.distinct().filter { it !in already }.map { TermsAgreement(userId, it, now, ip?.take(45)) })
    }

    private fun com.ttokttok.domain.terms.Terms.toView() = TermsUseCase.TermsView(id, type, version, title, body, required, effectiveAt)
}
