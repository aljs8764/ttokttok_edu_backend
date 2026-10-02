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
 * JWT 서명·검증. Access 15분 / Refresh 14일(자동 로그인 30일).
 * refresh 의 jti·만료는 SessionUseCase 가 정하고 저장한다 — 회전·폐기·재사용 감지는 거기서.
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

    fun issue(userId: UserId, rememberMe: Boolean, refreshJti: UUID, refreshExpiresAt: Instant, now: Instant = Instant.now()): TokenPair {
        val accessExp = now.plus(accessTtl)
        val access = encode(userId, TYPE_ACCESS, now, accessExp, UUID.randomUUID())
        val refresh = encode(userId, TYPE_REFRESH, now, refreshExpiresAt, refreshJti, rememberMe)
        return TokenPair(access, refresh, accessExp)
    }

    data class RefreshClaims(val userId: UserId, val rememberMe: Boolean, val jti: UUID)

    /** refresh 토큰 서명·만료 검증 → (사용자, 자동로그인 여부, jti) */
    fun parseRefresh(token: String): RefreshClaims {
        val jwt = try { refreshDecoder.decode(token) } catch (e: JwtException) { throw UnauthenticatedException("refresh 토큰이 유효하지 않습니다") }
        if (jwt.getClaimAsString("typ") != TYPE_REFRESH || jwt.getClaimAsString("iss") != ISSUER) throw UnauthenticatedException("refresh 토큰이 아닙니다")
        val jti = jwt.id?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw UnauthenticatedException("refresh 토큰이 유효하지 않습니다")
        return RefreshClaims(UserId(UUID.fromString(jwt.subject)), jwt.getClaim<Boolean>("rm") ?: false, jti)
    }

    private fun encode(userId: UserId, type: String, now: Instant, exp: Instant, jti: UUID, rememberMe: Boolean = false): String {
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER).subject(userId.value.toString()).issuedAt(now).expiresAt(exp)
            .id(jti.toString())
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
