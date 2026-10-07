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
import com.ttokttok.application.port.out.ChildPort
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
import com.ttokttok.domain.child.ChildId
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
import java.util.UUID

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
        val names = students.findAllByIds(logs.mapNotNull { it.studentId }).associate { it.id to it.name }
        return logs.map {
            CheckinQrAdminUseCase.ScanFailureView(it.studentId, it.studentId?.let { id -> names[id] } ?: "등록되지 않은 학생", it.outcome, it.distanceMeters, it.at)
        }
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
    override fun issueCode(parent: UserId, id: UUID): StudentDeviceLinkUseCase.LinkCodeView {
        val childId = requireChild(parent, id)
        // 코드 충돌은 31^8 공간이라 거의 없지만, 있으면 다시 뽑는다
        repeat(5) {
            val code = StudentLinkCode.issue(childId, parent, clock.now())
            if (codes.findForUpdate(code.code) == null) {
                codes.save(code)
                return StudentDeviceLinkUseCase.LinkCodeView(code.code, code.expiresAt)
            }
        }
        throw ConflictException("LINK_CODE_RETRY", "잠시 후 다시 시도하세요")
    }

    @Transactional
    override fun devices(parent: UserId, id: UUID): List<StudentDeviceLinkUseCase.DeviceView> {
        val childId = requireChild(parent, id)
        return devices.findActiveByChild(childId).sortedByDescending { it.createdAt }
            .map { StudentDeviceLinkUseCase.DeviceView(it.id, it.deviceName, it.createdAt, it.lastSeenAt) }
    }

    @Transactional
    override fun revoke(parent: UserId, id: UUID, deviceId: StudentDeviceId) {
        val childId = requireChild(parent, id)
        val d = devices.find(deviceId)?.takeIf { it.childId == childId } ?: throw NotFoundException("기기")
        devices.save(d.revoke(clock.now()))
    }

    /** 아이 id, 또는 이전 앱이 보내는 원생 id → 그 원생의 아이 */
    private fun requireChild(parent: UserId, id: UUID): ChildId {
        val kids = children.children(parent)
        kids.firstOrNull { it.childId.value == id }?.let { return it.childId }
        kids.firstOrNull { k -> k.enrollments.any { it.studentId.value == id } }?.let { return it.childId }
        throw ForbiddenException("본인 자녀가 아닙니다")
    }
}

/**
 * 학생앱. 기기는 아이에 묶이고(스펙 7-8), 스캔한 QR 의 기관으로 그 기관의 원생을 찾는다.
 * → 폰 하나로 아이가 다니는 모든 학원에서 출석. 다른 기관 정보는 학생앱에도 아이 본인 것만 보인다.
 */
