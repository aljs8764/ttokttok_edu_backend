package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.OtpUseCase
import com.ttokttok.application.port.`in`.SampleInstitutionUseCase
import com.ttokttok.application.port.`in`.SessionPolicyUseCase
import com.ttokttok.application.port.`in`.SocialLinkView
import com.ttokttok.application.port.`in`.SocialLoginUseCase
import com.ttokttok.application.port.`in`.SocialProvider
import com.ttokttok.domain.common.InvalidInputException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal fun parseProvider(raw: String): SocialProvider =
    SocialProvider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: throw InvalidInputException("INVALID_PROVIDER", "지원하지 않는 소셜 로그인입니다: $raw")

private fun SocialLinkView.toResponse() = mapOf("provider" to provider.name, "email" to email, "linkedAt" to linkedAt)

// ───────── AUTH-007 내 2단계 인증 ─────────
@RestController
class MeOtpController(private val otp: OtpUseCase) {
    data class CodeRequest(@field:NotBlank val code: String)
    data class DisableRequest(@field:NotBlank val password: String, @field:NotBlank val code: String)

    @GetMapping("/api/v1/me/otp")
    fun status(@AuthenticationPrincipal jwt: Jwt) = otp.status(jwt.userId()).let {
        mapOf("enabled" to it.enabled, "enabledAt" to it.enabledAt, "recoveryCodesLeft" to it.recoveryCodesLeft)
    }

    @PostMapping("/api/v1/me/otp/setup")
    fun setup(@AuthenticationPrincipal jwt: Jwt) = otp.setup(jwt.userId()).let { mapOf("secret" to it.secret, "otpauthUri" to it.otpauthUri) }

    @PostMapping("/api/v1/me/otp/enable")
    fun enable(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: CodeRequest) = mapOf("recoveryCodes" to otp.enable(jwt.userId(), req.code))

    @PostMapping("/api/v1/me/otp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disable(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: DisableRequest) = otp.disable(jwt.userId(), req.password, req.code)

    @PostMapping("/api/v1/me/otp/recovery-codes")
    fun regenerate(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: CodeRequest) = mapOf("recoveryCodes" to otp.regenerateRecoveryCodes(jwt.userId(), req.code))
}

// ───────── AUTH-006 내 소셜 계정 연결 ─────────
@RestController
class MeSocialController(private val social: SocialLoginUseCase) {
    data class TokenRequest(@field:NotBlank val accessToken: String)

    @GetMapping("/api/v1/me/social")
    fun list(@AuthenticationPrincipal jwt: Jwt) = mapOf(
        "providers" to social.enabledProviders().map { it.name },
        "links" to social.links(jwt.userId()).map { it.toResponse() },
    )

    @PutMapping("/api/v1/me/social/{provider}")
    fun link(@AuthenticationPrincipal jwt: Jwt, @PathVariable provider: String, @Valid @RequestBody req: TokenRequest) =
        social.link(jwt.userId(), parseProvider(provider), req.accessToken).map { it.toResponse() }

    @DeleteMapping("/api/v1/me/social/{provider}")
    fun unlink(@AuthenticationPrincipal jwt: Jwt, @PathVariable provider: String) =
        social.unlink(jwt.userId(), parseProvider(provider)).map { it.toResponse() }
}

// ───────── AUTH-010 자동 로그아웃 ─────────
@RestController
class SessionPolicyController(private val policy: SessionPolicyUseCase) {
    data class PolicyRequest(@field:NotNull val idleMinutes: Int)

    @GetMapping("/api/v1/institution/session-policy")
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        mapOf("idleMinutes" to policy.idleMinutes(jwt.userId(), inst(institutionId)))

    @PutMapping("/api/v1/institution/session-policy")
    fun put(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: PolicyRequest) =
        mapOf("idleMinutes" to policy.setIdleMinutes(jwt.userId(), inst(institutionId), req.idleMinutes))
}

// ───────── ONB-003 샘플 체험 기관 ─────────
@RestController
class SampleInstitutionController(private val samples: SampleInstitutionUseCase) {
    @GetMapping("/api/v1/sample-institution")
    fun mine(@AuthenticationPrincipal jwt: Jwt) = mapOf("sample" to samples.mine(jwt.userId())?.let { mapOf("institutionId" to it.institutionId, "name" to it.name) })

    @PostMapping("/api/v1/sample-institution")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt) = samples.create(jwt.userId()).let { mapOf("institutionId" to it.institutionId, "name" to it.name) }

    @DeleteMapping("/api/v1/sample-institution/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = samples.remove(jwt.userId(), id)
}
