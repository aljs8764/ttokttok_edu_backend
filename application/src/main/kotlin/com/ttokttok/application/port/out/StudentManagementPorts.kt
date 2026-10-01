package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.invitation.Invitation
import com.ttokttok.domain.invitation.JoinRequest
import com.ttokttok.domain.invitation.JoinRequestId
import com.ttokttok.domain.invitation.JoinRequestStatus
import com.ttokttok.domain.student.StudentStatusChange
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface StudentStatusHistoryPort {
    fun save(change: StudentStatusChange, actor: UserId, at: Instant)
    fun findByStudent(studentId: StudentId): List<StudentStatusChange>
}

interface InvitationPort {
    fun save(invitation: Invitation): Invitation
    fun findByToken(token: String): Invitation?
}

interface JoinRequestPort {
    fun save(request: JoinRequest): JoinRequest
    fun find(id: JoinRequestId, institutionId: InstitutionId): JoinRequest?
    fun findByInstitution(institutionId: InstitutionId, status: JoinRequestStatus?): List<JoinRequest>
}

/** STU-002 엑셀 원본 행 (헤더 이름 → 셀 문자열) */
data class SpreadsheetRow(val rowNumber: Int, val cells: Map<String, String>)

/** 엑셀 읽기·쓰기 (구현: adapter-out-storage, Apache POI) */
interface SpreadsheetPort {
    fun read(bytes: ByteArray, maxRows: Int): List<SpreadsheetRow>
    fun write(sheetName: String, headers: List<String>, rows: List<List<String>>, notes: List<String> = emptyList()): ByteArray
}

/** 검증을 통과해 확정 대기 중인 업로드 행 */
data class ImportCandidate(
    val rowNumber: Int,
    val name: String,
    val birthDate: LocalDate,
    val classroomName: String,
    val guardianPhone: PhoneNumber,
    val relation: String?,
    val grade: String?,
    val memo: String?,
)

data class ImportError(val rowNumber: Int, val column: String, val message: String)

enum class ImportJobStatus { VALIDATED, COMMITTED }

data class ImportJob(
    val id: UUID,
    val institutionId: InstitutionId,
    val createdBy: UserId,
    val status: ImportJobStatus,
    val candidates: List<ImportCandidate>,
    val errors: List<ImportError>,
    val totalRows: Int,
    val createdAt: Instant,
)

/** 업로드 검증 결과 보관. 연락처가 담기므로 구현체는 암호화 저장해야 한다. */
interface ImportJobPort {
    fun save(job: ImportJob): ImportJob
    fun find(id: UUID, institutionId: InstitutionId): ImportJob?
}

/** 이메일 (구현: SES / 로그) — 임시 비밀번호처럼 Outbox에 남기면 안 되는 내용은 직접 발송 */
interface SendEmailPort {
    fun send(to: String, subject: String, body: String)
}

/** 알림톡(실패 시 SMS 대체) — 구현: 대행사 API / 로그 */
interface SendAlimtalkPort {
    fun send(phone: PhoneNumber, templateCode: String, variables: Map<String, String>): Boolean
}

/** 링크 생성용 설정값 (초대 링크 베이스 URL, 앱 설치 URL) */
interface AppLinksPort {
    fun joinUrl(token: String): String
    fun appInstallUrl(): String
}
