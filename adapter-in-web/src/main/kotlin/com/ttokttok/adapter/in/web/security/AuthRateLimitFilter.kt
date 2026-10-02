package com.ttokttok.adapter.`in`.web.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 공개 인증 API Rate limit (스펙 8장: 로그인 IP당 10회/분).
 * 대상: 로그인·임시 비밀번호·초대 링크 제출·교직원 초대 수락 (무차별 대입 표면).
 * 고정 1분 창, 인스턴스 메모리 기준 — API 를 여러 대로 늘리면 Redis 카운터로 교체.
 * 클라이언트 IP 는 server.forward-headers-strategy=native 로 ALB 의 X-Forwarded-For 를 반영한 remoteAddr.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AuthRateLimitFilter(
    @Value("\${ttok.rate-limit.auth-per-minute:10}") private val limitPerMinute: Int,
) : OncePerRequestFilter() {

    private data class Window(val minute: Long, val count: AtomicInteger)
    private val windows = ConcurrentHashMap<String, Window>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return true
        val path = request.requestURI
        return PROTECTED.none { it.matches(path) }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val minute = System.currentTimeMillis() / 60_000
        val key = request.remoteAddr + "|" + bucketOf(request.requestURI)
        val window = windows.compute(key) { _, w -> if (w == null || w.minute != minute) Window(minute, AtomicInteger()) else w }!!
        if (window.count.incrementAndGet() > limitPerMinute) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", (60 - (System.currentTimeMillis() / 1000) % 60).toString())
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"code":"TOO_MANY_REQUESTS","message":"요청이 너무 많습니다. 잠시 후 다시 시도하세요"}""")
            return
        }
        if (windows.size > MAX_KEYS) windows.entries.removeIf { it.value.minute < minute } // 지난 창 정리
        chain.doFilter(request, response)
    }

    private fun bucketOf(path: String) = PROTECTED.indexOfFirst { it.matches(path) }

    companion object {
        private const val MAX_KEYS = 50_000
        private val PROTECTED = listOf(
            Regex("^/api/v1/auth/login$"),
            Regex("^/api/v1/auth/password/temp$"),
            Regex("^/api/v1/join/[^/]+$"),
            Regex("^/api/v1/staff-invitations/[^/]+/accept$"),
        )
    }
}
