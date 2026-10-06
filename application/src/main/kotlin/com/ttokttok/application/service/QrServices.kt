package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.CheckinQrAdminUseCase
import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.QrScanException
import com.ttokttok.application.port.`in`.StudentAppUseCase
import com.ttokttok.application.port.`in`.StudentDeviceLinkUseCase
import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.CheckinQrPort
import com.ttokttok.application.port.out.ClassroomPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.EnrollmentPort
import com.ttokttok.application.port.out.GeofencePort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.QrScanLogPort
import com.ttokttok.application.port.out.StudentDevicePort
import com.ttokttok.application.port.out.StudentLinkCodePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.attendance.AttendanceSource
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.qr.CheckinQr
import com.ttokttok.domain.qr.CheckinQrId
import com.ttokttok.domain.qr.Geofence
import com.ttokttok.domain.qr.QrClassPicker
import com.ttokttok.domain.qr.QrScanFailure
import com.ttokttok.domain.qr.QrScanLog
import com.ttokttok.domain.qr.QrScanOutcome
import com.ttokttok.domain.qr.StudentDevice
import com.ttokttok.domain.qr.StudentDeviceId
import com.ttokttok.domain.qr.StudentLinkCode
import com.ttokttok.domain.student.StudentStatus
import com.ttokttok.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64

// 학생앱 QR 출석 (스펙 7-7)

/** QR 에 담긴 문자열(https://ttok.app/qr/{token} 또는 토큰만)에서 토큰을 꺼낸다 */
internal fun qrTokenOf(content: String): String =
    content.trim().substringBefore('?').trimEnd('/').substringAfterLast('/')

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

