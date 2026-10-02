package com.ttokttok.domain.user

import com.ttokttok.domain.common.UserId
import java.time.Duration
import java.time.Instant

/**
 * 로그인 실패 잠금 (스펙 3장: 5회 실패 시 10분 잠금).
 * 잠금이 풀린 뒤 첫 실패는 1회부터 다시 센다. 성공하면 초기화.
 */
data class LoginAttempt(
    val userId: UserId,
    val failedCount: Int = 0,
    val lockedUntil: Instant? = null,
) {
    fun isLocked(now: Instant) = lockedUntil != null && now.isBefore(lockedUntil)

    fun recordFailure(now: Instant): LoginAttempt {
        val base = if (lockedUntil != null && !now.isBefore(lockedUntil)) 0 else failedCount
        val count = base + 1
        return if (count >= MAX_FAILURES) copy(failedCount = count, lockedUntil = now.plus(LOCK_DURATION))
        else copy(failedCount = count, lockedUntil = null)
    }

    fun remainingAttempts(): Int = (MAX_FAILURES - failedCount).coerceAtLeast(0)

    companion object {
        const val MAX_FAILURES = 5
        val LOCK_DURATION: Duration = Duration.ofMinutes(10)
    }
}
