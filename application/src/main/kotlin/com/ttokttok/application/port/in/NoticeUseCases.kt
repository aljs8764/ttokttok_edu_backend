package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticeStatus
import com.ttokttok.domain.notice.NoticeTarget
import java.time.Instant

/** NTC-001 작성 · NTC-002 예약 · NTC-009 전체 공지 */
interface ComposeNoticeUseCase {
    /** sendAt 이 null 이면 즉시 발송 */
    fun create(command: Command): NoticeView
    fun update(actor: UserId, institutionId: InstitutionId, id: NoticeId, command: Command): NoticeView
    fun cancel(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeView

    data class Command(
        val actor: UserId,
        val institutionId: InstitutionId,
        val kind: NoticeKind,
        val title: String,
        val body: String,
        val pinned: Boolean,
        val targets: List<NoticeTarget>,
        val sendAt: Instant?,
        /** 업로드 완료(NOTICE_ATTACHMENT)된 파일 id, 최대 10개 */
        val attachments: List<FileId> = emptyList(),
    )
}

/** NTC-004 발송 리스트 · NTC-005 수신확인 */
interface NoticeQuery {
    fun list(actor: UserId, institutionId: InstitutionId, kind: NoticeKind?, status: NoticeStatus?, page: Int, size: Int): PageResult<NoticeView>
    fun detail(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeView
    fun receipts(actor: UserId, institutionId: InstitutionId, id: NoticeId): NoticeReceipts
}

/** NTC-006 미열람 재발송 (30분 쿨타임) */
interface ResendUnreadNoticeUseCase {
    fun resend(actor: UserId, institutionId: InstitutionId, id: NoticeId): ResendResult
    /** 앱 미연결 보호자(알림톡 안내 대상)는 재발송하지 않는다 */
    data class ResendResult(val students: Int, val pushRecipients: Int, val resentAt: Instant)
}

/** 매분 스케줄러 (NTC-002) */
interface PublishDueNoticesUseCase {
    fun publishDue(limit: Int): Int
}

/** 학부모 알림장함 (PAR-004) */
interface ParentNoticeUseCase {
    /** filter: 필터 (GetMyChildrenQuery.resolveFilter), null = 전체 */
    fun inbox(parent: UserId, filter: Set<StudentId>?, before: Instant?, limit: Int): List<ParentNoticeItem>
    fun detail(parent: UserId, id: NoticeId): ParentNoticeItem
    /** 상세 화면 최초 진입 시 호출. 푸시 수신만으로는 열람이 아니다 */
    fun markRead(parent: UserId, id: NoticeId)
}

data class NoticeView(
    val id: NoticeId,
    val kind: NoticeKind,
    val title: String,
    val body: String,
    val pinned: Boolean,
    val targets: List<TargetView>,
    val status: NoticeStatus,
    val scheduledAt: Instant,
    val sentAt: Instant?,
    val lastResentAt: Instant?,
    val authorId: UserId,
    val authorName: String,
    val createdAt: Instant,
    /** 발송 전이면 null */
    val readStats: ReadStats?,
    val attachments: List<FileRef> = emptyList(),
)

data class TargetView(val scope: String, val id: java.util.UUID?, val name: String)

data class ReadStats(val targetStudents: Int, val readStudents: Int, val rate: Double?)

data class NoticeReceipts(
    val noticeId: NoticeId,
    val stats: ReadStats,
    val canResendAt: Instant?,
    val students: List<StudentReceipt>,
)

data class StudentReceipt(
    val studentId: StudentId,
    val studentName: String,
    val classroomNames: List<String>,
    val read: Boolean,
    val firstReadAt: Instant?,
    val resentCount: Int,
    /** 앱 연결된 보호자가 없으면 빈 목록 (알림톡으로만 안내됨) */
    val guardians: List<GuardianReceipt>,
)

data class GuardianReceipt(val userId: UserId, val name: String, val deliveredAt: Instant?, val readAt: Instant?)

data class ParentNoticeItem(
    val id: NoticeId,
    val institutionId: InstitutionId,
    val institutionName: String,
    val kind: NoticeKind,
    val title: String,
    /** 목록에서는 앞 100자 미리보기, 상세에서는 전문 */
    val body: String,
    val pinned: Boolean,
    val sentAt: Instant,
    val children: List<ChildRef>,
    val readAt: Instant?,
    val authorName: String,
    /** 상세에서만 5분 다운로드 URL 포함 */
    val attachments: List<FileRef> = emptyList(),
)

data class ChildRef(val studentId: StudentId, val name: String)
