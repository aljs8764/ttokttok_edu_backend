package com.ttokttok.application.port.`in`

import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.Platform
import java.time.Instant

/**
 * 자녀 목록 — 기관을 가로질러 (PAR-006). 아이 단위로 묶고, 아이마다 다니는 기관의 원생 목록 (스펙 7-8).
 * 다자녀 = 아이 여러 명, 한 아이가 여러 학원·학교 = enrollments 여러 개.
 */
interface GetMyChildrenQuery {
    fun children(parent: UserId): List<ChildView>

    /**
     * 학부모 목록 API 의 `childId` 필터 → 원생 id 집합.
     * 아이 id 면 그 아이의 모든 원생, 원생 id 면 그 원생 하나 (이전 앱 호환). null 이면 null (= 전체).
     * 본인 자녀가 아니면 403.
     */
    fun resolveFilter(parent: UserId, id: java.util.UUID?): Set<StudentId>?

    data class ChildView(val childId: ChildId, val name: String, val enrollments: List<EnrollmentView>)
    data class EnrollmentView(
        val studentId: StudentId, val studentName: String,
        val institutionId: InstitutionId, val institutionName: String, val institutionType: String,
        val status: String,
    )
}

/** 스펙 7-8 보호자: 같은 아이 합치기·나누기, 이름 */
interface ManageMyChildUseCase {
    /** 같은 보호자의 아이 중 이름·생일이 같은 쌍 (합치자고 물어볼 후보) */
    fun mergeSuggestions(parent: UserId): List<MergeSuggestion>
    /** source 아이의 원생·보호자·학생앱 기기를 target 으로 옮기고 source 를 지운다 */
    fun merge(parent: UserId, target: ChildId, source: ChildId): GetMyChildrenQuery.ChildView
    /** 잘못 합친 원생 하나를 새 아이로 떼어낸다. 학생앱 기기는 원래 아이에 남는다 */
    fun split(parent: UserId, childId: ChildId, studentId: StudentId): GetMyChildrenQuery.ChildView
    fun rename(parent: UserId, childId: ChildId, name: String): GetMyChildrenQuery.ChildView

    data class MergeSuggestion(val childIds: List<ChildId>, val name: String, val institutionNames: List<String>)
}

/** 통합 안심 타임라인 (PAR-001). 여러 기관 로그를 시간순 하나로 */
interface GetTimelineQuery {
    /** filter: 필터 (GetMyChildrenQuery.resolveFilter), null = 전체 */
    fun timeline(parent: UserId, filter: Set<StudentId>?, before: Instant?, limit: Int): List<TimelineItem>
    data class TimelineItem(
        val studentId: StudentId, val studentName: String,
        /** 같은 아이의 여러 기관 기록을 앱이 묶는다 (스펙 7-8) */
        val childId: ChildId?,
        val institutionId: InstitutionId, val institutionName: String,
        val type: AttendanceEventType, val status: AttendanceStatus, val isLate: Boolean,
        val destinationName: String?, val occurredAt: Instant,
    )
}

/** FCM 토큰 등록 (PAR-002) */
interface RegisterDeviceUseCase {
    fun register(user: UserId, flavor: AppFlavor, platform: Platform, token: String)
    fun unregister(user: UserId, token: String)
}

/** 워커: Outbox 처리 → 푸시 발송 */
interface ProcessOutboxUseCase {
    fun processBatch(limit: Int = 50): Int
}
