package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.CheckinQrEntity
import com.ttokttok.adapter.out.persistence.entity.InstitutionGeofenceEntity
import com.ttokttok.adapter.out.persistence.entity.QrScanLogEntity
import com.ttokttok.adapter.out.persistence.entity.StudentDeviceEntity
import com.ttokttok.adapter.out.persistence.entity.StudentLinkCodeEntity
import com.ttokttok.adapter.out.persistence.repository.CheckinQrJpaRepository
import com.ttokttok.adapter.out.persistence.repository.InstitutionGeofenceJpaRepository
import com.ttokttok.adapter.out.persistence.repository.QrScanLogJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentDeviceJpaRepository
import com.ttokttok.adapter.out.persistence.repository.StudentLinkCodeJpaRepository
import com.ttokttok.application.port.out.CheckinQrPort
import com.ttokttok.application.port.out.GeofencePort
import com.ttokttok.application.port.out.QrScanLogPort
import com.ttokttok.application.port.out.StudentDevicePort
import com.ttokttok.application.port.out.StudentLinkCodePort
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.qr.CheckinQr
import com.ttokttok.domain.qr.CheckinQrId
import com.ttokttok.domain.qr.Geofence
import com.ttokttok.domain.qr.QrScanLog
import com.ttokttok.domain.qr.StudentDevice
import com.ttokttok.domain.qr.StudentDeviceId
import com.ttokttok.domain.qr.StudentLinkCode
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

// 학생앱 QR 출석 (스펙 7-7)

@Component
class CheckinQrJpaAdapter(private val repo: CheckinQrJpaRepository) : CheckinQrPort {

    private fun CheckinQrEntity.toDomain() = CheckinQr(
        id = CheckinQrId(id), institutionId = InstitutionId(institutionId),
        name = name, token = token, active = active,
        createdAt = createdAt, rotatedAt = rotatedAt,
    )

    override fun save(qr: CheckinQr): CheckinQr {
        repo.save(CheckinQrEntity(qr.id.value, qr.institutionId.value, qr.name, qr.token, qr.active, qr.createdAt, qr.rotatedAt))
        return qr
    }

    override fun find(id: CheckinQrId, institutionId: InstitutionId): CheckinQr? = repo.findByIdAndInstitutionId(id.value, institutionId.value)?.toDomain()

    override fun findByToken(token: String): CheckinQr? = repo.findByTokenAndActiveTrue(token)?.toDomain()

    override fun findActiveByInstitution(institutionId: InstitutionId): List<CheckinQr> =
        repo.findByInstitutionIdAndActiveTrueOrderByCreatedAt(institutionId.value).map { it.toDomain() }
}

@Component
class GeofenceJpaAdapter(private val repo: InstitutionGeofenceJpaRepository) : GeofencePort {
    override fun find(institutionId: InstitutionId): Geofence? =
        repo.findByIdOrNull(institutionId.value)?.let { Geofence(it.latitude, it.longitude, it.radiusM) }

    override fun save(institutionId: InstitutionId, geofence: Geofence?) {
        if (geofence == null) {
            if (repo.existsById(institutionId.value)) repo.deleteById(institutionId.value)
            return
        }
        repo.save(InstitutionGeofenceEntity(institutionId.value, geofence.latitude, geofence.longitude, geofence.radiusMeters, Instant.now()))
    }
}

@Component
class StudentDeviceJpaAdapter(private val repo: StudentDeviceJpaRepository) : StudentDevicePort {

    private fun StudentDeviceEntity.toDomain() = StudentDevice(
        id = StudentDeviceId(id), childId = ChildId(childId), linkedBy = UserId(linkedBy),
        deviceName = deviceName, tokenHash = tokenHash,
        createdAt = createdAt, lastSeenAt = lastSeenAt, revokedAt = revokedAt,
    )

    override fun save(device: StudentDevice): StudentDevice {
        repo.save(
            StudentDeviceEntity(
                device.id.value, device.childId.value, device.linkedBy.value, device.deviceName,
                device.tokenHash, device.createdAt, device.lastSeenAt, device.revokedAt,
            ),
        )
        return device
    }

    override fun find(id: StudentDeviceId): StudentDevice? = repo.findByIdOrNull(id.value)?.toDomain()

    override fun findActiveByTokenHash(tokenHash: String): StudentDevice? = repo.findByTokenHashAndRevokedAtIsNull(tokenHash)?.toDomain()

    override fun findActiveByChild(childId: ChildId): List<StudentDevice> = repo.findByChildIdAndRevokedAtIsNull(childId.value).map { it.toDomain() }

    override fun reassignChild(from: ChildId, to: ChildId) {
        repo.reassignChild(from.value, to.value)
    }

    override fun touch(id: StudentDeviceId, at: Instant) {
        repo.touch(id.value, at)
    }
}

@Component
class StudentLinkCodeJpaAdapter(private val repo: StudentLinkCodeJpaRepository) : StudentLinkCodePort {

    private fun StudentLinkCodeEntity.toDomain() = StudentLinkCode(
        code = code, childId = ChildId(childId), issuedBy = UserId(issuedBy), expiresAt = expiresAt, usedAt = usedAt,
    )

    override fun save(code: StudentLinkCode): StudentLinkCode {
        repo.save(StudentLinkCodeEntity(code.code, code.childId.value, code.issuedBy.value, code.expiresAt, code.usedAt))
        // 오래된 코드 정리 (하루 지난 것)
        repo.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)))
        return code
    }

    /** 같은 코드를 두 기기가 동시에 쓰지 못하게 행 잠금 */
    override fun findForUpdate(code: String): StudentLinkCode? = repo.lockByCode(code)?.toDomain()

    override fun deleteByChild(childId: ChildId) {
        repo.deleteByChild(childId.value)
    }
}

@Component
class QrScanLogJpaAdapter(private val repo: QrScanLogJpaRepository) : QrScanLogPort {
    /** 스캔 실패는 예외로 끝나 바깥 트랜잭션이 롤백되므로 로그는 별도 트랜잭션으로 남긴다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(log: QrScanLog) {
        repo.save(
            QrScanLogEntity(
                null, log.institutionId.value, log.studentId?.value, log.childId?.value, log.qrId?.value, log.classroomId?.value,
                log.outcome, log.distanceMeters, log.at,
            ),
        )
    }

    override fun recentFailures(institutionId: InstitutionId, since: Instant, limit: Int): List<QrScanLog> =
        repo.findRecent(institutionId.value, since, PageRequest.of(0, limit)).map {
            QrScanLog(
                InstitutionId(it.institutionId), it.studentId?.let(::StudentId),
                it.childId?.let(::ChildId), it.qrId?.let(::CheckinQrId),
                it.classroomId?.let(::ClassroomId), it.outcome, it.distanceM, it.at,
            )
        }

    override fun reassignChild(from: ChildId, to: ChildId) {
        repo.reassignChild(from.value, to.value)
    }
}
