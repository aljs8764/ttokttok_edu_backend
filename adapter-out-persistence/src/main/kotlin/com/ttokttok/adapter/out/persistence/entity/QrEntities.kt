package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// 학생앱 QR 출석 (스펙 7-7) — V8, V9(아이 단위로 이동)

@Entity @Table(name = "checkin_qr")
class CheckinQrEntity(
    @Id var id: UUID,
    var institutionId: UUID,
    var name: String,
    var token: String,
    var active: Boolean,
    var createdAt: Instant,
    var rotatedAt: Instant?,
)

/** 기관 위치(지오펜스) */
@Entity @Table(name = "institution_geofence")
class InstitutionGeofenceEntity(
    @Id var institutionId: UUID,
    var latitude: Double,
    var longitude: Double,
    @Column(name = "radius_m")
    var radiusM: Int,
    var updatedAt: Instant,
)

/** 학생 기기 — 보호자가 연결한 기기 토큰의 SHA-256 만 저장. 아이에 묶는다 */
@Entity @Table(name = "student_device")
class StudentDeviceEntity(
    @Id var id: UUID,
    var childId: UUID,
    var linkedBy: UUID,
    var deviceName: String,
    var tokenHash: String,
    var createdAt: Instant,
    var lastSeenAt: Instant?,
    var revokedAt: Instant?,
)

/** 기기 연결 코드 (10분 1회용) */
@Entity @Table(name = "student_link_code")
class StudentLinkCodeEntity(
    @Id var code: String,
    var childId: UUID,
    var issuedBy: UUID,
    var expiresAt: Instant,
    var usedAt: Instant?,
)

/** 스캔 실패 로그 — 위치는 저장하지 않고 반경 밖 거리(m)만 */
@Entity @Table(name = "qr_scan_log")
class QrScanLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var institutionId: UUID,
    var studentId: UUID?,
    var childId: UUID?,
    var qrId: UUID?,
    var classroomId: UUID?,
    var outcome: String,
    @Column(name = "distance_m")
    var distanceM: Int?,
    var at: Instant,
)
