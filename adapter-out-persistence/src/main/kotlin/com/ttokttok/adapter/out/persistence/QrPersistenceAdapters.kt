package com.ttokttok.adapter.out.persistence

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// 학생앱 QR 출석 (스펙 7-7) — V8 테이블, JDBC

private fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
private fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }
private fun Instant?.ts(): Timestamp? = this?.let(Timestamp::from)

@Component
class CheckinQrJdbcAdapter(private val jdbc: JdbcTemplate) : CheckinQrPort {
    private val mapper = RowMapper { rs, _ ->
        CheckinQr(
            id = CheckinQrId(rs.uuid("id")), institutionId = InstitutionId(rs.uuid("institution_id")),
            name = rs.getString("name"), token = rs.getString("token"), active = rs.getBoolean("active"),
            createdAt = rs.instant("created_at"), rotatedAt = rs.instantOrNull("rotated_at"),
        )
    }

    override fun save(qr: CheckinQr): CheckinQr {
        jdbc.update(
            """insert into checkin_qr (id, institution_id, name, token, active, created_at, rotated_at)
               values (?, ?, ?, ?, ?, ?, ?)
               on conflict (id) do update set name = excluded.name, token = excluded.token, active = excluded.active, rotated_at = excluded.rotated_at""",
            qr.id.value, qr.institutionId.value, qr.name, qr.token, qr.active, Timestamp.from(qr.createdAt), qr.rotatedAt.ts(),
        )
        return qr
    }

    override fun find(id: CheckinQrId, institutionId: InstitutionId): CheckinQr? =
        jdbc.query("select * from checkin_qr where id = ? and institution_id = ?", mapper, id.value, institutionId.value).firstOrNull()

    override fun findByToken(token: String): CheckinQr? =
        jdbc.query("select * from checkin_qr where token = ? and active", mapper, token).firstOrNull()

    override fun findActiveByInstitution(institutionId: InstitutionId): List<CheckinQr> =
        jdbc.query("select * from checkin_qr where institution_id = ? and active order by created_at", mapper, institutionId.value)
}

@Component
class GeofenceJdbcAdapter(private val jdbc: JdbcTemplate) : GeofencePort {
    override fun find(institutionId: InstitutionId): Geofence? =
        jdbc.query(
            "select latitude, longitude, radius_m from institution_geofence where institution_id = ?",
            RowMapper { rs, _ -> Geofence(rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getInt("radius_m")) },
            institutionId.value,
        ).firstOrNull()

    override fun save(institutionId: InstitutionId, geofence: Geofence?) {
        if (geofence == null) {
            jdbc.update("delete from institution_geofence where institution_id = ?", institutionId.value)
            return
        }
        jdbc.update(
            """insert into institution_geofence (institution_id, latitude, longitude, radius_m, updated_at) values (?, ?, ?, ?, now())
               on conflict (institution_id) do update set latitude = excluded.latitude, longitude = excluded.longitude,
                   radius_m = excluded.radius_m, updated_at = now()""",
            institutionId.value, geofence.latitude, geofence.longitude, geofence.radiusMeters,
        )
    }
}

@Component
class StudentDeviceJdbcAdapter(private val jdbc: JdbcTemplate) : StudentDevicePort {
    private val mapper = RowMapper { rs, _ ->
        StudentDevice(
            id = StudentDeviceId(rs.uuid("id")), childId = ChildId(rs.uuid("child_id")), linkedBy = UserId(rs.uuid("linked_by")),
            deviceName = rs.getString("device_name"), tokenHash = rs.getString("token_hash"),
            createdAt = rs.instant("created_at"), lastSeenAt = rs.instantOrNull("last_seen_at"), revokedAt = rs.instantOrNull("revoked_at"),
        )
    }

    override fun save(device: StudentDevice): StudentDevice {
        jdbc.update(
            """insert into student_device (id, child_id, linked_by, device_name, token_hash, created_at, last_seen_at, revoked_at)
               values (?, ?, ?, ?, ?, ?, ?, ?)
               on conflict (id) do update set device_name = excluded.device_name, last_seen_at = excluded.last_seen_at, revoked_at = excluded.revoked_at""",
            device.id.value, device.childId.value, device.linkedBy.value, device.deviceName,
            device.tokenHash, Timestamp.from(device.createdAt), device.lastSeenAt.ts(), device.revokedAt.ts(),
        )
        return device
    }

