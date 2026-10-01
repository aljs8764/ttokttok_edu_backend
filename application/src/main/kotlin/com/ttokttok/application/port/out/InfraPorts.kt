package com.ttokttok.application.port.out

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 시간은 반드시 이 포트로만 얻는다 (테스트에서 고정 시각 주입). */
interface ClockPort {
    fun now(): Instant
    fun zone(): ZoneId = ZoneId.of("Asia/Seoul")
    fun today(): LocalDate = LocalDate.ofInstant(now(), zone())
}

interface PasswordHasherPort {
    fun hash(raw: String): String
    fun matches(raw: String, hash: String): Boolean
}

/** Transactional Outbox. 유스케이스와 같은 트랜잭션에서 저장된다. */
interface OutboxPort {
    fun publish(event: DomainEvent)
    /** FOR UPDATE SKIP LOCKED — 워커 여러 대가 같은 메시지를 잡지 않는다 */
    fun lockPending(limit: Int, now: Instant): List<OutboxMessage>
    fun markDone(id: UUID, at: Instant)
    fun markFailed(id: UUID, error: String, nextAttemptAt: Instant?, at: Instant)
}

data class OutboxMessage(val id: UUID, val attempts: Int, val event: DomainEvent)

data class PushMessage(
    val tokens: List<String>,
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

data class PushResult(val successCount: Int, val invalidTokens: List<String>)

interface SendPushPort {
    fun send(message: PushMessage): PushResult
}

enum class NotificationChannel { PUSH, ALIMTALK, SMS, EMAIL }
enum class NotificationStatus { SENT, PARTIAL, FAILED, SKIPPED }

interface NotificationLogPort {
    fun record(
        institutionId: InstitutionId, channel: NotificationChannel, templateCode: String,
        recipientCount: Int, status: NotificationStatus, error: String?, at: Instant,
    )
}

/** 실시간 브로드캐스트. 구현체는 트랜잭션 커밋 이후에 전송해야 한다. */
interface RealtimePort {
    fun attendanceUpdated(institutionId: InstitutionId, classroomId: ClassroomId, payload: Map<String, Any?>)
    /** 기관 토픽(/topic/inst.{id})으로만 보내는 이벤트 — notice.read 등. 워커처럼 소켓이 없는 곳은 무시 */
    fun institutionEvent(institutionId: InstitutionId, payload: Map<String, Any?>) = Unit
}
