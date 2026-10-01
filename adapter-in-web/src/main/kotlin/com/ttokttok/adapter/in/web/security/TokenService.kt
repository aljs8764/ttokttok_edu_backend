package com.ttokttok.adapter.`in`.web.security

import com.ttokttok.domain.common.UnauthenticatedException
import com.ttokttok.domain.common.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

data class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresAt: Instant)

/**
 * JWT 발급. Access 15분 / Refresh 14일(자동 로그인 30일).
 * TODO(S2): refresh 회전·폐기 목록을 Redis에 저장 (스펙 3장)
 */
@Component
class TokenService(
    private val encoder: JwtEncoder,
    @Value("\${ttok.jwt.secret}") secretB64: String,
    @Value("\${ttok.jwt.access-ttl:PT15M}") private val accessTtl: Duration,
    @Value("\${ttok.jwt.refresh-ttl:P14D}") private val refreshTtl: Duration,
    @Value("\${ttok.jwt.remember-me-ttl:P30D}") private val rememberMeTtl: Duration,
) {
    private val refreshDecoder = NimbusJwtDecoder
        .withSecretKey(SecretKeySpec(Base64.getDecoder().decode(secretB64), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256).build()

    fun issue(userId: UserId, rememberMe: Boolean, now: Instant = Instant.now()): TokenPair {
        val accessExp = now.plus(accessTtl)
        val access = encode(userId, TYPE_ACCESS, now, accessExp)
        val refresh = encode(userId, TYPE_REFRESH, now, now.plus(if (rememberMe) rememberMeTtl else refreshTtl), rememberMe)
        return TokenPair(access, refresh, accessExp)
    }

    /** refresh 토큰 검증 → (사용자, 자동로그인 여부) */
    fun parseRefresh(token: String): Pair<UserId, Boolean> {
        val jwt = try { refreshDecoder.decode(token) } catch (e: JwtException) { throw UnauthenticatedException("refresh 토큰이 유효하지 않습니다") }
        if (jwt.getClaimAsString("typ") != TYPE_REFRESH || jwt.getClaimAsString("iss") != ISSUER) throw UnauthenticatedException("refresh 토큰이 아닙니다")
        return UserId(UUID.fromString(jwt.subject)) to (jwt.getClaim<Boolean>("rm") ?: false)
    }

    private fun encode(userId: UserId, type: String, now: Instant, exp: Instant, rememberMe: Boolean = false): String {
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER).subject(userId.value.toString()).issuedAt(now).expiresAt(exp)
            .id(UUID.randomUUID().toString())
            .claim("typ", type)
            .apply { if (type == TYPE_REFRESH) claim("rm", rememberMe) }
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    companion object {
        const val ISSUER = "ttokttok"
        const val TYPE_ACCESS = "access"
        const val TYPE_REFRESH = "refresh"
    }
}