@Service
class CheckinQrAdminService(
    private val guard: AccessGuard,
    private val qrs: CheckinQrPort,
    private val geofences: GeofencePort,
    private val scanLogs: QrScanLogPort,
    private val students: StudentPort,
    private val links: AppLinksPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : CheckinQrAdminUseCase {

    @Transactional(readOnly = true)
    override fun list(actor: UserId, institutionId: InstitutionId): List<CheckinQrAdminUseCase.QrView> {
        guard.requireManager(actor, institutionId)
        return qrs.findActiveByInstitution(institutionId).sortedBy { it.createdAt }.map { it.toView() }
    }

    @Transactional
    override fun create(actor: UserId, institutionId: InstitutionId, name: String): CheckinQrAdminUseCase.QrView {
        guard.requireManager(actor, institutionId)
        if (qrs.findActiveByInstitution(institutionId).size >= CheckinQr.MAX_PER_INSTITUTION)
            throw ConflictException("QR_LIMIT", "출석 QR 은 기관당 ${CheckinQr.MAX_PER_INSTITUTION}개까지 만들 수 있습니다")
        val qr = qrs.save(CheckinQr.issue(institutionId, name, clock.now()))
        audit(actor, institutionId, "CHECKIN_QR_CREATE", qr.id, mapOf("name" to qr.name))
        return qr.toView()
    }

    @Transactional
    override fun rename(actor: UserId, institutionId: InstitutionId, id: CheckinQrId, name: String): CheckinQrAdminUseCase.QrView {
        guard.requireManager(actor, institutionId)
        return qrs.save(load(institutionId, id).rename(name)).toView()
    }

    @Transactional
    override fun rotate(actor: UserId, institutionId: InstitutionId, id: CheckinQrId): CheckinQrAdminUseCase.QrView {
        guard.requireManager(actor, institutionId)
        val qr = qrs.save(load(institutionId, id).rotate(clock.now()))
        audit(actor, institutionId, "CHECKIN_QR_ROTATE", qr.id, mapOf("name" to qr.name))
        return qr.toView()
    }

    @Transactional
    override fun delete(actor: UserId, institutionId: InstitutionId, id: CheckinQrId) {
        guard.requireManager(actor, institutionId)
        val qr = qrs.save(load(institutionId, id).deactivate())
        audit(actor, institutionId, "CHECKIN_QR_DELETE", qr.id, mapOf("name" to qr.name))
    }

    @Transactional(readOnly = true)
    override fun geofence(actor: UserId, institutionId: InstitutionId): Geofence? {
        guard.requireStaff(actor, institutionId)
        return geofences.find(institutionId)
    }

    @Transactional
    override fun setGeofence(actor: UserId, institutionId: InstitutionId, geofence: Geofence?): Geofence? {
        guard.require(actor, institutionId, Role.OWNER)
        val before = geofences.find(institutionId)
        geofences.save(institutionId, geofence)
        audit.record(
            AuditEntry(
                institutionId, actor, "GEOFENCE_UPDATE", "institution", institutionId.value.toString(),
                mapOf("radiusMeters" to mapOf("from" to before?.radiusMeters, "to" to geofence?.radiusMeters), "enabled" to (geofence != null)),
                clock.now(),
            ),
        )
        return geofence
    }

    @Transactional(readOnly = true)
    override fun recentFailures(actor: UserId, institutionId: InstitutionId): List<CheckinQrAdminUseCase.ScanFailureView> {
        guard.requireManager(actor, institutionId)
        val logs = scanLogs.recentFailures(institutionId, clock.now().minus(Duration.ofDays(7)), 100)
        val names = students.findAllByIds(logs.map { it.studentId }).associate { it.id to it.name }
        return logs.map { CheckinQrAdminUseCase.ScanFailureView(it.studentId, names[it.studentId] ?: "-", it.outcome, it.distanceMeters, it.at) }
    }

    private fun load(institutionId: InstitutionId, id: CheckinQrId) =
        qrs.find(id, institutionId)?.takeIf { it.active } ?: throw NotFoundException("출석 QR")

    private fun CheckinQr.toView() = CheckinQrAdminUseCase.QrView(id, name, links.checkinQrUrl(token), createdAt, rotatedAt)

    private fun audit(actor: UserId, institutionId: InstitutionId, action: String, id: CheckinQrId, diff: Map<String, Any?>) =
        audit.record(AuditEntry(institutionId, actor, action, "checkin_qr", id.value.toString(), diff, clock.now()))
}

@Service
class StudentDeviceLinkService(
    private val children: GetMyChildrenQuery,
    private val codes: StudentLinkCodePort,
    private val devices: StudentDevicePort,
    private val clock: ClockPort,
) : StudentDeviceLinkUseCase {

    @Transactional
    override fun issueCode(parent: UserId, studentId: StudentId): StudentDeviceLinkUseCase.LinkCodeView {
        val child = requireChild(parent, studentId)
        // 코드 충돌은 31^8 공간이라 거의 없지만, 있으면 다시 뽑는다
        repeat(5) {
            val code = StudentLinkCode.issue(studentId, child.institutionId, parent, clock.now())
            if (codes.findForUpdate(code.code) == null) {
                codes.save(code)
                return StudentDeviceLinkUseCase.LinkCodeView(code.code, code.expiresAt)
            }
        }
        throw ConflictException("LINK_CODE_RETRY", "잠시 후 다시 시도하세요")
    }

    @Transactional(readOnly = true)
    override fun devices(parent: UserId, studentId: StudentId): List<StudentDeviceLinkUseCase.DeviceView> {
        requireChild(parent, studentId)
        return devices.findActiveByStudent(studentId).sortedByDescending { it.createdAt }
            .map { StudentDeviceLinkUseCase.DeviceView(it.id, it.deviceName, it.createdAt, it.lastSeenAt) }
    }

    @Transactional
    override fun revoke(parent: UserId, studentId: StudentId, deviceId: StudentDeviceId) {
        requireChild(parent, studentId)
        val d = devices.find(deviceId)?.takeIf { it.studentId == studentId } ?: throw NotFoundException("기기")
        devices.save(d.revoke(clock.now()))
    }

    private fun requireChild(parent: UserId, studentId: StudentId) =
        children.children(parent).firstOrNull { it.studentId == studentId } ?: throw ForbiddenException("본인 자녀가 아닙니다")
}

@Service
class StudentAppService(
    private val codes: StudentLinkCodePort,
    private val devices: StudentDevicePort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val enrollments: EnrollmentPort,
    private val classrooms: ClassroomPort,
    private val attendance: AttendancePort,
    private val destinations: DestinationPort,
    private val qrs: CheckinQrPort,
    private val geofences: GeofencePort,
    private val scanLogs: QrScanLogPort,
    private val support: AttendanceCommandSupport,
    private val clock: ClockPort,
) : StudentAppUseCase {

    private val random = SecureRandom()

    @Transactional
    override fun link(code: String, deviceName: String): StudentAppUseCase.LinkedDevice {
        val now = clock.now()
        val linkCode = codes.findForUpdate(StudentLinkCode.normalize(code))
            ?: throw ConflictException("LINK_CODE_INVALID", "연결 코드가 올바르지 않습니다. 보호자 앱에서 만든 8자리 코드를 확인하세요")
        codes.save(linkCode.use(now))

        val student = students.find(linkCode.studentId, linkCode.institutionId) ?: throw NotFoundException("원생")
        val institution = institutions.findById(linkCode.institutionId) ?: throw NotFoundException("기관")

        // 기기 수 제한: 넘으면 가장 오래된 기기를 해제 (분실 폰 정리 유도)
        val active = devices.findActiveByStudent(student.id).sortedBy { it.createdAt }
        active.dropLast(StudentDevice.MAX_ACTIVE_PER_STUDENT - 1).forEach { devices.save(it.revoke(now)) }

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        devices.save(
            StudentDevice(
                StudentDeviceId.new(), student.id, institution.id, linkCode.issuedBy,
                deviceName.trim().take(50).ifBlank { "학생 기기" }, sha256Hex(token), now,
            ),
        )
        return StudentAppUseCase.LinkedDevice(token, student.id, student.name, institution.id, institution.name)
    }

    @Transactional(readOnly = true)
    override fun me(deviceToken: String): StudentAppUseCase.StudentHome {
        val device = authenticate(deviceToken)
        val student = students.find(device.studentId, device.institutionId) ?: throw UnauthenticatedException("연결이 해제되었습니다")
        val institution = institutions.findById(device.institutionId) ?: throw NotFoundException("기관")
        val today = clock.today()
        val dests = mutableMapOf<com.ttokttok.domain.common.DestinationId, com.ttokttok.domain.destination.Destination?>()
        val list = todaysClasses(student.id, device.institutionId).map { c ->
            val day = attendance.findDays(c.id, today).firstOrNull { it.studentId == student.id }
            val dest = day?.nextDestinationId?.let { id -> dests.getOrPut(id) { destinations.find(id, device.institutionId) } }
            StudentAppUseCase.TodayClass(
                c.id.value.toString(), c.name, c.startTime.toString(), c.endTime.toString(),
                day?.let { toAttendanceView(it, student.name, dest) },
            )
        }
        return StudentAppUseCase.StudentHome(student.id, student.name, institution.id, institution.name, list)
    }

    /**
     * 실패는 예외로 돌려주되(앱이 사유별 안내), 사유는 qr_scan_log 에 별도 트랜잭션으로 남긴다.
     * 출결 처리 자체는 교사 원터치와 같은 AttendanceCommandSupport 경로 (행 잠금·Outbox·STOMP).
     */
    @Transactional
    override fun scan(deviceToken: String, command: StudentAppUseCase.ScanCommand): StudentAppUseCase.ScanResult {
        val device = authenticate(deviceToken)
        support.replayIfDuplicate(device.institutionId, command.idempotencyKey)?.let { prior ->
            val outcome = if (prior.status == AttendanceStatus.OUT) QrScanOutcome.CHECKED_OUT else QrScanOutcome.CHECKED_IN
            return StudentAppUseCase.ScanResult(outcome, null, prior)
        }

        val qr = qrs.findByToken(qrTokenOf(command.qrContent))
            ?: fail(device, null, QrScanFailure.QR_INVALID, "등록되지 않았거나 재발급된 QR 입니다. 선생님께 문의하세요")
        if (qr.institutionId != device.institutionId) fail(device, qr, QrScanFailure.NOT_ENROLLED, "이 학원 QR 이 아닙니다")

        val student = students.find(device.studentId, device.institutionId)
        if (student == null || student.status != StudentStatus.ACTIVE) fail(device, qr, QrScanFailure.NOT_ENROLLED, "재원 중인 원생이 아닙니다")

        geofences.find(device.institutionId)?.let { fence ->
            val lat = command.latitude
            val lng = command.longitude
            if (lat == null || lng == null) fail(device, qr, QrScanFailure.LOCATION_REQUIRED, "위치 권한을 켜고 다시 찍어 주세요")
            if (!fence.contains(lat, lng, command.accuracyMeters)) {
                fail(device, qr, QrScanFailure.OUT_OF_RANGE, "학원 근처에서만 출석할 수 있습니다", fence.distanceTo(lat, lng).toInt())
            }
        }

        val now = clock.now()
        val today = clock.today()
        val classes = todaysClasses(student.id, device.institutionId)
        val candidates = classes.map { c ->
            val status = attendance.findDays(c.id, today).firstOrNull { it.studentId == student.id }?.status ?: AttendanceStatus.SCHEDULED
            QrClassPicker.Candidate(c, status)
        }
        val picked = QrClassPicker.pick(candidates, LocalDateTime.ofInstant(now, clock.zone()).toLocalTime())
        if (picked == null) {
            val doneToday = candidates.isNotEmpty() && candidates.all { it.status == AttendanceStatus.OUT || it.status == AttendanceStatus.ABSENT }
            if (doneToday) return StudentAppUseCase.ScanResult(QrScanOutcome.ALREADY_DONE, candidates.last().classroom.name, null)
            fail(device, qr, QrScanFailure.NO_CLASS_NOW, "지금 출석할 수업이 없습니다")
        }

        val ctx = support.loadForStudent(picked.classroom, student.id, command.clientAt)
        devices.touch(device.id, now)
        return when (ctx.day.status) {
            AttendanceStatus.SCHEDULED -> {
                val t = ctx.day.checkIn(support.ruleOf(ctx), ctx.at, STUDENT_QR_ACTOR, AttendanceSource.STUDENT_APP)
                StudentAppUseCase.ScanResult(QrScanOutcome.CHECKED_IN, ctx.classroom.name, support.commit(ctx, t, command.idempotencyKey, null))
            }
            AttendanceStatus.IN -> {
                val options = destinations.findByInstitution(device.institutionId)
                val destination = when {
                    command.destinationId != null -> options.firstOrNull { it.id == command.destinationId }
                        ?: throw InvalidInputException("INVALID_DESTINATION", "목적지를 다시 골라 주세요")
                    options.size == 1 -> options.first()
                    options.isEmpty() -> throw ConflictException("NO_DESTINATION", "하원 목적지가 설정되지 않았습니다. 선생님께 하원 처리를 요청하세요")
                    else -> return StudentAppUseCase.ScanResult(
                        QrScanOutcome.CHOOSE_DESTINATION, ctx.classroom.name, null,
                        options.sortedBy { it.sortOrder }.map { StudentAppUseCase.DestinationOption(it.id, it.name, it.type.name) },
                    )
                }
                val t = ctx.day.checkOut(support.ruleOf(ctx), ctx.at, destination.id, STUDENT_QR_ACTOR, AttendanceSource.STUDENT_APP)
                StudentAppUseCase.ScanResult(QrScanOutcome.CHECKED_OUT, ctx.classroom.name, support.commit(ctx, t, command.idempotencyKey, destination))
            }
            else -> StudentAppUseCase.ScanResult(QrScanOutcome.ALREADY_DONE, ctx.classroom.name, null)
        }
    }

    @Transactional
    override fun logout(deviceToken: String) {
        val device = devices.findActiveByTokenHash(sha256Hex(deviceToken)) ?: return
        devices.save(device.revoke(clock.now()))
    }

    private fun authenticate(token: String): StudentDevice {
        if (token.isBlank()) throw UnauthenticatedException("기기 연결이 필요합니다")
        return devices.findActiveByTokenHash(sha256Hex(token)) ?: throw UnauthenticatedException("기기 연결이 해제되었습니다. 보호자 앱에서 다시 연결해 주세요")
    }

    private fun todaysClasses(studentId: StudentId, institutionId: InstitutionId) =
        enrollments.findCurrent(studentId)
            .mapNotNull { classrooms.find(it.classroomId, institutionId) }
            .filter { it.isHeldOn(clock.today()) }
            .sortedBy { it.startTime }

    private fun fail(device: StudentDevice, qr: CheckinQr?, reason: QrScanFailure, message: String, distance: Int? = null): Nothing {
        scanLogs.record(QrScanLog(device.institutionId, device.studentId, qr?.id, null, reason.name, distance, clock.now()))
        throw QrScanException(reason, message)
    }
}
