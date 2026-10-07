package com.ttokttok.application.port.out

import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.qr.CheckinQr
import com.ttokttok.domain.qr.CheckinQrId
import com.ttokttok.domain.qr.Geofence
import com.ttokttok.domain.qr.QrScanLog
import com.ttokttok.domain.qr.StudentDevice
import com.ttokttok.domain.qr.StudentDeviceId
import com.ttokttok.domain.qr.StudentLinkCode
import java.time.Instant

// 학생앱 QR 출석 (스펙 7-7)

interface CheckinQrPort {
    fun save(qr: CheckinQr): CheckinQr
    fun find(id: CheckinQrId, institutionId: InstitutionId): CheckinQr?
    /** 활성 QR 만 */
    fun findByToken(token: String): CheckinQr?
    fun findActiveByInstitution(institutionId: InstitutionId): List<CheckinQr>
}

interface GeofencePort {
    fun find(institutionId: InstitutionId): Geofence?
    fun save(institutionId: InstitutionId, geofence: Geofence?)
}

interface StudentDevicePort {
    fun save(device: StudentDevice): StudentDevice
    fun find(id: StudentDeviceId): StudentDevice?
    /** 해제되지 않은 기기만 */
    fun findActiveByTokenHash(tokenHash: String): StudentDevice?
    fun findActiveByChild(childId: ChildId): List<StudentDevice>
    /** 아이 합치기: source 아이의 기기를 target 으로 */
    fun reassignChild(from: ChildId, to: ChildId)
    /** 마지막 사용 시각 — 하루 한 번 정도만 쓰면 되므로 실패해도 무시 */
    fun touch(id: StudentDeviceId, at: Instant)
}

interface StudentLinkCodePort {
    fun save(code: StudentLinkCode): StudentLinkCode
    fun findForUpdate(code: String): StudentLinkCode?
    fun deleteByChild(childId: ChildId)
}

interface QrScanLogPort {
    fun record(log: QrScanLog)
    /** 관리자 화면: 최근 실패 시도 */
    fun recentFailures(institutionId: InstitutionId, since: Instant, limit: Int): List<QrScanLog>
    fun reassignChild(from: ChildId, to: ChildId)
}
