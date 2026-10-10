package com.ttokttok.adapter.`in`.web.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.adapter.`in`.web.api.INSTITUTION_HEADER
import com.ttokttok.application.port.`in`.UsageDelta
import com.ttokttok.application.port.`in`.UsageMeteringUseCase
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SAAS-006 사용량 계측. 기관 헤더가 붙은 /api/v1 요청을 기관 × 시간 단위로 세어 1분마다 DB 에 더한다.
 * - 보안 필터보다 앞에서 돌아 401·403·429 도 센다. 사용자 수는 Bearer 토큰의 sub 를 검증 없이 읽어 세는데,
 *   2xx·3xx 응답(= 보안 필터를 통과한 요청)만 세므로 위조 토큰은 포함되지 않는다.
 * - 로그인(POST /auth/login)은 기관을 모르므로 플랫폼 행(00000000-…)에 성공·실패를 센다.
 * - 인스턴스 메모리에 모았다가 보내므로 서버가 갑자기 죽으면 최대 1분치가 빠질 수 있다 (모니터링 용도로 허용).
 * - 학부모 /me API 는 기관 헤더가 없어 기관별 사용량에서 빠진다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class UsageMeteringFilter(
    private val metering: UsageMeteringUseCase,
    private val json: ObjectMapper,
) : OncePerRequestFilter(), DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Counter {
        val requests = AtomicInteger()
        val errors = AtomicInteger()
        val denied = AtomicInteger()
        val throttled = AtomicInteger()
        val loginSuccess = AtomicInteger()
        val loginFailed = AtomicInteger()
        val users: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private data class Key(val institutionId: UUID, val hourEpoch: Long)

    private val buffer = ConcurrentHashMap<Key, Counter>()
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-flush").apply { isDaemon = true } }

    init {
        flusher.scheduleWithFixedDelay({ flush() }, 60, 60, TimeUnit.SECONDS)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } finally {
            runCatching { count(request, response.status) }.onFailure { log.debug("사용량 계측 실패", it) }
        }
    }

    private fun count(request: HttpServletRequest, status: Int) {
        val hour = System.currentTimeMillis() / 3_600_000
        if (request.method == "POST" && request.requestURI == "/api/v1/auth/login") {
            val c = buffer.computeIfAbsent(Key(PLATFORM, hour)) { Counter() }
            when (status) {
                200, 202 -> c.loginSuccess.incrementAndGet()
                401, 423 -> c.loginFailed.incrementAndGet()
            }
            return
        }
        val inst = request.getHeader(INSTITUTION_HEADER)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val c = buffer.computeIfAbsent(Key(inst, hour)) { Counter() }
        c.requests.incrementAndGet()
        when {
            status >= 500 -> c.errors.incrementAndGet()
            status == 401 || status == 403 -> c.denied.incrementAndGet()
            status == 429 -> c.throttled.incrementAndGet()
        }
        if (status < 400 && c.users.size < MAX_USERS_PER_KEY) subject(request)?.let { c.users.add(it) }
    }

    /** 검증 없이 JWT payload 의 sub 만 읽는다 (집계용) */
    private fun subject(request: HttpServletRequest): String? {
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching { json.readTree(Base64.getUrlDecoder().decode(payload)).get("sub")?.asText() }.getOrNull()
    }

    private fun flush() {
        try {
            val deltas = buffer.keys.toList().mapNotNull { key ->
                val c = buffer.remove(key) ?: return@mapNotNull null
                UsageDelta(
                    key.institutionId, Instant.ofEpochMilli(key.hourEpoch * 3_600_000), c.requests.get(), c.errors.get(), c.denied.get(),
                    c.throttled.get(), c.users.size, c.loginSuccess.get(), c.loginFailed.get(),
                )
            }
            if (deltas.isNotEmpty()) metering.record(deltas)
        } catch (e: Exception) {
            log.warn("사용량 저장 실패: {}", e.message)
        }
    }

    override fun destroy() {
        flusher.shutdown()
        flush()
    }

    companion object {
        private val PLATFORM = UUID(0L, 0L)
        private const val MAX_USERS_PER_KEY = 10_000
    }
}
