package com.ttokttok.application.port.out

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.event.RsvpResponse
import com.ttokttok.domain.event.SchoolEvent
import com.ttokttok.domain.event.SchoolEventId
import java.time.Instant

interface SchoolEventPort {
    fun save(event: SchoolEvent): SchoolEvent
    fun find(id: SchoolEventId, institutionId: InstitutionId): SchoolEvent?
    fun findForUpdate(id: SchoolEventId, institutionId: InstitutionId): SchoolEvent?
    fun findAllByIds(ids: Collection<SchoolEventId>): List<SchoolEvent>
    /** 관리자 목록: 시작 시각 최신순. authorId 가 있으면 작성자 본인 것만, from 이 있으면 그 이후 시작 */
    fun search(institutionId: InstitutionId, authorId: UserId?, startsFrom: Instant?, page: Int, size: Int): PageResult<SchoolEvent>
    /** 5분 스케줄러: RSVP 진행 중·미독촉 건 (FOR UPDATE SKIP LOCKED). 독촉 시점 판정은 도메인이 한다 */
    fun lockReminderCandidates(now: Instant, limit: Int): List<SchoolEvent>
}

/** 생성 시점 대상 학생 스냅샷 — 미응답 = 대상 − 응답 */
interface EventTargetPort {
    fun saveTargets(eventId: SchoolEventId, studentIds: Collection<StudentId>)
    fun findTargetStudents(eventId: SchoolEventId): List<StudentId>
    /** 학부모 RSVP함: 자녀가 대상인 행사. startsFrom 이후 시작, 시작 시각 오름차순 */
    fun findEventIdsForStudents(studentIds: Collection<StudentId>, startsFrom: Instant?, limit: Int): List<SchoolEventId>
}

interface RsvpResponsePort {
    /** (행사, 학생) 당 한 건 — 다시 응답하면 덮어쓴다 */
    fun upsert(response: RsvpResponse)
    fun findByEvent(eventId: SchoolEventId): List<RsvpResponse>
    fun findByEvents(eventIds: Collection<SchoolEventId>): List<RsvpResponse>
}
