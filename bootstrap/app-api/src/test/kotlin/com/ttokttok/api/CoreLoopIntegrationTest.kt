package com.ttokttok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.adapter.out.notification.RecordingPushSender
import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import com.ttokttok.application.port.`in`.ProcessOutboxUseCase
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.SendPushPort
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * S1 Core Loop 인수 테스트 (스펙 1장 목표):
 * "교사가 [하원(목적지)]을 누르면 학부모가 즉시 푸시를 받고 타임라인에서 확인한다"
 *
 * 실행 전제: PostgreSQL (TEST_DB_URL, 기본 localhost:5432/ttok_test)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreLoopIntegrationTest {

    @TestConfiguration
    class FixedClockConfig {
        @Bean @Primary
        fun fixedClock(): MutableClock = MutableClock()
    }

    class MutableClock : ClockPort {
        val current = AtomicReference(Instant.now())
        override fun now(): Instant = current.get()
        fun set(date: LocalDate, hour: Int, minute: Int) = current.set(date.atTime(hour, minute).atZone(ZoneId.of("Asia/Seoul")).toInstant())
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var processOutbox: ProcessOutboxUseCase
    @Autowired lateinit var batch: DailyAttendanceBatchUseCase
    @Autowired lateinit var pushPort: SendPushPort

    private val monday = LocalDate.of(2026, 10, 5)
    private val push get() = pushPort as RecordingPushSender

    // 테스트끼리 데이터가 겹치지 않도록 실행마다 고유 이메일·번호 사용
    private val run = UUID.randomUUID().toString().take(8)
    private val parentPhone = "010" + (10_000_000 + (Math.random() * 89_999_999).toInt())

    @BeforeEach
    fun setUp() {
        clock.set(monday, 14, 0)
        drainOutbox()
        push.sent.clear()
    }

    @Test
    fun `교사 등원·하원 → 학부모 푸시 → 통합 타임라인`() {
        // ── 원장 가입 ──
        val owner = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
            mapOf("institutionName" to "똑똑수학학원", "ownerName" to "김원장", "email" to "owner-$run@ttok.dev", "password" to "Passw0rd!"))
            .expect(201)
        val ownerToken = owner["accessToken"].asText()
        val instId = owner["user"]["memberships"][0]["institutionId"].asText()
        owner["user"]["memberships"][0]["role"].asText() shouldBe "OWNER"

        // ── 교사 계정 생성 → 로그인 ──
        val teacher = call(HttpMethod.POST, "/api/v1/staff", ownerToken, instId,
            mapOf("name" to "이선생", "email" to "teacher-$run@ttok.dev", "temporaryPassword" to "Temp1234!", "role" to "TEACHER"))
            .expect(201)
        val teacherId = teacher["userId"].asText()
        val teacherLogin = call(HttpMethod.POST, "/api/v1/auth/login", null, null,
            mapOf("loginId" to "teacher-$run@ttok.dev", "password" to "Temp1234!")).expect(200)
        teacherLogin["user"]["mustChangePassword"].asBoolean() shouldBe true
        val teacherToken = teacherLogin["accessToken"].asText()

        // ── 목적지·반·원생 ──
        val home = call(HttpMethod.POST, "/api/v1/destinations", ownerToken, instId, mapOf("name" to "집", "type" to "HOME")).expect(201)
        val classroom = call(HttpMethod.POST, "/api/v1/classes", ownerToken, instId, mapOf(
            "name" to "초등 3반", "capacity" to 10, "days" to listOf("MONDAY", "WEDNESDAY", "FRIDAY"),
            "startTime" to "15:00", "endTime" to "17:00", "teacherIds" to listOf(teacherId),
        )).expect(201)
        val classId = classroom["id"].asText()
        val student = call(HttpMethod.POST, "/api/v1/students", ownerToken, instId, mapOf(
            "name" to "박하늘", "birthDate" to "2017-03-02", "classroomId" to classId,
            "guardians" to listOf(mapOf("phone" to parentPhone, "relation" to "모", "isPrimary" to true)),
        )).expect(201)
        val studentId = student["id"].asText()
        student["guardians"][0]["linkStatus"].asText() shouldBe "PENDING"

        // 교사는 담당 반 학생만 보고, 생년월일은 마스킹된다
        val roster = call(HttpMethod.GET, "/api/v1/students?classId=$classId", teacherToken, instId, null).expect(200)["items"]
        roster shouldHaveSizeOf 1
        roster[0]["birthDate"].isNull shouldBe true

        // ── 00:05 배치: 월요일 수업이므로 예정 생성 (다른 테스트 데이터도 함께 생성될 수 있음) ──
        batch.generateScheduled(monday)
        call(HttpMethod.GET, "/api/v1/attendance/daily?classId=$classId&date=$monday", teacherToken, instId, null)
            .expect(200)[0]["status"].asText() shouldBe "SCHEDULED"

        // ── 학부모 가입: 같은 번호 → 자녀 자동 연결, 기기 등록 ──
        val parent = call(HttpMethod.POST, "/api/v1/auth/parents", null, null,
            mapOf("name" to "최엄마", "phone" to parentPhone, "password" to "Parent123")).expect(201)
        val parentToken = parent["accessToken"].asText()
        parent["user"]["memberships"][0]["role"].asText() shouldBe "PARENT"
        call(HttpMethod.GET, "/api/v1/me/children", parentToken, null, null).expect(200)[0]["enrollments"][0]["studentId"].asText() shouldBe studentId
        call(HttpMethod.PUT, "/api/v1/me/devices", parentToken, null, mapOf("flavor" to "PARENT", "platform" to "IOS", "token" to "fcm-$run")).expect(204)

        // ── 15:12 등원 (지각 기준 15:10 초과 → 지각) ──
        clock.set(monday, 15, 12)
        val key = "checkin-$run"
        val checkedIn = call(HttpMethod.POST, "/api/v1/attendance/check-in", teacherToken, instId,
            mapOf("studentId" to studentId, "classroomId" to classId), idempotencyKey = key).expect(200)
        checkedIn["status"].asText() shouldBe "IN"
        checkedIn["isLate"].asBoolean() shouldBe true

        // 같은 키로 재전송(네트워크 재시도) → 오류 없이 같은 결과, 이벤트 중복 없음
        call(HttpMethod.POST, "/api/v1/attendance/check-in", teacherToken, instId,
            mapOf("studentId" to studentId, "classroomId" to classId), idempotencyKey = key).expect(200)["status"].asText() shouldBe "IN"
        // 키 없이 다시 누르면 상태 전이 위반
        call(HttpMethod.POST, "/api/v1/attendance/check-in", teacherToken, instId,
            mapOf("studentId" to studentId, "classroomId" to classId)).expect(409)["code"].asText() shouldBe "INVALID_TRANSITION"

        // ── 16:58 하원 → 집 ──
        clock.set(monday, 16, 58)
        val out = call(HttpMethod.POST, "/api/v1/attendance/check-out", teacherToken, instId,
            mapOf("studentId" to studentId, "classroomId" to classId, "destinationId" to home["id"].asText())).expect(200)
        out["status"].asText() shouldBe "OUT"
        out["isEarlyLeave"].asBoolean() shouldBe false
        out["nextDestinationName"].asText() shouldBe "집"

        // ── 워커: Outbox → 푸시 2건 ──
        drainOutbox()
        push.sent shouldHaveSize 2
        push.sent[0].tokens shouldBe listOf("fcm-$run")
        push.sent[0].body shouldContain "등원했어요 (지각)"
        push.sent[1].body shouldBe "박하늘 학생이 하원했어요 → 집"
        push.sent[1].title shouldBe "[똑똑수학학원] 박하늘"

        // ── 학부모 통합 타임라인: 최신순 ──
        val timeline = call(HttpMethod.GET, "/api/v1/me/timeline", parentToken, null, null).expect(200)
        timeline shouldHaveSizeOf 2
        timeline[0]["type"].asText() shouldBe "CHECK_OUT"
        timeline[0]["destinationName"].asText() shouldBe "집"
        timeline[0]["institutionName"].asText() shouldBe "똑똑수학학원"
        timeline[1]["type"].asText() shouldBe "CHECK_IN"
        timeline[1]["isLate"].asBoolean() shouldBe true

        // ── 데일리 현황 ──
        val daily = call(HttpMethod.GET, "/api/v1/attendance/daily?classId=$classId&date=$monday", teacherToken, instId, null).expect(200)
        daily[0]["status"].asText() shouldBe "OUT"
    }

    @Test
    fun `권한 경계 - 담당 아닌 교사, 학부모, 타 기관`() {
        val owner = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
            mapOf("institutionName" to "A학원", "ownerName" to "원장A", "email" to "a-$run@ttok.dev", "password" to "Passw0rd!")).expect(201)
        val ownerToken = owner["accessToken"].asText()
        val instId = owner["user"]["memberships"][0]["institutionId"].asText()
        call(HttpMethod.POST, "/api/v1/staff", ownerToken, instId,
            mapOf("name" to "비담당", "email" to "other-$run@ttok.dev", "temporaryPassword" to "Temp1234!")).expect(201)
        val otherTeacher = call(HttpMethod.POST, "/api/v1/auth/login", null, null,
            mapOf("loginId" to "other-$run@ttok.dev", "password" to "Temp1234!")).expect(200)["accessToken"].asText()

        val classId = call(HttpMethod.POST, "/api/v1/classes", ownerToken, instId, mapOf(
            "name" to "중등반", "capacity" to 5, "days" to listOf("MONDAY"), "startTime" to "18:00", "endTime" to "20:00",
        )).expect(201)["id"].asText()
        val studentId = call(HttpMethod.POST, "/api/v1/students", ownerToken, instId, mapOf(
            "name" to "정바다", "birthDate" to "2013-07-07", "classroomId" to classId,
            "guardians" to listOf(mapOf("phone" to parentPhone)),
        )).expect(201)["id"].asText()

        // 담당 반이 아닌 교사 → 403
        call(HttpMethod.POST, "/api/v1/attendance/check-in", otherTeacher, instId,
            mapOf("studentId" to studentId, "classroomId" to classId)).expect(403)
        // 교사는 직원 계정을 만들 수 없다
        call(HttpMethod.POST, "/api/v1/staff", otherTeacher, instId,
            mapOf("name" to "x", "email" to "x-$run@ttok.dev", "temporaryPassword" to "Temp1234!")).expect(403)

        // 다른 기관 원장은 A학원 데이터에 접근 불가
        val stranger = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
            mapOf("institutionName" to "B학원", "ownerName" to "원장B", "email" to "b-$run@ttok.dev", "password" to "Passw0rd!")).expect(201)
        call(HttpMethod.GET, "/api/v1/students", stranger["accessToken"].asText(), instId, null).expect(403)

        // 토큰 없이 → 401, 잘못된 비밀번호 → 401
        call(HttpMethod.GET, "/api/v1/students", null, instId, null).expect(401)
        call(HttpMethod.POST, "/api/v1/auth/login", null, null, mapOf("loginId" to "a-$run@ttok.dev", "password" to "wrong-pass1")).expect(401)

        // refresh 토큰으로 API 호출 불가, refresh 엔드포인트로는 재발급 가능
        val refresh = owner["refreshToken"].asText()
        call(HttpMethod.GET, "/api/v1/classes", refresh, instId, null).expect(401)
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, null, mapOf("refreshToken" to refresh)).expect(200)["accessToken"].isTextual shouldBe true
    }

    // ── helpers ──
    private fun drainOutbox() { while (processOutbox.processBatch(50) > 0) Unit }

    private data class Res(val status: Int, val body: JsonNode)

    private fun Res.expect(code: Int): JsonNode {
        if (status != code) throw AssertionError("expected HTTP $code but was $status: $body")
        return body
    }

    private infix fun JsonNode.shouldHaveSizeOf(n: Int) = size() shouldBe n

    private fun call(method: HttpMethod, path: String, token: String?, instId: String?, body: Any?, idempotencyKey: String? = null): Res {
        val req = request(method, path).contentType(MediaType.APPLICATION_JSON)
        token?.let { req.header("Authorization", "Bearer $it") }
        instId?.let { req.header("X-Institution-Id", it) }
        idempotencyKey?.let { req.header("Idempotency-Key", it) }
        body?.let { req.content(json.writeValueAsString(it)) }
        val res = mvc.perform(req).andReturn().response
        val text = res.getContentAsString(Charsets.UTF_8)
        return Res(res.status, if (text.isBlank()) json.nullNode() else json.readTree(text))
    }
}
