package com.ttokttok.application.port.`in`

import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.qr.CheckinQrId
import com.ttokttok.domain.qr.Geofence
import com.ttokttok.domain.qr.QrScanFailure
import com.ttokttok.domain.qr.QrScanOutcome
import com.ttokttok.domain.qr.StudentDeviceId
import java.time.Instant

// 학생앱 QR 출석 (스펙 7-7)

/** QR-001·002 관리자: 출석 QR 과 기관 위치 */
interface CheckinQrAdminUseCase {
    fun list(actor: UserId, institutionId: InstitutionId): List<QrView>
    fun create(actor: UserId, institutionId: InstitutionId, name: String): QrView
    fun rename(actor: UserId, institutionId: InstitutionId, id: CheckinQrId, name: String): QrView
    /** 토큰 교체 — 이전 인쇄물은 즉시 무효 */
    fun rotate(actor: UserId, institutionId: InstitutionId, id: CheckinQrId): QrView
    fun delete(actor: UserId, institutionId: InstitutionId, id: CheckinQrId)

    fun geofence(actor: UserId, institutionId: InstitutionId): Geofence?
    /** OWNER. null 이면 위치 확인 끔 */
    fun setGeofence(actor: UserId, institutionId: InstitutionId, geofence: Geofence?): Geofence?

    fun recentFailures(actor: UserId, institutionId: InstitutionId): List<ScanFailureView>

    /** content = 인쇄할 QR 에 담을 문자열 (https://ttok.app/qr/{token}) */
    data class QrView(val id: CheckinQrId, val name: String, val content: String, val createdAt: Instant, val rotatedAt: Instant?)
    /** studentId null = 이 기관에 등록되지 않은 아이가 찍음 */
    data class ScanFailureView(val studentId: StudentId?, val studentName: String, val reason: String, val distanceMeters: Int?, val at: Instant)
}

/**
 * PAR-007 보호자: 아이 기기 연결 코드 발급·연결된 기기 관리.
 * 기기는 아이에 묶인다 (스펙 7-8) — `id` 는 아이 id, 이전 앱이 보내는 원생 id 도 그 원생의 아이로 받는다.
 */
interface StudentDeviceLinkUseCase {
    fun issueCode(parent: UserId, id: java.util.UUID): LinkCodeView
    fun devices(parent: UserId, id: java.util.UUID): List<DeviceView>
    fun revoke(parent: UserId, id: java.util.UUID, deviceId: StudentDeviceId)

    data class LinkCodeView(val code: String, val expiresAt: Instant)
    data class DeviceView(val id: StudentDeviceId, val deviceName: String, val createdAt: Instant, val lastSeenAt: Instant?)
}

/** STD-001·002 학생앱 (기기 토큰 인증, 사용자 계정 없음) */
interface StudentAppUseCase {
    /** 공개 — 연결 코드로 기기 토큰 발급. 토큰은 이 응답에서 한 번만 내려간다 */
    fun link(code: String, deviceName: String): LinkedDevice

    fun me(deviceToken: String): StudentHome

    fun scan(deviceToken: String, command: ScanCommand): ScanResult

    fun logout(deviceToken: String)

    data class InstitutionRef(val id: InstitutionId, val name: String, val type: String)

    /** 기기는 아이에 묶인다 — 아이가 다니는 모든 기관에서 같은 폰으로 출석 (스펙 7-8) */
    data class LinkedDevice(val deviceToken: String, val childId: ChildId, val childName: String, val institutions: List<InstitutionRef>)

    data class TodayClass(
        val institutionId: InstitutionId, val institutionName: String,
        val classroomId: String, val classroomName: String, val startTime: String, val endTime: String, val attendance: AttendanceView?,
    )
    data class StudentHome(val childId: ChildId, val childName: String, val institutions: List<InstitutionRef>, val today: List<TodayClass>)

    data class ScanCommand(
        val qrContent: String,
        val latitude: Double?,
        val longitude: Double?,
        val accuracyMeters: Double?,
        val destinationId: DestinationId?,
        val clientAt: Instant?,
        val idempotencyKey: String?,
    )

    data class DestinationOption(val id: DestinationId, val name: String, val type: String)

    data class ScanResult(
        val outcome: QrScanOutcome,
        val classroomName: String?,
        val institutionName: String?,
        val attendance: AttendanceView?,
        /** CHOOSE_DESTINATION 일 때 고를 목록 */
        val destinations: List<DestinationOption> = emptyList(),
    )
}

/** 스캔 실패 — 코드가 곧 사유 (QR_INVALID, OUT_OF_RANGE …). 웹 어댑터가 422 로 내린다 */
class QrScanException(val reason: QrScanFailure, message: String) : RuntimeException(message)