    override fun find(id: StudentDeviceId): StudentDevice? =
        jdbc.query("select * from student_device where id = ?", mapper, id.value).firstOrNull()

    override fun findActiveByTokenHash(tokenHash: String): StudentDevice? =
        jdbc.query("select * from student_device where token_hash = ? and revoked_at is null", mapper, tokenHash).firstOrNull()

    override fun findActiveByChild(childId: ChildId): List<StudentDevice> =
        jdbc.query("select * from student_device where child_id = ? and revoked_at is null", mapper, childId.value)

    override fun reassignChild(from: ChildId, to: ChildId) {
        jdbc.update("update student_device set child_id = ? where child_id = ?", to.value, from.value)
    }

    override fun touch(id: StudentDeviceId, at: Instant) {
        jdbc.update("update student_device set last_seen_at = ? where id = ?", Timestamp.from(at), id.value)
    }
}

@Component
class StudentLinkCodeJdbcAdapter(private val jdbc: JdbcTemplate) : StudentLinkCodePort {
    private val mapper = RowMapper { rs, _ ->
        StudentLinkCode(
            code = rs.getString("code"), childId = ChildId(rs.uuid("child_id")),
            issuedBy = UserId(rs.uuid("issued_by")), expiresAt = rs.instant("expires_at"), usedAt = rs.instantOrNull("used_at"),
        )
    }

    override fun save(code: StudentLinkCode): StudentLinkCode {
        jdbc.update(
            """insert into student_link_code (code, child_id, issued_by, expires_at, used_at) values (?, ?, ?, ?, ?)
               on conflict (code) do update set used_at = excluded.used_at""",
            code.code, code.childId.value, code.issuedBy.value, Timestamp.from(code.expiresAt), code.usedAt.ts(),
        )
        // 오래된 코드 정리 (하루 지난 것)
        jdbc.update("delete from student_link_code where expires_at < now() - interval '1 day'")
        return code
    }

    /** 같은 코드를 두 기기가 동시에 쓰지 못하게 행 잠금 */
    override fun findForUpdate(code: String): StudentLinkCode? =
        jdbc.query("select * from student_link_code where code = ? for update", mapper, code).firstOrNull()

    override fun deleteByChild(childId: ChildId) {
        jdbc.update("delete from student_link_code where child_id = ?", childId.value)
    }
}

@Component
class QrScanLogJdbcAdapter(private val jdbc: JdbcTemplate) : QrScanLogPort {
    /** 스캔 실패는 예외로 끝나 바깥 트랜잭션이 롤백되므로 로그는 별도 트랜잭션으로 남긴다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(log: QrScanLog) {
        jdbc.update(
            "insert into qr_scan_log (institution_id, student_id, child_id, qr_id, classroom_id, outcome, distance_m, at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            log.institutionId.value, log.studentId?.value, log.childId?.value, log.qrId?.value, log.classroomId?.value, log.outcome, log.distanceMeters, Timestamp.from(log.at),
        )
    }

    override fun recentFailures(institutionId: InstitutionId, since: Instant, limit: Int): List<QrScanLog> =
        jdbc.query(
            "select * from qr_scan_log where institution_id = ? and at >= ? order by at desc limit ?",
            RowMapper { rs, _ ->
                QrScanLog(
                    InstitutionId(rs.uuid("institution_id")), rs.uuidOrNull("student_id")?.let(::StudentId),
                    rs.uuidOrNull("child_id")?.let(::ChildId), rs.uuidOrNull("qr_id")?.let(::CheckinQrId),
                    rs.uuidOrNull("classroom_id")?.let(::ClassroomId), rs.getString("outcome"), rs.intOrNull("distance_m"), rs.instant("at"),
                )
            },
            institutionId.value, Timestamp.from(since), limit,
        )

    override fun reassignChild(from: ChildId, to: ChildId) {
        jdbc.update("update qr_scan_log set child_id = ? where child_id = ?", to.value, from.value)
    }
}
