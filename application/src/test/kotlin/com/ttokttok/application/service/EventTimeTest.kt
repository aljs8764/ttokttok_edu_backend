package com.ttokttok.application.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EventTimeTest {
    private val now = Instant.parse("2026-10-05T07:00:00Z")

    @Test
    fun `오프라인 큐 요청은 10분 이내면 단말 시각을 채택한다`() {
        resolveEventTime(now.minus(Duration.ofMinutes(9)), now) shouldBe now.minus(Duration.ofMinutes(9))
    }

    @Test
    fun `10분을 넘거나 미래 시각이 크게 어긋나면 서버 시각을 쓴다`() {
        resolveEventTime(now.minus(Duration.ofMinutes(11)), now) shouldBe now
        resolveEventTime(now.plus(Duration.ofHours(1)), now) shouldBe now
        resolveEventTime(null, now) shouldBe now
    }
}
