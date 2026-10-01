package com.ttokttok.adapter.`in`.scheduler

import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
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
