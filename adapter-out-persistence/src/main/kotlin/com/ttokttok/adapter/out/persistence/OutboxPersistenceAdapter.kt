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
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.messaging.GuardianMessageRequested
import com.ttokttok.domain.messaging.MessageTemplate
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeId
import com.ttokttok.domain.notice.NoticeKind
import com.ttokttok.domain.notice.NoticePublished
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
    private val crypto: FieldCrypto,
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
            is GuardianMessageRequested -> TYPE_GUARDIAN_MESSAGE to GuardianMessageV1(
                institutionId = event.institutionId.value, phone = event.phone.digits, template = event.template.name,
                variables = event.variables, occurredAt = event.occurredAt,
            )
            is NoticePublished -> TYPE_NOTICE_PUBLISHED to NoticePublishedV1(
                institutionId = event.institutionId.value, institutionName = event.institutionName,
                noticeId = event.noticeId.value, kind = event.kind.name, title = event.title,
                recipientUserIds = event.recipientUserIds.map { it.value }, isResend = event.isResend, occurredAt = event.occurredAt,
            )
            else -> error("Outbox 직렬화 미지원 이벤트: ${event::class.simpleName}")
        }
        // 이름·연락처가 담기므로 페이로드는 암호화해 {"enc": "..."} 로 저장
        val sealed = json.writeValueAsString(mapOf("enc" to crypto.encrypt(json.writeValueAsString(payload))))
        repo.save(OutboxEntity(Uuid7.next(), event.institutionId.value, type, sealed, nextAttemptAt = clock.now()))
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

    private fun decode(type: String, stored: String): DomainEvent {
        val payload = crypto.decrypt(json.readTree(stored).get("enc").asText())
        return decodePlain(type, payload)
    }

    private fun decodePlain(type: String, payload: String): DomainEvent = when (type) {
        TYPE_GUARDIAN_MESSAGE -> json.readValue<GuardianMessageV1>(payload).let {
            GuardianMessageRequested(InstitutionId(it.institutionId), PhoneNumber.of(it.phone), MessageTemplate.valueOf(it.template), it.variables, it.occurredAt)
        }
        TYPE_ATTENDANCE_CHANGED -> json.readValue<AttendanceChangedV1>(payload).let {
            AttendanceChanged(
                InstitutionId(it.institutionId), it.institutionName, StudentId(it.studentId), it.studentName,
                AttendanceEventType.valueOf(it.type), AttendanceStatus.valueOf(it.toStatus), it.isLate, it.destinationName, it.occurredAt,
            )
        }
        TYPE_NOTICE_PUBLISHED -> json.readValue<NoticePublishedV1>(payload).let {
            NoticePublished(
                InstitutionId(it.institutionId), it.institutionName, NoticeId(it.noticeId), NoticeKind.valueOf(it.kind), it.title,
                it.recipientUserIds.map(::UserId), it.isResend, it.occurredAt,
            )
        }
        else -> error("알 수 없는 outbox 이벤트 타입: $type")
    }

    data class AttendanceChangedV1(
        val institutionId: UUID, val institutionName: String, val studentId: UUID, val studentName: String,
        val type: String, val toStatus: String, val isLate: Boolean, val destinationName: String?, val occurredAt: Instant,
    )

    data class GuardianMessageV1(
        val institutionId: UUID, val phone: String, val template: String, val variables: Map<String, String>, val occurredAt: Instant,
    )

    data class NoticePublishedV1(
        val institutionId: UUID, val institutionName: String, val noticeId: UUID, val kind: String, val title: String,
        val recipientUserIds: List<UUID>, val isResend: Boolean, val occurredAt: Instant,
    )

    companion object {
        const val TYPE_NOTICE_PUBLISHED = "notice.published.v1"
        const val TYPE_GUARDIAN_MESSAGE = "guardian.message.v1"
        const val TYPE_ATTENDANCE_CHANGED = "attendance.changed.v1"
    }
}
