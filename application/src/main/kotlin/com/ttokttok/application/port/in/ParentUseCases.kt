package com.ttokttok.application.port.`in`

import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.Platform
import java.time.Instant

/** 자녀 목록 — 기관을 가로질러 (PAR-006) */
interface GetMyChildrenQuery {
    fun children(parent: UserId): List<ChildView>
    data class ChildView(val studentId: StudentId, val name: String, val institutionId: InstitutionId, val institutionName: String)
}

/** 통합 안심 타임라인 (PAR-001). 여러 기관 로그를 시간순 하나로 */
interface GetTimelineQuery {
    fun timeline(parent: UserId, studentId: StudentId?, before: Instant?, limit: Int): List<TimelineItem>
    data class TimelineItem(
        val studentId: StudentId, val studentName: String,
        val institutionId: InstitutionId, val institutionName: String,
        val type: AttendanceEventType, val status: AttendanceStatus, val isLate: Boolean,
        val destinationName: String?, val occurredAt: Instant,
    )
}

/** FCM 토큰 등록 (PAR-002) */
interface RegisterDeviceUseCase {
    fun register(user: UserId, flavor: AppFlavor, platform: Platform, token: String)
}

/** 워커: Outbox 처리 → 푸시 발송 */
interface ProcessOutboxUseCase {
    fun processBatch(limit: Int = 50): Int
}
