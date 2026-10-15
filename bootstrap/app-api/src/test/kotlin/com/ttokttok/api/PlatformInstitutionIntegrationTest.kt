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

/** SAAS-001 운영사 기관 등록·원장 초대, SAAS-009 운영사 계정 관리, SAAS-008 메뉴 그룹 */
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

    @Test
    fun `운영사 계정을 화면에서 지정·해제한다`() {
        val outsiderMail = "outsider-$run@ttok.dev"
        call(HttpMethod.GET, "/api/v1/platform/admins", outsiderToken, null, null).expect(403)

        call(HttpMethod.POST, "/api/v1/platform/admins", adminToken, null, mapOf("email" to "nobody-$run@ttok.dev")).expect(404)
        val added = call(HttpMethod.POST, "/api/v1/platform/admins", adminToken, null, mapOf("email" to outsiderMail.uppercase())).expect(201)
        added["email"].asText() shouldBe outsiderMail
        call(HttpMethod.POST, "/api/v1/platform/admins", adminToken, null, mapOf("email" to outsiderMail)).expect(409)

        // 새 운영사는 콘솔 API 를 쓸 수 있다
        call(HttpMethod.GET, "/api/v1/platform/me", outsiderToken, null, null).expect(200)["platformAdmin"].asBoolean() shouldBe true
        val outsiderId = added["userId"].asText()
        val admins = call(HttpMethod.GET, "/api/v1/platform/admins", outsiderToken, null, null).expect(200)
        admins.any { it["userId"].asText() == outsiderId } shouldBe true

        // 자기 자신은 해제 불가, 다른 운영사가 해제
        call(HttpMethod.DELETE, "/api/v1/platform/admins/$outsiderId", outsiderToken, null, null).expect(409)
        call(HttpMethod.DELETE, "/api/v1/platform/admins/$outsiderId", adminToken, null, null).expect(204)
        call(HttpMethod.GET, "/api/v1/platform/admins", outsiderToken, null, null).expect(403)
    }

    @Test
    fun `메뉴 그룹을 만들고 기관을 매핑하면 그 기관에서 끈 메뉴가 내려간다`() {
        val inst = signUp("메뉴학원-$run", "menu-owner-$run@ttok.dev")
        val ownerToken = inst["accessToken"].asText()
        val instId = inst["user"]["memberships"][0]["institutionId"].asText()

        // 매핑 전: 모든 메뉴
        call(HttpMethod.GET, "/api/v1/institution/menus", ownerToken, instId, null).expect(200)["disabledMenus"].size() shouldBe 0

        call(HttpMethod.POST, "/api/v1/platform/menu-groups", outsiderToken, null, mapOf("name" to "x")).expect(403)
        call(HttpMethod.POST, "/api/v1/platform/menu-groups", adminToken, null, mapOf("name" to "전체-$run")).expect(201)
        val minimal = call(HttpMethod.POST, "/api/v1/platform/menu-groups", adminToken, null,
            mapOf("name" to "최소-$run", "disabledMenus" to listOf("/dashboard", "/shuttle"))).expect(201)
        val groupId = minimal["id"].asText()
        call(HttpMethod.POST, "/api/v1/platform/menu-groups", adminToken, null, mapOf("name" to "최소-$run")).expect(409)
        call(HttpMethod.POST, "/api/v1/platform/menu-groups", adminToken, null,
            mapOf("name" to "잘못-$run", "disabledMenus" to listOf("dashboard; drop"))).expect(400)

        // 그룹에 기관 매핑 → 기관 화면에 끈 메뉴
        val mapped = call(HttpMethod.PUT, "/api/v1/platform/menu-groups/$groupId/institutions", adminToken, null,
            mapOf("institutionIds" to listOf(instId))).expect(200)
        mapped["institutions"][0]["id"].asText() shouldBe instId
        val menus = call(HttpMethod.GET, "/api/v1/institution/menus", ownerToken, instId, null).expect(200)
        menus["menuGroupName"].asText() shouldBe "최소-$run"
        menus["disabledMenus"].map { it.asText() }.toSet() shouldBe setOf("/dashboard", "/shuttle")
        call(HttpMethod.GET, "/api/v1/platform/institutions/$instId", adminToken, null, null).expect(200)["menuGroupId"].asText() shouldBe groupId

        // 그룹 수정: 대시보드만 끔
        call(HttpMethod.PUT, "/api/v1/platform/menu-groups/$groupId", adminToken, null,
            mapOf("name" to "최소-$run", "disabledMenus" to listOf("/dashboard"))).expect(200)
        call(HttpMethod.GET, "/api/v1/institution/menus", ownerToken, instId, null).expect(200)["disabledMenus"].map { it.asText() } shouldBe listOf("/dashboard")

        // 기관 단위 해제 → 모든 메뉴, 다시 지정 후 그룹 삭제 → 모든 메뉴
        call(HttpMethod.PUT, "/api/v1/platform/institutions/$instId/menu-group", adminToken, null, mapOf("menuGroupId" to null)).expect(204)
        call(HttpMethod.GET, "/api/v1/institution/menus", ownerToken, instId, null).expect(200)["disabledMenus"].size() shouldBe 0
        call(HttpMethod.PUT, "/api/v1/platform/institutions/$instId/menu-group", adminToken, null, mapOf("menuGroupId" to groupId)).expect(204)
        call(HttpMethod.DELETE, "/api/v1/platform/menu-groups/$groupId", adminToken, null, null).expect(204)
        call(HttpMethod.GET, "/api/v1/institution/menus", ownerToken, instId, null).expect(200)["menuGroupId"].isNull shouldBe true
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
