package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.Notice
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticeRecipient
import com.ttokttok.domain.notice.NoticeStatus
import java.time.Instant

interface NoticePort {
    fun save(notice: Notice): Notice
    fun find(id: NoticeId, institutionId: InstitutionId): Notice?
    /** 같은 알림장 동시 발송·재발송 직렬화 */
    fun findForUpdate(id: NoticeId, institutionId: InstitutionId): Notice?
    fun findAllByIds(ids: Collection<NoticeId>): List<Notice>
    /** 매분 스케줄러: 발송 시각이 지난 예약 건 (FOR UPDATE SKIP LOCKED — 워커 여러 대 안전) */
    fun lockDue(now: Instant, limit: Int): List<Notice>
    /** 관리자 웹 발송 이력 (NTC-004). authorId 가 있으면 작성자 본인 것만 */
    fun search(criteria: NoticeSearchCriteria): PageResult<Notice>
    /** 대시보드 공지 열람률: 기간 내 발송 건 */
    fun findSentBetween(institutionId: InstitutionId, from: Instant, to: Instant, authorId: UserId?): List<Notice>
}

data class NoticeSearchCriteria(
    val institutionId: InstitutionId,
    val authorId: UserId?,
    val kind: NoticeKind?,
    val status: NoticeStatus?,
    val page: Int,
    val size: Int,
)

interface NoticeRecipientPort {
    fun saveAll(recipients: List<NoticeRecipient>)
    fun findByNotice(noticeId: NoticeId): List<NoticeRecipient>
    fun findByNotices(noticeIds: Collection<NoticeId>): List<NoticeRecipient>
    /** 최초 열람만 기록. 처음 읽은 행이 있으면 true */
    fun markRead(noticeId: NoticeId, userId: UserId, at: Instant): Boolean
    fun markDelivered(noticeId: NoticeId, userIds: Collection<UserId>, at: Instant)
    fun incrementResent(noticeId: NoticeId, studentIds: Collection<StudentId>)
    /** 학부모 알림장함 (PAR-004): 발송 시각 최신순 notice id. before = 커서(sent_at) */
    fun findInboxNoticeIds(userId: UserId, studentIds: Collection<StudentId>?, before: Instant?, limit: Int): List<NoticeId>
    fun findForUser(noticeIds: Collection<NoticeId>, userId: UserId): List<NoticeRecipient>
}
