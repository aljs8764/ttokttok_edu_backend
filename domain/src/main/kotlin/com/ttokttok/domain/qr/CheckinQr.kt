package com.ttokttok.domain.qr

import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 학생앱 QR 출석 (스펙 7-7)

@JvmInline value class CheckinQrId(val value: UUID) { companion object { fun new() = CheckinQrId(Uuid7.next()) } }
@JvmInline value class StudentDeviceId(val value: UUID) { companion object { fun new() = StudentDeviceId(Uuid7.next()) } }

private val random = SecureRandom()

/** URL 에 그대로 넣을 수 있는 무작위 토큰 (bytes 바이트) */
internal fun randomToken(bytes: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

/**
 * QR-001 출석 QR. 기관 입구마다 하나씩 인쇄해 붙인다 (예: "1층 입구", "2층 강의실").
 * 내용은 토큰뿐이라 유출되면 rotate 로 토큰을 바꾸고 다시 인쇄한다 (이전 인쇄물은 즉시 무효).
 */
data class CheckinQr(
    val id: CheckinQrId,
    val institutionId: InstitutionId,
    val name: String,
    val token: String,
    val active: Boolean = true,
    val createdAt: Instant,
    val rotatedAt: Instant? = null,
) {
    init {
        if (name.isBlank() || name.length > 50) throw InvalidInputException("INVALID_NAME", "QR 이름은 1~50자입니다")
    }

    fun rotate(now: Instant): CheckinQr {
        if (!active) throw ConflictException("QR_INACTIVE", "삭제된 QR 입니다")
        return copy(token = randomToken(TOKEN_BYTES), rotatedAt = now)
    }

    fun rename(newName: String) = copy(name = newName.trim())

    fun deactivate() = copy(active = false)

    companion object {
        const val TOKEN_BYTES = 18
        const val MAX_PER_INSTITUTION = 20

        fun issue(institutionId: InstitutionId, name: String, now: Instant) =
            CheckinQr(CheckinQrId.new(), institutionId, name.trim(), randomToken(TOKEN_BYTES), createdAt = now)
    }
}

/**
 * QR-002 기관 위치. 스캔한 기기 GPS 가 이 반경 안이어야 처리한다 (QR 사진 공유로 밖에서 찍는 것 방지).
 * GPS 오차는 기기가 보고한 정확도만큼 봐주되 최대 [MAX_ACCURACY_CREDIT_M] 까지만 인정.
 */
data class Geofence(val latitude: Double, val longitude: Double, val radiusMeters: Int = DEFAULT_RADIUS_M) {
    init {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) throw InvalidInputException("INVALID_LOCATION", "위도·경도가 올바르지 않습니다")
        if (radiusMeters !in MIN_RADIUS_M..MAX_RADIUS_M) throw InvalidInputException("INVALID_RADIUS", "반경은 ${MIN_RADIUS_M}~${MAX_RADIUS_M}m 입니다")
    }

    /** 하버사인 거리 (m) */
    fun distanceTo(lat: Double, lng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat - latitude)
        val dLng = Math.toRadians(lng - longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun contains(lat: Double, lng: Double, accuracyMeters: Double?): Boolean {
        val credit = min(maxOf(accuracyMeters ?: 0.0, 0.0), MAX_ACCURACY_CREDIT_M)
        return distanceTo(lat, lng) - credit <= radiusMeters
    }

    companion object {
        const val DEFAULT_RADIUS_M = 150
        const val MIN_RADIUS_M = 30
        const val MAX_RADIUS_M = 1000
        const val MAX_ACCURACY_CREDIT_M = 100.0
    }
}

/**
 * 학생 기기. 학생은 계정이 없고, 보호자가 연결 코드로 이 기기를 자녀(원생)에 묶는다 (PAR-007 / STD-001).
 * 서버에는 기기 토큰의 SHA-256 만 둔다.
 */
data class StudentDevice(
    val id: StudentDeviceId,
    val studentId: StudentId,
    val institutionId: InstitutionId,
    val linkedBy: UserId,
    val deviceName: String,
    val tokenHash: String,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null,
    val revokedAt: Instant? = null,
) {
    val active get() = revokedAt == null

    fun revoke(now: Instant) = if (revokedAt != null) this else copy(revokedAt = now)

    companion object {
        const val MAX_ACTIVE_PER_STUDENT = 3
    }
}

/** 보호자가 만드는 1회용 기기 연결 코드. 헷갈리는 글자(0/O, 1/I/L) 제외 8자, 10분 유효 */
data class StudentLinkCode(
    val code: String,
    val studentId: StudentId,
    val institutionId: InstitutionId,
    val issuedBy: UserId,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
) {
    fun isUsable(now: Instant) = usedAt == null && now.isBefore(expiresAt)

    fun use(now: Instant): StudentLinkCode {
        if (!isUsable(now)) throw ConflictException("LINK_CODE_INVALID", "연결 코드가 만료되었거나 이미 사용되었습니다. 보호자 앱에서 새 코드를 만들어 주세요")
        return copy(usedAt = now)
    }

    companion object {
        val VALIDITY: Duration = Duration.ofMinutes(10)
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val LENGTH = 8

        fun issue(studentId: StudentId, institutionId: InstitutionId, issuedBy: UserId, now: Instant): StudentLinkCode {
            val code = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            return StudentLinkCode(code, studentId, institutionId, issuedBy, now.plus(VALIDITY))
        }

        fun normalize(raw: String) = raw.uppercase().filter { it.isLetterOrDigit() }
    }
}

/**
 * 스캔 시 어느 반으로 처리할지 고른다 (원생이 여러 반에 다닐 수 있음).
 * 1) 지금 등원(IN) 상태인 반 → 하원
 * 2) 시작 [EARLY_WINDOW] 전 ~ 종료 사이인 반 (여럿이면 시작이 빠른 반)
 * 3) 오늘 남은 반 중 가장 가까운 반 (조금 일찍 온 경우)
 * 오늘 수업이 없거나 다 끝났으면 null.
 */
object QrClassPicker {
    val EARLY_WINDOW: Duration = Duration.ofMinutes(60)

    data class Candidate(val classroom: Classroom, val status: AttendanceStatus)

    fun pick(candidates: List<Candidate>, now: LocalTime): Candidate? {
        candidates.firstOrNull { it.status == AttendanceStatus.IN }?.let { return it }
        val open = candidates.filter { it.status == AttendanceStatus.SCHEDULED }
        open.filter { !now.isBefore(it.classroom.startTime.minus(EARLY_WINDOW)) && !now.isAfter(it.classroom.endTime) }
            .minByOrNull { it.classroom.startTime }?.let { return it }
        return open.filter { it.classroom.startTime.isAfter(now) }.minByOrNull { it.classroom.startTime }
    }
}

/** 스캔 결과 종류 (앱이 화면을 고른다) */
enum class QrScanOutcome { CHECKED_IN, CHECKED_OUT, CHOOSE_DESTINATION, ALREADY_DONE }

/** 실패 사유 — qr_scan_log 에 남겨 관리자가 부정 시도를 본다 */
enum class QrScanFailure { QR_INVALID, NOT_ENROLLED, OUT_OF_RANGE, NO_CLASS_NOW, LOCATION_REQUIRED }

data class QrScanLog(
    val institutionId: InstitutionId,
    val studentId: StudentId,
    val qrId: CheckinQrId?,
    val classroomId: ClassroomId?,
    val outcome: String,
    val distanceMeters: Int?,
    val at: Instant,
)
