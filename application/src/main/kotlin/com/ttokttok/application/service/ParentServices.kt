package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.GetTimelineQuery
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
import com.ttokttok.application.port.`in`.RegisterDeviceUseCase
import com.ttokttok.application.port.out.AttendancePort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DestinationPort
import com.ttokttok.application.port.out.DeviceTokenPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.NotificationChannel
import com.ttokttok.application.port.out.NotificationLogPort
import com.ttokttok.application.port.out.NotificationStatus
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.application.port.out.PushMessage
import com.ttokttok.application.port.out.SendPushPort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.attendance.AttendanceChanged
import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.device.AppFlavor
import com.ttokttok.domain.device.DeviceToken
import com.ttokttok.domain.device.Platform
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class GetMyChildrenService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
) : GetMyChildrenQuery {
    @Transactional(readOnly = true)
    override fun children(parent: UserId): List<GetMyChildrenQuery.ChildView> {
        val links = guardians.findLinkedByUser(parent)
        val ss = students.findAllByIds(links.map { it.studentId }.distinct())
        val names = institutions.findAllByIds(ss.map { it.institutionId }.distinct()).associate { it.id to it.name }
        return ss.sortedBy { it.name }.map { GetMyChildrenQuery.ChildView(it.id, it.name, it.institutionId, names[it.institutionId] ?: "") }
    }
}

@Service
class GetTimelineService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val attendance: AttendancePort,
    private val destinations: DestinationPort,
) : GetTimelineQuery {
    @Transactional(readOnly = true)
    override fun timeline(parent: UserId, studentId: StudentId?, before: Instant?, limit: Int): List<GetTimelineQuery.TimelineItem> {
        if (limit !in 1..100) throw InvalidInputException("INVALID_LIMIT", "limit은 1~100입니다")
        val mine = guardians.findLinkedByUser(parent).map { it.studentId }.toSet()
        val targets = if (studentId != null) {
            if (studentId !in mine) throw ForbiddenException("본인 자녀가 아닙니다")
            setOf(studentId)
        } else mine
        if (targets.isEmpty()) return emptyList()

        val events = attendance.findEvents(targets, before, limit)
        val ss = students.findAllByIds(targets).associateBy { it.id }
        val insts = institutions.findAllByIds(ss.values.map { it.institutionId }.distinct()).associateBy { it.id }
        val days = attendance.findDaysByIds(events.map { it.attendanceDayId }.distinct()).associateBy { it.id }
        val dests = destinations.findAllByIds(events.mapNotNull { it.destinationId }.distinct()).associateBy { it.id }

        return events.map { e ->
            val s = ss.getValue(e.studentId)
            GetTimelineQuery.TimelineItem(
                studentId = s.id, studentName = s.name,
                institutionId = s.institutionId, institutionName = insts[s.institutionId]?.name ?: "",
                type = e.type, status = e.toStatus, isLate = days[e.attendanceDayId]?.isLate ?: false,
                destinationName = e.destinationId?.let { dests[it]?.name }, occurredAt = e.occurredAt,
            )
        }
    }
}

@Service
class RegisterDeviceService(private val devices: DeviceTokenPort) : RegisterDeviceUseCase {
    @Transactional
    override fun register(user: UserId, flavor: AppFlavor, platform: Platform, token: String) {
        if (token.isBlank() || token.length > 4096) throw InvalidInputException("INVALID_TOKEN", "FCM 토큰이 올바르지 않습니다")
        devices.upsert(DeviceToken(user, flavor, platform, token))
    }
}

/**
 * Outbox 처리 (app-worker). 실패는 지수 백오프로 최대 [MAX_ATTEMPTS]회 재시도.
 * 한 배치를 한 트랜잭션으로 잠그므로(SKIP LOCKED) 워커를 여러 대 띄워도 중복 발송이 없다.
 */
@Service
class ProcessOutboxService(
    private val outbox: OutboxPort,
    private val guardians: GuardianPort,
    private val devices: DeviceTokenPort,
    private val push: SendPushPort,
    private val logs: NotificationLogPort,
    private val clock: ClockPort,
) : ProcessOutboxUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun processBatch(limit: Int): Int {
        val now = clock.now()
        val messages = outbox.lockPending(limit, now)
        messages.forEach { m ->
            try {
                handle(m.event)
                outbox.markDone(m.id, clock.now())
            } catch (e: Exception) {
                val attempt = m.attempts + 1
                val next = if (attempt >= MAX_ATTEMPTS) null else now.plus(Duration.ofSeconds(5L shl attempt))
                log.warn("outbox {} 처리 실패 (시도 {}): {}", m.id, attempt, e.message)
                outbox.markFailed(m.id, e.message ?: e.javaClass.simpleName, next, clock.now())
            }
        }
        return messages.size
    }

    private fun handle(event: DomainEvent) = when (event) {
        is AttendanceChanged -> sendAttendancePush(event)
        else -> error("처리기가 없는 이벤트: ${event::class.simpleName}")
    }

    private fun sendAttendancePush(e: AttendanceChanged) {
        val parentIds = guardians.findByStudent(e.studentId).mapNotNull { it.userId }.distinct()
        val tokens = devices.findByUsers(parentIds, AppFlavor.PARENT).map { it.token }.distinct()
        if (tokens.isEmpty()) {
            logs.record(e.institutionId, NotificationChannel.PUSH, TEMPLATE, 0, NotificationStatus.SKIPPED, "등록된 학부모 기기 없음", clock.now())
            return
        }
        val result = push.send(
            PushMessage(
                tokens = tokens, title = e.pushTitle(), body = e.pushBody(),
                data = mapOf(
                    "type" to "attendance", "studentId" to e.studentId.value.toString(),
                    "status" to e.toStatus.name, "occurredAt" to e.occurredAt.toString(),
                ),
            ),
        )
        if (result.invalidTokens.isNotEmpty()) devices.deleteTokens(result.invalidTokens)
        val status = when {
            result.successCount == tokens.size -> NotificationStatus.SENT
            result.successCount > 0 -> NotificationStatus.PARTIAL
            else -> NotificationStatus.FAILED
        }
        logs.record(e.institutionId, NotificationChannel.PUSH, TEMPLATE, tokens.size, status, null, clock.now())
        // 유효 토큰이 있었는데 전부 실패 → 재시도 대상
        if (status == NotificationStatus.FAILED && result.invalidTokens.size < tokens.size) error("푸시 발송 실패")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val TEMPLATE = "ATTENDANCE_CHANGED"
    }
}
