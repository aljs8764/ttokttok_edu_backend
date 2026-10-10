package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.PlatformUsageQuery
import com.ttokttok.application.port.`in`.UiLocale
import com.ttokttok.application.port.`in`.UserLocaleUseCase
import com.ttokttok.domain.common.InvalidInputException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// ───────── SAAS-006 운영 콘솔: 사용량·이상 탐지 ─────────
@RestController
class PlatformUsageController(private val usage: PlatformUsageQuery) {
    @GetMapping("/api/v1/platform/usage")
    fun overview(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "7") days: Int) = usage.overview(jwt.userId(), days)

    /** institutionId 없으면 플랫폼 전체 */
    @GetMapping("/api/v1/platform/usage/hourly")
    fun hourly(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) institutionId: UUID?, @RequestParam(defaultValue = "7") days: Int) =
        usage.hourly(jwt.userId(), institutionId, days)

    @GetMapping("/api/v1/platform/usage-alerts")
    fun alerts(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "true") openOnly: Boolean) = usage.alerts(jwt.userId(), openOnly)

    @PostMapping("/api/v1/platform/usage-alerts/{id}/ack")
    fun ack(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = usage.acknowledge(jwt.userId(), id)
}

// ───────── SET-007 표시 언어 (기관 헤더 없이) ─────────
@RestController
class UserLocaleController(private val locales: UserLocaleUseCase) {
    data class LocaleRequest(@field:NotBlank val locale: String)

    @GetMapping("/api/v1/me/locale")
    fun get(@AuthenticationPrincipal jwt: Jwt) = mapOf("locale" to locales.get(jwt.userId()).code)

    @PutMapping("/api/v1/me/locale")
    fun set(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: LocaleRequest): Map<String, String> {
        val l = UiLocale.of(req.locale) ?: throw InvalidInputException("INVALID_LOCALE", "지원하는 언어는 ko, en 입니다")
        return mapOf("locale" to locales.set(jwt.userId(), l).code)
    }
}
