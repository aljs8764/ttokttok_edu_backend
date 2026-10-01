package com.ttokttok.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ttokttok.adapter.out.persistence.entity.OutboxEntity
import com.ttokttok.adapter.out.persistence.repository.OutboxJpaRepository
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.OutboxMessage
import com.ttokttok.application.port.out.OutboxPort
import com.ttokttok.domain.attendance.AttendanceChanged
import com.ttokttok.domain.attendance.AttendanceEventType
import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.Uuid7
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Transactional Outbox. 도메인 이벤트를 명시적 페이로드(원시 타입만)로 직렬화한다 —
 * 도메인 클래스 구조가 바뀌어도 이미 쌓인 메시지를 읽을 수 있도록 스키마를 분리.
 */
@Component
class OutboxPersistenceAdapter(
    private val repo: OutboxJpaRepository,
    private val clock: ClockPort,
) : OutboxPort {
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    override fun publish(event: DomainEvent) {
        val (type, payload) = when (event) {
            is AttendanceChanged -> TYPE_ATTENDANCE_CHANGED to AttendanceChangedV1(
                institutionId = event.institutionId.value, institutionName = event.institutionName,
                studentId = event.studentId.value, studentName = event.studentName,
                type = event.type.name, toStatus = event.toStatus.name, isLate = event.isLate,
                destinationName = event.destinationName, occurredAt = event.occurredAt,
            )
            else -> error("Outbox 직렬화 미지원 이벤트: ${event::class.simpleName}")
        }
        repo.save(OutboxEntity(Uuid7.next(), event.institutionId.value, type, json.writeValueAsString(payload), nextAttemptAt = clock.now()))
    }

    override fun lockPending(limit: Int, now: Instant): List<OutboxMessage> =
        repo.lockPending(now, limit).map { OutboxMessage(it.id, it.attempts, decode(it.eventType, it.payload)) }

    override fun markDone(id: UUID, at: Instant) {
        repo.findByIdOrNull(id)?.apply { status = "DONE"; processedAt = at }
    }

    override fun markFailed(id: UUID, error: String, nextAttemptAt: Instant?, at: Instant) {
        repo.findByIdOrNull(id)?.apply {
            attempts += 1
            lastError = error.take(2000)
            if (nextAttemptAt == null) { status = "DEAD"; processedAt = at } else this.nextAttemptAt = nextAttemptAt
        }
    }

    private fun decode(type: String, payload: String): DomainEvent = when (type) {
        TYPE_ATTENDANCE_CHANGED -> json.readValue<AttendanceChangedV1>(payload).let {
            AttendanceChanged(
                InstitutionId(it.institutionId), it.institutionName, StudentId(it.studentId), it.studentName,
                AttendanceEventType.valueOf(it.type), AttendanceStatus.valueOf(it.toStatus), it.isLate, it.destinationName, it.occurredAt,
            )
        }
        else -> error("알 수 없는 outbox 이벤트 타입: $type")
    }

    data class AttendanceChangedV1(
        val institutionId: UUID, val institutionName: String, val studentId: UUID, val studentName: String,
        val type: String, val toStatus: String, val isLate: Boolean, val destinationName: String?, val occurredAt: Instant,
    )

    companion object {
        const val TYPE_ATTENDANCE_CHANGED = "attendance.changed.v1"
    }
}
