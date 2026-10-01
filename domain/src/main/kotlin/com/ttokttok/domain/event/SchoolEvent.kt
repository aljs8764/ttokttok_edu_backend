package com.ttokttok.domain.event

import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.notice.NoticeTarget
import com.ttokttok.domain.notice.TargetScope
import java.time.Duration
import java.time.Instant
import java.util.UUID

@JvmInline value class SchoolEventId(val value: UUID) { companion object { fun new() = SchoolEventId(Uuid7.next()) } }

enum class SchoolEventStatus { ACTIVE, CANCELED }
enum class RsvpAnswer { ATTEND, ABSENT }

/**
 * 행사 (EVT-001). 도메인 이벤트(DomainEvent)와 이름이 겹치지 않도록 SchoolEvent 로 부른다.
 * 대상 지정 규칙은 알림장과 같다(NoticeTarget 재사용: 전체/반/학생).
 * RSVP 를 켜면 대상 학생 단위로 참석/불참을 받는다 (스펙 7-6).
 */
data class SchoolEvent(
    val id: SchoolEventId,
    val institutionId: InstitutionId,
    val authorId: UserId,
    val title: String,
    val body: String?,
    val location: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val targets: List<NoticeTarget>,
    val rsvpEnabled: Boolean,
    val rsvpDeadline: Instant?,
    /** 마감 몇 시간 전에 자동 독촉할지 (기본 24h, 스펙 5장 스케줄 작업) */
    val reminderHoursBefore: Int = DEFAULT_REMINDER_HOURS,
    val remindedAt: Instant? = null,
    val status: SchoolEventStatus = SchoolEventStatus.ACTIVE,
    val createdAt: Instant,
) {
    init {
        if (title.isBlank() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "행사명은 1~100자입니다")
        if ((body?.length ?: 0) > 5000) throw InvalidInputException("INVALID_BODY", "내용은 5000자 이내입니다")
        if ((location?.length ?: 0) > 200) throw InvalidInputException("INVALID_LOCATION", "장소는 200자 이내입니다")
        if (targets.isEmpty()) throw InvalidInputException("TARGET_REQUIRED", "대상을 하나 이상 지정하세요")
        if (TargetScope.ALL in targets.map { it.scope } && targets.size > 1)
            throw InvalidInputException("INVALID_TARGET", "전체 대상은 다른 대상과 함께 지정할 수 없습니다")
        if (endsAt != null && !endsAt.isAfter(startsAt)) throw InvalidInputException("INVALID_TIME", "종료 시각은 시작 이후여야 합니다")
        if (rsvpEnabled && rsvpDeadline == null) throw InvalidInputException("DEADLINE_REQUIRED", "참석 여부를 받으려면 응답 마감 시각이 필요합니다")
        if (rsvpDeadline != null && rsvpDeadline.isAfter(startsAt)) throw InvalidInputException("INVALID_DEADLINE", "응답 마감은 행사 시작 전이어야 합니다")
        if (reminderHoursBefore !in 1..168) throw InvalidInputException("INVALID_REMINDER", "독촉 시점은 마감 1~168시간 전입니다")
    }

    fun isOpenForResponse(now: Instant) =
        status == SchoolEventStatus.ACTIVE && rsvpEnabled && rsvpDeadline != null && now.isBefore(rsvpDeadline)

    /** 학부모 응답 가능 여부 — 마감 후에는 403 (스펙 5장) */
    fun requireOpen(now: Instant) {
        if (status == SchoolEventStatus.CANCELED) throw ConflictException("EVENT_CANCELED", "취소된 행사입니다")
        if (!rsvpEnabled) throw ConflictException("RSVP_DISABLED", "참석 여부를 받지 않는 행사입니다")
        if (!isOpenForResponse(now)) throw ForbiddenException("응답이 마감되었습니다")
    }

    /** 자동 독촉 대상: 마감 N시간 전 ~ 마감 사이, 아직 독촉 안 함 */
    fun isReminderDue(now: Instant): Boolean {
        val deadline = rsvpDeadline ?: return false
        return isOpenForResponse(now) && remindedAt == null &&
            !now.isBefore(deadline.minus(Duration.ofHours(reminderHoursBefore.toLong())))
    }

    /** 수동 독촉 (EVT-004): 30분 쿨타임 */
    fun remind(now: Instant): SchoolEvent {
        if (!isOpenForResponse(now)) throw ConflictException("RSVP_CLOSED", "응답을 받는 중인 행사만 독촉할 수 있습니다")
        val last = remindedAt
        if (last != null && now.isBefore(last.plus(REMIND_COOLDOWN)))
            throw ConflictException("REMIND_COOLDOWN", "독촉은 30분에 한 번만 보낼 수 있습니다")
        return copy(remindedAt = now)
    }

    fun edit(
        title: String, body: String?, location: String?, startsAt: Instant, endsAt: Instant?,
        rsvpDeadline: Instant?, reminderHoursBefore: Int,
    ): SchoolEvent {
        if (status == SchoolEventStatus.CANCELED) throw ConflictException("EVENT_CANCELED", "취소된 행사는 수정할 수 없습니다")
        // 대상·RSVP 여부는 생성 후 바꾸지 않는다 (응답 스냅샷과 어긋남). 마감을 늘리면 독촉을 다시 할 수 있게 초기화
        return copy(
            title = title.trim(), body = body?.trim()?.ifEmpty { null }, location = location?.trim()?.ifEmpty { null },
            startsAt = startsAt, endsAt = endsAt, rsvpDeadline = if (rsvpEnabled) rsvpDeadline else null,
            reminderHoursBefore = reminderHoursBefore,
            remindedAt = if (rsvpDeadline != this.rsvpDeadline) null else remindedAt,
        )
    }

    fun cancel(): SchoolEvent {
        if (status == SchoolEventStatus.CANCELED) throw ConflictException("EVENT_CANCELED", "이미 취소된 행사입니다")
        return copy(status = SchoolEventStatus.CANCELED)
    }

    companion object {
        const val DEFAULT_REMINDER_HOURS = 24
        val REMIND_COOLDOWN: Duration = Duration.ofMinutes(30)

        fun create(
            institutionId: InstitutionId, author: UserId, title: String, body: String?, location: String?,
            startsAt: Instant, endsAt: Instant?, targets: List<NoticeTarget>, rsvpEnabled: Boolean,
            rsvpDeadline: Instant?, reminderHoursBefore: Int?, now: Instant,
        ): SchoolEvent {
            if (!startsAt.isAfter(now)) throw InvalidInputException("INVALID_TIME", "행사 시작은 현재 이후여야 합니다")
            if (rsvpDeadline != null && !rsvpDeadline.isAfter(now)) throw InvalidInputException("INVALID_DEADLINE", "응답 마감은 현재 이후여야 합니다")
            return SchoolEvent(
                id = SchoolEventId.new(), institutionId = institutionId, authorId = author,
                title = title.trim(), body = body?.trim()?.ifEmpty { null }, location = location?.trim()?.ifEmpty { null },
                startsAt = startsAt, endsAt = endsAt, targets = targets.distinct(), rsvpEnabled = rsvpEnabled,
                rsvpDeadline = if (rsvpEnabled) rsvpDeadline else null,
                reminderHoursBefore = reminderHoursBefore ?: DEFAULT_REMINDER_HOURS, createdAt = now,
            )
        }
    }
}

/** 학생 단위 응답. 다자녀면 자녀별로 따로 (스펙 7-6). 마감 전까지 변경 가능 */
data class RsvpResponse(
    val eventId: SchoolEventId,
    val studentId: StudentId,
    val answer: RsvpAnswer,
    val reason: String?,
    val respondedBy: UserId,
    val respondedAt: Instant,
) {
    init {
        if ((reason?.length ?: 0) > 200) throw InvalidInputException("REASON_TOO_LONG", "사유는 200자 이내입니다")
    }
}

/** 집계 = 참석 / 불참 / 미응답(대상 − 응답) */
data class RsvpTally(val targets: Int, val attend: Int, val absent: Int) {
    val pending: Int get() = targets - attend - absent

    companion object {
        fun of(targetStudents: Collection<StudentId>, responses: Collection<RsvpResponse>): RsvpTally {
            val inTarget = responses.filter { it.studentId in targetStudents.toSet() }
            return RsvpTally(
                targetStudents.toSet().size,
                inTarget.count { it.answer == RsvpAnswer.ATTEND },
                inTarget.count { it.answer == RsvpAnswer.ABSENT },
            )
        }
    }
}
