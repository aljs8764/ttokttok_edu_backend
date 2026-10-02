package com.ttokttok.adapter.`in`.scheduler

import com.ttokttok.application.port.`in`.CleanupStaleFilesUseCase
import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
import com.ttokttok.application.port.`in`.PublishDueNoticesUseCase
import com.ttokttok.application.port.`in`.RemindEventUseCase
import com.ttokttok.application.port.out.ClockPort
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulerConfig

/** Outbox 폴링. SKIP LOCKED라 워커 여러 대가 동시에 돌아도 안전하므로 ShedLock 불필요. */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPoller(private val processOutbox: ProcessOutboxUseCase) {
    @Scheduled(fixedDelayString = "\${ttok.outbox.poll-interval-ms:500}")
    fun poll() {
        // 한 번에 다 못 비우면 바로 다음 배치
        while (processOutbox.processBatch(50) == 50) Unit
    }
}

/** 일 단위 출결 배치. 다중 인스턴스에서 한 번만 실행되도록 ShedLock. */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class DailyAttendanceScheduler(
    private val batch: DailyAttendanceBatchUseCase,
    private val clock: ClockPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "attendance.generateScheduled")
    fun generate() {
        val n = batch.generateScheduled(clock.today())
        log.info("등원 예정 {}건 생성", n)
    }

    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "attendance.closeUnprocessed")
    fun close() {
        val n = batch.closeUnprocessed(clock.today())
        log.info("미처리 {}건 결석 확정", n)
    }
}

/**
 * 예약 알림장 발송 (NTC-002). 매분, 발송 시각이 지난 SCHEDULED 건을 SKIP LOCKED 로 잡아 발송한다.
 * 워커가 여러 대여도 같은 건을 두 번 잡지 않으므로 ShedLock 불필요.
 */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class NoticeScheduler(private val publishDue: PublishDueNoticesUseCase) {
    @Scheduled(fixedDelayString = "\${ttok.notice.poll-interval-ms:60000}")
    fun publish() {
        // 한 트랜잭션에 10건씩 — 실패가 다른 건 발송을 오래 막지 않도록
        while (publishDue.publishDue(10) == 10) Unit
    }
}

/** 행사 RSVP 마감 독촉 (EVT-004). 5분마다, 마감 N시간 전에 들어온 행사를 1회 자동 독촉 */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class EventReminderScheduler(private val remind: RemindEventUseCase) {
    @Scheduled(fixedDelayString = "\${ttok.event.reminder-interval-ms:300000}")
    fun remindDue() {
        while (remind.remindDue(20) == 20) Unit
    }
}

/** 업로드만 요청하고 확정하지 않은 파일 정리 (매일 03:30, 24시간 경과분) */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class StaleFileCleanupScheduler(private val cleanup: CleanupStaleFilesUseCase) {
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "files.cleanupStale")
    fun run() {
        while (cleanup.cleanup(200) == 200) Unit
    }
}
