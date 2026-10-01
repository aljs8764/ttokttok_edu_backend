package com.ttokttok.application.port.`in`

import com.ttokttok.application.port.out.PageResult
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.event.RsvpAnswer
import com.ttokttok.domain.event.SchoolEventId
import com.ttokttok.domain.event.SchoolEventStatus
import com.ttokttok.domain.notice.NoticeTarget
import java.time.Instant

/** EVT-001 행사 생성·수정·취소 */
interface ManageEventUseCase {
    fun create(command: CreateCommand): EventView
    fun update(actor: UserId, institutionId: InstitutionId, id: SchoolEventId, command: UpdateCommand): EventView
    fun cancel(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): EventView

    data class CreateCommand(
        val actor: UserId,
        val institutionId: InstitutionId,
        val title: String,
        val body: String?,
        val location: String?,
        val startsAt: Instant,
        val endsAt: Instant?,
        val targets: List<NoticeTarget>,
        val rsvpEnabled: Boolean,
        val rsvpDeadline: Instant?,
        val reminderHoursBefore: Int?,
    )

    data class UpdateCommand(
        val title: String,
        val body: String?,
        val location: String?,
        val startsAt: Instant,
        val endsAt: Instant?,
        val rsvpDeadline: Instant?,
        val reminderHoursBefore: Int,
    )
}

/** 행사 목록 · EVT-003 집계 대시보드 + 명단 엑셀 */
interface EventQuery {
    fun list(actor: UserId, institutionId: InstitutionId, upcomingOnly: Boolean, page: Int, size: Int): PageResult<EventView>
    fun summary(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): EventSummary
    fun exportResponses(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): ExportedFile
}

/** EVT-004 미응답자 독촉 — 수동(30분 쿨타임) / 자동(5분 스케줄러, 마감 N시간 전 1회) */
interface RemindEventUseCase {
    fun remind(actor: UserId, institutionId: InstitutionId, id: SchoolEventId): Int
    fun remindDue(limit: Int): Int
}

/** PAR-005 RSVP함 · EVT-005 간편 응답 */
interface ParentEventUseCase {
    fun list(parent: UserId, childId: StudentId?, includePast: Boolean): List<ParentEventItem>
    fun respond(parent: UserId, id: SchoolEventId, studentId: StudentId, answer: RsvpAnswer, reason: String?): ParentEventItem
}

data class EventView(
    val id: SchoolEventId,
    val title: String,
    val body: String?,
    val location: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val targets: List<TargetView>,
    val rsvpEnabled: Boolean,
    val rsvpDeadline: Instant?,
    val reminderHoursBefore: Int,
    val remindedAt: Instant?,
    val status: SchoolEventStatus,
    val authorName: String,
    val createdAt: Instant,
    val tally: Tally?,
)

data class Tally(val targets: Int, val attend: Int, val absent: Int, val pending: Int)

data class EventSummary(val event: EventView, val tally: Tally, val rows: List<RsvpRow>)

data class RsvpRow(
    val studentId: StudentId,
    val studentName: String,
    val classroomNames: List<String>,
    val answer: RsvpAnswer?,
    val reason: String?,
    val respondedAt: Instant?,
    val respondedByName: String?,
)

data class ParentEventItem(
    val id: SchoolEventId,
    val institutionId: InstitutionId,
    val institutionName: String,
    val title: String,
    val body: String?,
    val location: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val status: SchoolEventStatus,
    val rsvpEnabled: Boolean,
    val rsvpDeadline: Instant?,
    val open: Boolean,
    /** 대상 자녀별 내 응답 상태 */
    val children: List<ChildRsvp>,
)

data class ChildRsvp(val studentId: StudentId, val name: String, val answer: RsvpAnswer?, val reason: String?, val respondedAt: Instant?)
