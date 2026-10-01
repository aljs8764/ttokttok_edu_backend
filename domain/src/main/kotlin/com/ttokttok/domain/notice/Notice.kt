package com.ttokttok.domain.notice

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import java.time.Duration
import java.time.Instant
import java.util.UUID

@JvmInline value class NoticeId(val value: UUID) { companion object { fun new() = NoticeId(Uuid7.next()) } }

/** NOTE = 알림장(NTC-001, 반·학생 대상), ANNOUNCEMENT = 전체 공지(NTC-009, 관리자 전용) */
enum class NoticeKind { NOTE, ANNOUNCEMENT }

/**
 * 즉시 발송도 SCHEDULED(scheduledAt = 지금)로 만든 뒤 바로 publish 한다 — 발송 경로를 하나로.
 * 예약 취소는 CANCELED 로 바꾸는 것만으로 끝난다(지연 job 없음, 스펙 5장 스케줄 작업).
 */
enum class NoticeStatus { SCHEDULED, SENT, CANCELED }

enum class TargetScope { ALL, CLASS, STUDENT }

data class NoticeTarget(val scope: TargetScope, val classroomId: ClassroomId? = null, val studentId: StudentId? = null) {
    init {
        val ok = when (scope) {
            TargetScope.ALL -> classroomId == null && studentId == null
            TargetScope.CLASS -> classroomId != null && studentId == null
            TargetScope.STUDENT -> studentId != null && classroomId == null
        }
        if (!ok) throw InvalidInputException("INVALID_TARGET", "대상 지정이 올바르지 않습니다 ($scope)")
    }

    companion object {
        fun all() = NoticeTarget(TargetScope.ALL)
        fun classroom(id: ClassroomId) = NoticeTarget(TargetScope.CLASS, classroomId = id)
        fun student(id: StudentId) = NoticeTarget(TargetScope.STUDENT, studentId = id)
    }
}

data class Notice(
    val id: NoticeId,
    val institutionId: InstitutionId,
    val authorId: UserId,
    val kind: NoticeKind,
    val title: String,
    val body: String,
    val pinned: Boolean,
    val targets: List<NoticeTarget>,
    val status: NoticeStatus,
    val scheduledAt: Instant,
    val sentAt: Instant? = null,
    val lastResentAt: Instant? = null,
    val createdAt: Instant,
) {
    init {
        if (title.isBlank() || title.length > MAX_TITLE) throw InvalidInputException("INVALID_TITLE", "제목은 1~${MAX_TITLE}자입니다")
        if (body.isBlank() || body.length > MAX_BODY) throw InvalidInputException("INVALID_BODY", "본문은 1~${MAX_BODY}자입니다")
        if (targets.isEmpty()) throw InvalidInputException("TARGET_REQUIRED", "받는 대상을 하나 이상 지정하세요")
        if (targets.size > MAX_TARGETS) throw InvalidInputException("TOO_MANY_TARGETS", "대상은 최대 ${MAX_TARGETS}개입니다")
        if (kind == NoticeKind.ANNOUNCEMENT && targets != listOf(NoticeTarget.all()))
            throw InvalidInputException("ANNOUNCEMENT_TARGET", "전체 공지는 기관 전체 대상으로만 보낼 수 있습니다")
        if (TargetScope.ALL in targets.map { it.scope } && targets.size > 1)
            throw InvalidInputException("INVALID_TARGET", "전체 대상은 다른 대상과 함께 지정할 수 없습니다")
    }

    val targetsAll: Boolean get() = targets.any { it.scope == TargetScope.ALL }

    fun isDue(now: Instant) = status == NoticeStatus.SCHEDULED && !scheduledAt.isAfter(now)

    /** 예약 상태에서만 수정 가능. 발송된 알림장은 수정 불가(수신 스냅샷과 어긋남) */
    fun edit(title: String, body: String, pinned: Boolean, targets: List<NoticeTarget>, sendAt: Instant?, now: Instant): Notice {
        requireScheduled("수정")
        return copy(title = title.trim(), body = body.trim(), pinned = pinned, targets = targets.distinct(), scheduledAt = resolveSendAt(sendAt, now))
    }

    fun cancel(): Notice {
        requireScheduled("취소")
        return copy(status = NoticeStatus.CANCELED)
    }

    fun publish(now: Instant): Notice {
        requireScheduled("발송")
        return copy(status = NoticeStatus.SENT, sentAt = now)
    }

    /** 미열람 재발송 (NTC-006): 같은 알림장 기준 30분 쿨타임 */
    fun markResent(now: Instant): Notice {
        if (status != NoticeStatus.SENT) throw ConflictException("NOT_SENT", "발송된 알림장만 재발송할 수 있습니다")
        val last = lastResentAt
        if (last != null) {
            val wait = Duration.between(now, last.plus(RESEND_COOLDOWN))
            if (!wait.isNegative && !wait.isZero)
                throw ConflictException("RESEND_COOLDOWN", "재발송은 30분에 한 번만 가능합니다 (${wait.toMinutes() + 1}분 후 가능)")
        }
        return copy(lastResentAt = now)
    }

    private fun requireScheduled(action: String) {
        if (status != NoticeStatus.SCHEDULED) throw ConflictException("NOT_SCHEDULED", "예약 상태의 알림장만 ${action}할 수 있습니다 (현재 $status)")
    }

    companion object {
        const val MAX_TITLE = 100
        const val MAX_BODY = 5000
        const val MAX_TARGETS = 200
        val RESEND_COOLDOWN: Duration = Duration.ofMinutes(30)
        val MAX_SCHEDULE_AHEAD: Duration = Duration.ofDays(30)

        /** sendAt 이 null 이면 즉시 발송 */
        fun compose(
            institutionId: InstitutionId, author: UserId, kind: NoticeKind, title: String, body: String,
            pinned: Boolean, targets: List<NoticeTarget>, sendAt: Instant?, now: Instant,
        ) = Notice(
            id = NoticeId.new(), institutionId = institutionId, authorId = author, kind = kind,
            title = title.trim(), body = body.trim(), pinned = pinned, targets = targets.distinct(),
            status = NoticeStatus.SCHEDULED, scheduledAt = resolveSendAt(sendAt, now), createdAt = now,
        )

        private fun resolveSendAt(sendAt: Instant?, now: Instant): Instant {
            if (sendAt == null) return now
            if (!sendAt.isAfter(now)) throw InvalidInputException("INVALID_SCHEDULE", "예약 시각은 현재 이후여야 합니다")
            if (sendAt.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) throw InvalidInputException("INVALID_SCHEDULE", "예약은 30일 이내로만 가능합니다")
            return sendAt
        }
    }
}

