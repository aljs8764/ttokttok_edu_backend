package com.ttokttok.domain.user

import com.ttokttok.domain.common.UserId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LoginAttemptTest {
    private val now = Instant.parse("2026-10-02T01:00:00Z")

    @Test
    fun `5회 실패하면 10분 잠기고 풀린 뒤에는 다시 센다`() {
        var a = LoginAttempt(UserId.new())
        repeat(4) { a = a.recordFailure(now) }
        a.isLocked(now) shouldBe false
        a.remainingAttempts() shouldBe 1
        a = a.recordFailure(now)
        a.isLocked(now.plus(Duration.ofMinutes(9))) shouldBe true
        a.isLocked(now.plus(Duration.ofMinutes(10))) shouldBe false
        a = a.recordFailure(now.plus(Duration.ofMinutes(11)))
        a.failedCount shouldBe 1
        a.isLocked(now.plus(Duration.ofMinutes(11))) shouldBe false
    }
}