@Service
class StudentAppService(
    private val codes: StudentLinkCodePort,
    private val devices: StudentDevicePort,
    private val children: ChildPort,
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
        val child = children.find(linkCode.childId) ?: throw NotFoundException("아이")

        // 기기 수 제한: 넘으면 가장 오래된 기기를 해제 (분실 폰 정리 유도)
        val active = devices.findActiveByChild(child.id).sortedBy { it.createdAt }
        active.dropLast(StudentDevice.MAX_ACTIVE_PER_CHILD - 1).forEach { devices.save(it.revoke(now)) }

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        devices.save(
            StudentDevice(
                StudentDeviceId.new(), child.id, linkCode.issuedBy,
                deviceName.trim().take(50).ifBlank { "학생 기기" }, sha256Hex(token), now,
            ),
        )
        return StudentAppUseCase.LinkedDevice(token, child.id, child.name, institutionsOf(activeStudents(child.id)))
    }

    @Transactional(readOnly = true)
    override fun me(deviceToken: String): StudentAppUseCase.StudentHome {
        val device = authenticate(deviceToken)
        val child = children.find(device.childId) ?: throw UnauthenticatedException("연결이 해제되었습니다")
        val mine = activeStudents(child.id)
        val insts = institutions.findAllByIds(mine.map { it.institutionId }.distinct()).associateBy { it.id }
        val today = clock.today()
        val list = mine.flatMap { student ->
            val inst = insts[student.institutionId]
            todaysClasses(student.id, student.institutionId).map { c ->
                val day = attendance.findDays(c.id, today).firstOrNull { it.studentId == student.id }
                val dest = day?.nextDestinationId?.let { id -> destinations.find(id, student.institutionId) }
                StudentAppUseCase.TodayClass(
                    student.institutionId, inst?.name ?: "",
                    c.id.value.toString(), c.name, c.startTime.toString(), c.endTime.toString(),
                    day?.let { toAttendanceView(it, student.name, dest) },
                ) to c.startTime
            }
        }.sortedBy { it.second }.map { it.first }
        return StudentAppUseCase.StudentHome(child.id, child.name, institutionsOf(mine), list)
    }

    /**
     * 실패는 예외로 돌려주되(앱이 사유별 안내), 사유는 qr_scan_log 에 별도 트랜잭션으로 남긴다.
     * 출결 처리 자체는 교사 원터치와 같은 AttendanceCommandSupport 경로 (행 잠금·Outbox·STOMP).
     */
    @Transactional
    override fun scan(deviceToken: String, command: StudentAppUseCase.ScanCommand): StudentAppUseCase.ScanResult {
        val device = authenticate(deviceToken)

        // QR 이 어느 기관인지가 먼저 — 기기는 기관에 묶여 있지 않다
        val qr = qrs.findByToken(qrTokenOf(command.qrContent))
            ?: throw QrScanException(QrScanFailure.QR_INVALID, "등록되지 않았거나 재발급된 QR 입니다. 선생님께 문의하세요")
        val institutionId = qr.institutionId
        val instName = institutions.findById(institutionId)?.name

        support.replayIfDuplicate(institutionId, command.idempotencyKey)?.let { prior ->
            val outcome = if (prior.status == AttendanceStatus.OUT) QrScanOutcome.CHECKED_OUT else QrScanOutcome.CHECKED_IN
            return StudentAppUseCase.ScanResult(outcome, null, instName, prior)
        }

        val student = students.findByChildren(listOf(device.childId))
            .firstOrNull { it.institutionId == institutionId && it.status == StudentStatus.ACTIVE }
            ?: fail(device, qr, null, QrScanFailure.NOT_ENROLLED, "${instName ?: "이 기관"}에 재원 중인 원생으로 등록되어 있지 않습니다")

        geofences.find(institutionId)?.let { fence ->
            val lat = command.latitude
            val lng = command.longitude
            if (lat == null || lng == null) fail(device, qr, student, QrScanFailure.LOCATION_REQUIRED, "위치 권한을 켜고 다시 찍어 주세요")
            if (!fence.contains(lat, lng, command.accuracyMeters)) {
                fail(device, qr, student, QrScanFailure.OUT_OF_RANGE, "학원 근처에서만 출석할 수 있습니다", fence.distanceTo(lat, lng).toInt())
            }
        }

        val now = clock.now()
        val today = clock.today()
        val classes = todaysClasses(student.id, institutionId)
        val candidates = classes.map { c ->
            val status = attendance.findDays(c.id, today).firstOrNull { it.studentId == student.id }?.status ?: AttendanceStatus.SCHEDULED
            QrClassPicker.Candidate(c, status)
        }
        val picked = QrClassPicker.pick(candidates, LocalDateTime.ofInstant(now, clock.zone()).toLocalTime())
        if (picked == null) {
            val doneToday = candidates.isNotEmpty() && candidates.all { it.status == AttendanceStatus.OUT || it.status == AttendanceStatus.ABSENT }
            if (doneToday) return StudentAppUseCase.ScanResult(QrScanOutcome.ALREADY_DONE, candidates.last().classroom.name, instName, null)
            fail(device, qr, student, QrScanFailure.NO_CLASS_NOW, "지금 출석할 수업이 없습니다")
        }

        val ctx = support.loadForStudent(picked.classroom, student.id, command.clientAt)
        devices.touch(device.id, now)
        return when (ctx.day.status) {
            AttendanceStatus.SCHEDULED -> {
                val t = ctx.day.checkIn(support.ruleOf(ctx), ctx.at, STUDENT_QR_ACTOR, AttendanceSource.STUDENT_APP)
                StudentAppUseCase.ScanResult(QrScanOutcome.CHECKED_IN, ctx.classroom.name, instName, support.commit(ctx, t, command.idempotencyKey, null))
            }
            AttendanceStatus.IN -> {
                val options = destinations.findByInstitution(institutionId)
                val destination = when {
                    command.destinationId != null -> options.firstOrNull { it.id == command.destinationId }
                        ?: throw InvalidInputException("INVALID_DESTINATION", "목적지를 다시 골라 주세요")
                    options.size == 1 -> options.first()
                    options.isEmpty() -> throw ConflictException("NO_DESTINATION", "하원 목적지가 설정되지 않았습니다. 선생님께 하원 처리를 요청하세요")
                    else -> return StudentAppUseCase.ScanResult(
                        QrScanOutcome.CHOOSE_DESTINATION, ctx.classroom.name, instName, null,
                        options.sortedBy { it.sortOrder }.map { StudentAppUseCase.DestinationOption(it.id, it.name, it.type.name) },
                    )
                }
                val t = ctx.day.checkOut(support.ruleOf(ctx), ctx.at, destination.id, STUDENT_QR_ACTOR, AttendanceSource.STUDENT_APP)
                StudentAppUseCase.ScanResult(QrScanOutcome.CHECKED_OUT, ctx.classroom.name, instName, support.commit(ctx, t, command.idempotencyKey, destination))
            }
            else -> StudentAppUseCase.ScanResult(QrScanOutcome.ALREADY_DONE, ctx.classroom.name, instName, null)
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

    /** 퇴원하지 않은 원생 (휴원 포함 — 홈에 기관은 보이고, 스캔은 ACTIVE 만) */
    private fun activeStudents(childId: ChildId) =
        students.findByChildren(listOf(childId)).filter { it.status != StudentStatus.WITHDRAWN }

    private fun institutionsOf(list: List<com.ttokttok.domain.student.Student>) =
        institutions.findAllByIds(list.map { it.institutionId }.distinct())
            .sortedBy { it.name }
            .map { StudentAppUseCase.InstitutionRef(it.id, it.name, it.type.name) }

    private fun todaysClasses(studentId: StudentId, institutionId: InstitutionId) =
        enrollments.findCurrent(studentId)
            .mapNotNull { classrooms.find(it.classroomId, institutionId) }
            .filter { it.isHeldOn(clock.today()) }
            .sortedBy { it.startTime }

    private fun fail(
        device: StudentDevice, qr: CheckinQr, student: com.ttokttok.domain.student.Student?,
        reason: QrScanFailure, message: String, distance: Int? = null,
    ): Nothing {
        scanLogs.record(QrScanLog(qr.institutionId, student?.id, device.childId, qr.id, null, reason.name, distance, clock.now()))
        throw QrScanException(reason, message)
    }
}
