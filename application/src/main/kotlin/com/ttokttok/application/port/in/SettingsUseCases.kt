package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.PresignedUrl
import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.TermsAudience
import com.ttokttok.domain.terms.TermsId
import com.ttokttok.domain.terms.TermsType
import com.ttokttok.domain.user.Role
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** SET-001 기관 정보 */
interface InstitutionSettingsUseCase {
    fun get(actor: UserId, institutionId: InstitutionId): InstitutionView
    fun update(actor: UserId, institutionId: InstitutionId, command: UpdateCommand): InstitutionView

    data class UpdateCommand(
        val name: String,
        val ownerName: String,
        val address: String?,
        val phone: String?,
        val lateThresholdMinutes: Int,
        val earlyLeaveThresholdMinutes: Int,
        val logoFileId: FileId?,
        val sealFileId: FileId?,
    )
}

data class InstitutionView(
    val id: InstitutionId,
    val name: String,
    val ownerName: String,
    val address: String?,
    val phone: String?,
    val lateThresholdMinutes: Int,
    val earlyLeaveThresholdMinutes: Int,
    val logo: FileRef?,
    val seal: FileRef?,
)

/** 다운로드 URL 은 5분 만료 */
data class FileRef(val id: FileId, val name: String, val mime: String, val size: Long, val downloadUrl: String?)

/** 공통 파일 업로드 (S3 직접 업로드) */
interface FileUseCase {
    fun presign(actor: UserId, institutionId: InstitutionId, purpose: FilePurpose, filename: String, mime: String, size: Long): PresignResult
    fun complete(actor: UserId, institutionId: InstitutionId, id: FileId): FileRef
    fun download(actor: UserId, institutionId: InstitutionId, id: FileId): PresignedUrl

    data class PresignResult(val fileId: FileId, val upload: PresignedUrl)
}

/** ATT-003 결석 증빙 첨부·해제 */
interface AttendanceEvidenceUseCase {
    fun attach(actor: UserId, institutionId: InstitutionId, dayId: AttendanceDayId, fileId: FileId?): AttendanceView
}

/** STF-002 교직원 이메일 초대 */
interface StaffInvitationUseCase {
    fun invite(actor: UserId, institutionId: InstitutionId, email: String, name: String, role: Role): StaffInvitationView
    fun list(actor: UserId, institutionId: InstitutionId): List<StaffInvitationView>
    fun revoke(actor: UserId, institutionId: InstitutionId, id: StaffInvitationId)
    /** 공개 — 링크 열람 */
    fun info(token: String): PublicInvitationInfo
    /** 공개 — 수락. 같은 이메일 계정이 있으면 비밀번호 확인 후 소속만 추가 */
    fun accept(token: String, password: String): AuthenticatedUser

    data class StaffInvitationView(
        val id: StaffInvitationId, val email: String, val name: String, val role: Role,
        val status: String, val expiresAt: Instant, val acceptedAt: Instant?,
    )

    data class PublicInvitationInfo(val institutionName: String, val name: String, val email: String, val role: Role, val existingAccount: Boolean, val expiresAt: Instant)
}

/** refresh 토큰 세션 (회전·재사용 감지·로그아웃) */
interface SessionUseCase {
    fun start(userId: UserId, rememberMe: Boolean): Session
    /** 사용된 refresh 는 폐기하고 같은 family 로 새로 발급. 폐기된 토큰 재사용 = 탈취로 보고 family 전체 폐기 */
    fun rotate(jti: UUID, userId: UserId): Session
    fun end(jti: UUID)
    fun endAll(userId: UserId)

    data class Session(val jti: UUID, val userId: UserId, val rememberMe: Boolean, val expiresAt: Instant)
}

/** SET-005 약관·동의 */
interface TermsUseCase {
    fun current(audience: TermsAudience): List<TermsView>
    fun pending(userId: UserId, audience: TermsAudience): List<TermsView>
    fun agree(userId: UserId, termsIds: List<TermsId>, ip: String?)

    data class TermsView(val id: TermsId, val type: TermsType, val version: Int, val title: String, val body: String, val required: Boolean, val effectiveAt: Instant)
}

/** PAR-003 자녀 주간 스케줄: 수업(반 시간표) + 행사 + 그 주 출결 */
interface ParentScheduleQuery {
    fun week(parent: UserId, childId: StudentId?, weekStart: LocalDate?): WeekSchedule
}

data class WeekSchedule(val weekStart: LocalDate, val children: List<ChildWeek>)

data class ChildWeek(val studentId: StudentId, val name: String, val days: List<DaySchedule>)

data class DaySchedule(val date: LocalDate, val dayOfWeek: DayOfWeek, val items: List<ScheduleItem>)

data class ScheduleItem(
    /** CLASS | EVENT */
    val kind: String,
    val title: String,
    val institutionName: String,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val classroomId: ClassroomId?,
    val eventId: UUID?,
    /** 수업 일정이면 그날 출결 상태(SCHEDULED/IN/OUT/ABSENT), 행사면 내 응답(ATTEND/ABSENT) */
    val status: String?,
)

/** DASH-003 주요 일정 위젯 */
interface DashboardScheduleQuery {
    fun upcoming(actor: UserId, institutionId: InstitutionId, days: Int): List<DashboardScheduleItem>
}

data class DashboardScheduleItem(
    /** EVENT | RSVP_DEADLINE | NOTICE_SCHEDULED */
    val kind: String,
    val at: Instant,
    val title: String,
    val refId: UUID,
    val detail: String?,
)
