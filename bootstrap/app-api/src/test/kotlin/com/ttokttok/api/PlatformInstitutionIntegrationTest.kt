package com.ttokttok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.adapter.out.notification.RecordingEmailSender
import com.ttokttok.application.port.out.SendEmailPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.util.UUID

/** SAAS-001 운영사 기관 등록 → 원장 초대 메일 → 재발송 → 수락하면 OWNER 로 업무 API 사용 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformInstitutionIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var emailPort: SendEmailPort

    private val email get() = emailPort as RecordingEmailSender
    private val run = UUID.randomUUID().toString().take(8)

    private lateinit var adminToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        email.sent.clear()
        val admin = signUp("운영사-$run", "op-$run@ttok.dev")
        jdbc.update("insert into platform_admin (user_id) values (?)", UUID.fromString(admin["user"]["id"].asText()))
        adminToken = admin["accessToken"].asText()
        outsiderToken = signUp("다른학원-$run", "outsider-$run@ttok.dev")["accessToken"].asText()
    }

    @Test
    fun `운영사가 기관을 등록하면 원장 초대 메일이 가고, 수락하면 원장으로 업무를 시작한다`() {
        val ownerEmail = "new-owner-$run@ttok.dev"
        val body = mapOf("institutionName" to "똑똑영어-$run", "type" to "ACADEMY", "ownerName" to "박원장", "ownerEmail" to ownerEmail)

        // 운영사가 아니면 등록할 수 없다
        call(HttpMethod.POST, "/api/v1/platform/institutions", outsiderToken, null, body).expect(403)

        val created = call(HttpMethod.POST, "/api/v1/platform/institutions", adminToken, null, body).expect(201)
        val instId = created["id"].asText()
        created["serviceStatus"].asText() shouldBe "ACTIVE"
        created["ownerInvitation"]["status"].asText() shouldBe "PENDING"
        val firstToken = lastInviteToken(ownerEmail)

        // 재발송하면 이전 링크는 못 쓴다
        call(HttpMethod.POST, "/api/v1/platform/institutions/$instId/owner-invitation", adminToken, null, null).expect(200)
        val token = lastInviteToken(ownerEmail)
        (token == firstToken) shouldBe false
        call(HttpMethod.GET, "/api/v1/staff-invitations/$firstToken", null, null, null).expect(409)

        val info = call(HttpMethod.GET, "/api/v1/staff-invitations/$token", null, null, null).expect(200)
        info["role"].asText() shouldBe "OWNER"
        info["existingAccount"].asBoolean() shouldBe false

        val owner = call(HttpMethod.POST, "/api/v1/staff-invitations/$token/accept", null, null, mapOf("password" to "Passw0rd!")).expect(200)
        val membership = owner["user"]["memberships"][0]
        membership["institutionId"].asText() shouldBe instId
        membership["role"].asText() shouldBe "OWNER"

        // 원장 권한으로 업무 API 사용
        call(HttpMethod.POST, "/api/v1/destinations", owner["accessToken"].asText(), instId, mapOf("name" to "집", "type" to "HOME")).expect(201)

        val detail = call(HttpMethod.GET, "/api/v1/platform/institutions/$instId", adminToken, null, null).expect(200)
        detail["ownerInvitation"]["status"].asText() shouldBe "ACCEPTED"
        detail["staffCount"].asInt() shouldBe 1
        call(HttpMethod.POST, "/api/v1/platform/institutions/$instId/owner-invitation", adminToken, null, null).expect(409)
    }

    private fun signUp(name: String, mail: String) = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
        mapOf("institutionName" to name, "ownerName" to "김원장", "email" to mail, "password" to "Passw0rd!")).expect(201)

    private fun lastInviteToken(to: String): String {
        val mail = email.sent.last { it.to == to }
        return mail.body.trim().lines().last().substringAfterLast('/')
    }

    private data class Res(val status: Int, val body: JsonNode)

    private fun Res.expect(code: Int): JsonNode {
        if (status != code) throw AssertionError("expected HTTP $code but was $status: $body")
        return body
    }

    private fun call(method: HttpMethod, path: String, token: String?, instId: String?, body: Any?): Res {
        val req = request(method, path).contentType(MediaType.APPLICATION_JSON)
        token?.let { req.header("Authorization", "Bearer $it") }
        instId?.let { req.header("X-Institution-Id", it) }
        body?.let { req.content(json.writeValueAsString(it)) }
        val res = mvc.perform(req).andReturn().response
        val text = res.getContentAsString(Charsets.UTF_8)
        return Res(res.status, if (text.isBlank()) json.nullNode() else json.readTree(text))
    }
}
