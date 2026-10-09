package com.ttokttok.adapter.out.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.application.port.`in`.SocialProvider
import com.ttokttok.application.port.out.SocialProfile
import com.ttokttok.application.port.out.SocialProfilePort
import com.ttokttok.domain.common.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AUTH-006 카카오·네이버 사용자 정보 조회. 앱이 SDK 로 받은 access token 을 서버가 그대로 들고 가서 확인한다.
 * - 카카오: access_token_info 로 우리 앱(app_id)에서 발급된 토큰인지 확인 → /v2/user/me
 * - 네이버: /v1/nid/me (네이버는 토큰의 발급 앱을 확인하는 API 가 없어 응답만 신뢰한다)
 * 운영 키는 환경변수로 넣고, enabled=false 면 버튼을 숨긴다.
 */
@Component
class SocialProfileAdapter(
    @Value("\${ttok.social.kakao.enabled:false}") private val kakaoEnabled: Boolean,
    @Value("\${ttok.social.kakao.app-id:}") private val kakaoAppId: String,
    @Value("\${ttok.social.naver.enabled:false}") private val naverEnabled: Boolean,
) : SocialProfilePort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val mapper = ObjectMapper()

    override fun enabled(provider: SocialProvider): Boolean = when (provider) {
        SocialProvider.KAKAO -> kakaoEnabled
        SocialProvider.NAVER -> naverEnabled
    }

    override fun fetch(provider: SocialProvider, accessToken: String): SocialProfile {
        if (accessToken.isBlank() || accessToken.length > 4096) throw UnauthenticatedException("소셜 로그인 정보가 올바르지 않습니다")
        return when (provider) {
            SocialProvider.KAKAO -> kakao(accessToken)
            SocialProvider.NAVER -> naver(accessToken)
        }
    }

    private fun kakao(token: String): SocialProfile {
        if (kakaoAppId.isNotBlank()) {
            val info = get("https://kapi.kakao.com/v1/user/access_token_info", token)
            if (info.path("app_id").asText() != kakaoAppId) {
                log.warn("다른 앱에서 발급된 카카오 토큰 (app_id={})", info.path("app_id").asText())
                throw UnauthenticatedException("소셜 로그인 정보가 올바르지 않습니다")
            }
        }
        val me = get("https://kapi.kakao.com/v2/user/me", token)
        val id = me.path("id").asText().ifBlank { throw UnauthenticatedException("소셜 로그인 정보가 올바르지 않습니다") }
        val account = me.path("kakao_account")
        return SocialProfile(
            providerUserId = id,
            email = account.text("email")?.takeIf { account.path("is_email_verified").asBoolean(true) },
            name = account.text("name") ?: account.path("profile").text("nickname") ?: me.path("properties").text("nickname"),
            phone = account.text("phone_number"),
        )
    }

    private fun naver(token: String): SocialProfile {
        val body = get("https://openapi.naver.com/v1/nid/me", token)
        if (body.path("resultcode").asText() != "00") throw UnauthenticatedException("소셜 로그인 정보가 올바르지 않습니다")
        val r = body.path("response")
        val id = r.path("id").asText().ifBlank { throw UnauthenticatedException("소셜 로그인 정보가 올바르지 않습니다") }
        return SocialProfile(id, r.text("email"), r.text("name") ?: r.text("nickname"), r.text("mobile"))
    }

    private fun get(url: String, token: String): JsonNode {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $token").GET().build()
        val res = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("소셜 사용자 정보 조회 실패: {}", e.message)
            throw IllegalStateException("소셜 로그인 서버에 연결할 수 없습니다", e)
        }
        if (res.statusCode() == 401 || res.statusCode() == 400) throw UnauthenticatedException("소셜 로그인이 만료됐습니다. 다시 시도해 주세요")
        if (res.statusCode() !in 200..299) throw IllegalStateException("소셜 로그인 서버 오류 (${res.statusCode()})")
        return mapper.readTree(res.body())
    }

    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
}
