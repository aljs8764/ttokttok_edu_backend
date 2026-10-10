package com.ttokttok.adapter.`in`.scheduler

import com.ttokttok.application.port.`in`.CleanupStaleFilesUseCase
import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import com.ttokttok.application.port.`in`.InvoiceReminderUseCase
import com.ttokttok.application.port.`in`.MissingAlertUseCase
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
import com.ttokttok.application.port.`in`.PlatformBillingUseCase
import com.ttokttok.application.port.`in`.PublishDueNoticesUseCase
import com.ttokttok.application.port.`in`.ShuttleTripUseCase
import com.ttokttok.application.port.`in`.UsageAnomalyUseCase
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

/** 미납 자동 알림 (PAY-003). 매일 09:30 KST, 단계(기한 임박·당일·경과)당 청구서 1회 */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class InvoiceReminderScheduler(private val remind: InvoiceReminderUseCase) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "invoice.remind")
    fun run() {
        var total = 0
        while (true) {
            val n = remind.remindDue(100)
            total += n
            if (n < 100) break
        }
        log.info("납부 리마인드 {}건 발송", total)
    }
}

/** 기간 종료 시 해지로 예약된 구독을 다음 결제일이 지나면 해지 처리 (SAAS-002). 매일 00:10 KST */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class SubscriptionCancelScheduler(private val billing: PlatformBillingUseCase, private val clock: ClockPort) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "subscription.cancelDue")
    fun run() {
        log.info("구독 해지 처리 {}건", billing.processCancellations(clock.today()))
    }
}

/** 셔틀 운행 정리 (ATT-009): 3시간 넘게 위치가 없는 운행 자동 종료(10분마다), 30일 지난 좌표 삭제(매일 04:10 KST) */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class ShuttleHousekeepingScheduler(private val trips: ShuttleTripUseCase, private val clock: ClockPort) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "shuttle.closeAbandoned")
    fun closeAbandoned() {
        val n = trips.closeAbandoned(clock.now())
        if (n > 0) log.info("응답 없는 셔틀 운행 {}건 자동 종료", n)
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "shuttle.purgePositions")
    fun purge() {
        log.info("셔틀 좌표 {}건 삭제(30일 경과)", trips.purgePositions(clock.now().minus(java.time.Duration.ofDays(30))))
    }
}

/** ATT-010 미등원 초과 경고: 5분마다 (수업 시작 + 기관 기준 분) 을 넘긴 미등원 원생을 찾아 알린다 */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class MissingAlertScheduler(private val missing: MissingAlertUseCase, private val clock: ClockPort) {
    @Scheduled(cron = "30 */5 * * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "attendance.missingAlert", lockAtMostFor = "PT4M")
    fun scan() {
        missing.scan(clock.now())
    }
}

/** SAAS-006 사용량 이상 탐지: 매시 5분에 지난 1시간 판정, 매일 04:20 90일 지난 사용량 삭제 */
@Component
@ConditionalOnProperty(name = ["ttok.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class UsageAnomalyScheduler(private val anomaly: UsageAnomalyUseCase, private val clock: ClockPort) {
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "usage.detect")
    fun detect() {
        anomaly.detect(clock.now())
    }

    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "usage.purge")
    fun purge() {
        anomaly.purge(clock.now())
    }
}
