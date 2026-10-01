package com.ttokttok.domain.common

import java.security.SecureRandom
import java.util.UUID

/** RFC 9562 UUID v7 — 시간순 정렬되는 PK (인덱스 지역성 확보). */
object Uuid7 {
    private val random = SecureRandom()

    fun next(epochMillis: Long = System.currentTimeMillis()): UUID {
        val randA = random.nextInt(1 shl 12).toLong()
        val msb = (epochMillis shl 16) or (0x7L shl 12) or randA
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // variant 10
        return UUID(msb, lsb)
    }
}