/**
 * 발송 시점 수신자 스냅샷 (스펙 7-5). 학생 × 연결된 보호자 계정 한 행.
 * 연결된 보호자가 없는 학생은 guardianUserId = null 한 행 (대상 수에는 포함, 열람 불가 → 알림톡 안내).
 */
data class NoticeRecipient(
    val noticeId: NoticeId,
    val studentId: StudentId,
    val guardianUserId: UserId?,
    val deliveredAt: Instant? = null,
    val readAt: Instant? = null,
    val resentCount: Int = 0,
)

/** 열람률은 학생 기준: 보호자 중 한 명이라도 읽으면 열람 */
data class NoticeReadSummary(val targetStudents: Int, val readStudents: Int) {
    val unreadStudents: Int get() = targetStudents - readStudents
    /** 0.0 ~ 1.0, 대상이 없으면 null */
    val rate: Double? get() = if (targetStudents == 0) null else readStudents.toDouble() / targetStudents

    companion object {
        fun of(recipients: List<NoticeRecipient>): NoticeReadSummary {
            val byStudent = recipients.groupBy { it.studentId }
            return NoticeReadSummary(byStudent.size, byStudent.values.count { rows -> rows.any { it.readAt != null } })
        }

        /** 미열람 재발송 대상: 아무 보호자도 읽지 않은 학생의 연결된 보호자 계정 */
        fun unreadGuardians(recipients: List<NoticeRecipient>): Map<StudentId, List<UserId>> =
            recipients.groupBy { it.studentId }
                .filterValues { rows -> rows.none { it.readAt != null } }
                .mapValues { (_, rows) -> rows.mapNotNull { it.guardianUserId }.distinct() }
    }
}

/** 알림장 발송·재발송 → Outbox → 워커가 학부모 기기로 푸시 */
data class NoticePublished(
    override val institutionId: InstitutionId,
    val institutionName: String,
    val noticeId: NoticeId,
    val kind: NoticeKind,
    val title: String,
    val recipientUserIds: List<UserId>,
    val isResend: Boolean,
    override val occurredAt: Instant,
) : DomainEvent {
    fun pushTitle() = "[$institutionName] " + when (kind) {
        NoticeKind.NOTE -> "알림장"
        NoticeKind.ANNOUNCEMENT -> "공지"
    }

    fun pushBody() = if (isResend) "아직 확인하지 않은 소식이 있어요: $title" else title
}
