package com.ttokttok.domain.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhoneNumberTest {
    @Test
    fun `하이픈 유무와 관계없이 정규화된다`() {
        PhoneNumber.of("010-1234-5678") shouldBe PhoneNumber.of("01012345678")
        PhoneNumber.of("010 1234 5678").last4 shouldBe "5678"
        PhoneNumber.of("01012345678").masked shouldBe "010-****-5678"
    }

    @Test
    fun `휴대폰 형식이 아니면 거부한다`() {
        shouldThrow<InvalidInputException> { PhoneNumber.of("02-123-4567") }
        shouldThrow<InvalidInputException> { PhoneNumber.of("0101234") }
    }

    @Test
    fun `UUID v7은 시간순으로 정렬된다`() {
        val a = Uuid7.next(1_000L)
        val b = Uuid7.next(2_000L)
        (a < b) shouldBe true
        a.version() shouldBe 7
    }
}
