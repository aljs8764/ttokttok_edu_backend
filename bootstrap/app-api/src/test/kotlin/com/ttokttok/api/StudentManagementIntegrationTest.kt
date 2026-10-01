package com.ttokttok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.adapter.out.notification.RecordingAlimtalkSender
import com.ttokttok.adapter.out.notification.RecordingEmailSender
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
import com.ttokttok.application.port.out.SendAlimtalkPort
import com.ttokttok.application.port.out.SendEmailPort
import com.ttokttok.application.port.out.SpreadsheetPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.util.UUID

/**
 * S3–4 원생·교직원 관리 인수 테스트:
 * 목록 검색(STU-001) · 엑셀 업로드(STU-002) · 초대→가입승인(STU-004/005) · 반 이동/퇴원(STU-006/012)
 * · 반 삭제 규칙(CLS-002) · 교직원 목록(STF-001) · 임시 비밀번호(AUTH-004/005)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentManagementIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var processOutbox: ProcessOutboxUseCase
    @Autowired lateinit var alimtalkPort: SendAlimtalkPort
    @Autowired lateinit var emailPort: SendEmailPort
    @Autowired lateinit var sheets: SpreadsheetPort

    private val alimtalk get() = alimtalkPort as RecordingAlimtalkSender
    private val email get() = emailPort as RecordingEmailSender
    private val run = UUID.randomUUID().toString().take(8)
    private fun phone() = "010" + (10_000_000 + (Math.random() * 89_999_999).toInt())

    private lateinit var ownerToken: String
    private lateinit var instId: String
    private lateinit var classA: String
    private lateinit var classB: String

    @BeforeEach
    fun setUp() {
        drainOutbox()
        alimtalk.sent.clear()
        email.sent.clear()
        val owner = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
            mapOf("institutionName" to "똑똑영어", "ownerName" to "한원장", "email" to "owner-$run@ttok.dev", "password" to "Passw0rd!")).expect(201)
        ownerToken = owner["accessToken"].asText()
        instId = owner["user"]["memberships"][0]["institutionId"].asText()
        classA = newClass("초등 A반", 10)
        classB = newClass("초등 B반", 10)
    }

    @Test
    fun `원생 검색 - 이름·보호자 번호 뒷 4자리·상태 필터·페이징`() {
        val p = phone()
        newStudent("김하나", classA, p)
        newStudent("이두리", classA, phone())
        newStudent("박세나", classB, phone())

        search("keyword=하나")["totalElements"].asLong() shouldBe 1
        search("keyword=${p.takeLast(4)}")["items"][0]["name"].asText() shouldBe "김하나"
        search("classId=$classA")["totalElements"].asLong() shouldBe 2
        val paged = search("size=2&page=0")
        paged["items"].size() shouldBe 2
        paged["totalPages"].asInt() shouldBe 2
        search("status=WITHDRAWN")["totalElements"].asLong() shouldBe 0
    }

    @Test
    fun `반 이동 이력과 퇴원 처리, 빈 반만 삭제`() {
        val id = newStudent("정다운", classA, phone())["id"].asText()

        // 소속 학생이 있으면 반 삭제 불가
        call(HttpMethod.DELETE, "/api/v1/classes/$classA", ownerToken, instId, null).expect(409)["code"].asText() shouldBe "CLASS_NOT_EMPTY"

        call(HttpMethod.POST, "/api/v1/students/$id/class-move", ownerToken, instId, mapOf("toClassroomId" to classB))
            .expect(200)["classroomIds"][0].asText() shouldBe classB
        val detail = call(HttpMethod.GET, "/api/v1/students/$id", ownerToken, instId, null).expect(200)
        detail["classHistory"].size() shouldBe 2
        detail["classHistory"].any { it["classroomId"].asText() == classA && !it["toDate"].isNull } shouldBe true

        // A반은 이제 비었으므로 삭제 가능
        call(HttpMethod.DELETE, "/api/v1/classes/$classA", ownerToken, instId, null).expect(204)

        // 퇴원: 사유 필수 → 이력 기록 + 반 소속 마감
        call(HttpMethod.POST, "/api/v1/students/$id/status", ownerToken, instId, mapOf("status" to "WITHDRAWN")).expect(400)
        call(HttpMethod.POST, "/api/v1/students/$id/status", ownerToken, instId,
            mapOf("status" to "WITHDRAWN", "reason" to "MOVING", "note" to "이사")).expect(200)["classroomIds"].size() shouldBe 0
        val after = call(HttpMethod.GET, "/api/v1/students/$id", ownerToken, instId, null).expect(200)
        after["statusHistory"][0]["reason"].asText() shouldBe "MOVING"
        search("status=WITHDRAWN")["totalElements"].asLong() shouldBe 1
    }

    @Test
    fun `초대 링크 → 학부모 제출 → 승인으로 원생 생성`() {
        val p = phone()
        call(HttpMethod.POST, "/api/v1/invitations/parents", ownerToken, instId, mapOf("phone" to p)).expect(201)
        drainOutbox()
        val invite = alimtalk.sent.single { it.templateCode == "TTOK_JOIN_INVITE" }
        invite.phone.digits shouldBe p
        val joinUrl = invite.variables.getValue("joinUrl")
        joinUrl shouldStartWith "https://ttok.app/join/"
        val token = joinUrl.substringAfterLast("/")

        // 로그인 없이 링크 열람·제출
        call(HttpMethod.GET, "/api/v1/join/$token", null, null, null).expect(200)["institutionName"].asText() shouldBe "똑똑영어"
        call(HttpMethod.POST, "/api/v1/join/$token", null, null,
            mapOf("childName" to "최아라", "birthDate" to "2016-05-05", "guardianName" to "최아빠", "relation" to "부")).expect(201)
        call(HttpMethod.GET, "/api/v1/join/invalid-token-xxxxxxxx", null, null, null).expect(404)

        val pending = call(HttpMethod.GET, "/api/v1/join-requests?status=PENDING", ownerToken, instId, null).expect(200)
        pending.size() shouldBe 1
        val reqId = pending[0]["id"].asText()

        val student = call(HttpMethod.POST, "/api/v1/join-requests/$reqId/approve", ownerToken, instId, mapOf("classroomId" to classA)).expect(200)
        student["name"].asText() shouldBe "최아라"
        student["classroomIds"][0].asText() shouldBe classA
        // 두 번 승인 불가
        call(HttpMethod.POST, "/api/v1/join-requests/$reqId/approve", ownerToken, instId, mapOf("classroomId" to classA)).expect(409)

        drainOutbox()
        alimtalk.sent.any { it.templateCode == "TTOK_JOIN_APPROVED" && it.variables["classroomName"] == "초등 A반" } shouldBe true
    }

    @Test
    fun `엑셀 업로드 - 오류 행만 걸러내고 정상 행만 확정`() {
        call(HttpMethod.GET, "/api/v1/students/import/template", ownerToken, instId, null, raw = true).status shouldBe 200

        val dupPhone = phone()
        val file = sheets.write(
            "원생등록", listOf("이름*", "생년월일*", "반명*", "보호자 연락처*", "보호자 관계", "학년", "메모"),
            listOf(
                listOf("엑셀일", "2017-01-01", "초등 A반", dupPhone, "모", "초3", ""),
                listOf("엑셀이", "2017-02-30", "초등 A반", phone(), "", "", ""),          // 잘못된 날짜
                listOf("엑셀삼", "2017-03-03", "없는반", phone(), "", "", ""),            // 미등록 반
                listOf("엑셀사", "2017-04-04", "초등 B반", "02-123-4567", "", "", ""),     // 연락처 형식
                listOf("엑셀일", "2017-01-01", "초등 A반", dupPhone, "모", "", ""),        // 파일 내 중복
                listOf("엑셀오", "2017-05-05", "초등 B반", phone(), "부", "초2", "형제 있음"),
            ),
        )
        val res = mvc.perform(
            multipart("/api/v1/students/import").file(MockMultipartFile("file", "students.xlsx", "application/octet-stream", file))
                .header("Authorization", "Bearer $ownerToken").header("X-Institution-Id", instId),
        ).andReturn().response
        res.status shouldBe 200
        val validated = json.readTree(res.contentAsString)
        validated["totalRows"].asInt() shouldBe 6
        validated["validRows"].asInt() shouldBe 2
        validated["errors"].map { it["rowNumber"].asInt() }.toSet() shouldBe setOf(3, 4, 5, 6)
        val jobId = validated["jobId"].asText()

        call(HttpMethod.GET, "/api/v1/students/import/$jobId/errors", ownerToken, instId, null, raw = true).status shouldBe 200
        call(HttpMethod.POST, "/api/v1/students/import/$jobId/commit", ownerToken, instId, mapOf("sendInstallGuide" to true))
            .expect(200)["created"].asInt() shouldBe 2
        call(HttpMethod.POST, "/api/v1/students/import/$jobId/commit", ownerToken, instId, null).expect(409)

        search("keyword=엑셀")["totalElements"].asLong() shouldBe 2
        drainOutbox()
        alimtalk.sent.count { it.templateCode == "TTOK_APP_INSTALL" } shouldBe 2
    }

    @Test
    fun `교직원 목록과 임시 비밀번호 → 변경`() {
        val teacher = call(HttpMethod.POST, "/api/v1/staff", ownerToken, instId,
            mapOf("name" to "유선생", "email" to "t-$run@ttok.dev", "temporaryPassword" to "Temp1234!")).expect(201)["userId"].asText()
        call(HttpMethod.PUT, "/api/v1/classes/$classA/teachers", ownerToken, instId, mapOf("teacherIds" to listOf(teacher))).expect(200)

        val staff = call(HttpMethod.GET, "/api/v1/staff", ownerToken, instId, null).expect(200)
        staff.size() shouldBe 2
        staff.single { it["userId"].asText() == teacher }["classrooms"][0]["name"].asText() shouldBe "초등 A반"
        // 교사는 담당 반 목록에서 인원·교사명을 함께 본다
        val teacherToken = login("t-$run@ttok.dev", "Temp1234!")["accessToken"].asText()
        val myClasses = call(HttpMethod.GET, "/api/v1/classes", teacherToken, instId, null).expect(200)
        myClasses.size() shouldBe 1
        myClasses[0]["teacherNames"][0].asText() shouldBe "유선생"

        // 미가입 이메일도 202 (존재 여부 비노출), 메일은 가입자에게만
        call(HttpMethod.POST, "/api/v1/auth/password/temp", null, null, mapOf("email" to "nobody-$run@ttok.dev")).expect(202)
        call(HttpMethod.POST, "/api/v1/auth/password/temp", null, null, mapOf("email" to "t-$run@ttok.dev")).expect(202)
        val mail = email.sent.single()
        val temp = Regex("임시 비밀번호는 (\\S+) 입니다").find(mail.body)!!.groupValues[1]

        login("t-$run@ttok.dev", "Temp1234!", expect = 401)
        val tempLogin = login("t-$run@ttok.dev", temp)
        tempLogin["user"]["mustChangePassword"].asBoolean() shouldBe true
        call(HttpMethod.PUT, "/api/v1/auth/password", tempLogin["accessToken"].asText(), null,
            mapOf("currentPassword" to temp, "newPassword" to "NewPass99")).expect(204)
        login("t-$run@ttok.dev", "NewPass99")["user"]["mustChangePassword"].asBoolean() shouldBe false
    }

    // ── helpers ──
    private fun newClass(name: String, capacity: Int) = call(HttpMethod.POST, "/api/v1/classes", ownerToken, instId, mapOf(
        "name" to name, "capacity" to capacity, "days" to listOf("MONDAY", "WEDNESDAY"), "startTime" to "15:00", "endTime" to "17:00",
    )).expect(201)["id"].asText()

    private fun newStudent(name: String, classId: String, phone: String) = call(HttpMethod.POST, "/api/v1/students", ownerToken, instId, mapOf(
        "name" to name, "birthDate" to "2017-03-02", "classroomId" to classId, "guardians" to listOf(mapOf("phone" to phone)),
    )).expect(201)

    private fun search(query: String) = call(HttpMethod.GET, "/api/v1/students?$query", ownerToken, instId, null).expect(200)

    private fun login(id: String, pw: String, expect: Int = 200) =
        call(HttpMethod.POST, "/api/v1/auth/login", null, null, mapOf("loginId" to id, "password" to pw)).expect(expect)

    private fun drainOutbox() { while (processOutbox.processBatch(50) > 0) Unit }

    private data class Res(val status: Int, val body: JsonNode)

    private fun Res.expect(code: Int): JsonNode {
        if (status != code) throw AssertionError("expected HTTP $code but was $status: $body")
        return body
    }

    private fun call(method: HttpMethod, path: String, token: String?, instId: String?, body: Any?, raw: Boolean = false): Res {
        val req = request(method, path).contentType(MediaType.APPLICATION_JSON)
        token?.let { req.header("Authorization", "Bearer $it") }
        instId?.let { req.header("X-Institution-Id", it) }
        body?.let { req.content(json.writeValueAsString(it)) }
        val res = mvc.perform(req).andReturn().response
        if (raw) return Res(res.status, json.nullNode())
        val text = res.getContentAsString(Charsets.UTF_8)
        return Res(res.status, if (text.isBlank()) json.nullNode() else json.readTree(text))
    }
}
