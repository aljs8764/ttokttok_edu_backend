package com.ttokttok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ttokttok.application.port.`in`.DailyAttendanceBatchUseCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.time.LocalDate
import java.util.UUID

/**
 * S5–6 출결 관리·대시보드 인수 테스트
 * ATT-001 데일리 리포트 · ATT-002 수동 변경 · ATT-003 월간 출석부 · ATT-004 엑셀 · ATT-005~006 일괄
 * DASH-001 KPI · DASH-002 타임라인 · DASH-005 결석/지각 위젯
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceManagementIntegrationTest {

    @TestConfiguration
    class ClockConfig {
        @Bean @Primary
        fun fixedClock() = CoreLoopIntegrationTest.MutableClock()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var clock: CoreLoopIntegrationTest.MutableClock
    @Autowired lateinit var batch: DailyAttendanceBatchUseCase
    @Autowired lateinit var jdbc: JdbcTemplate

    private val monday = LocalDate.of(2026, 10, 5)
    private val run = UUID.randomUUID().toString().take(8)
    private fun phone() = "010" + (10_000_000 + (Math.random() * 89_999_999).toInt())

    @Test
    fun `일괄 등원 → 대시보드 KPI → 수동 결석 처리 → 리포트·월간 출석부·엑셀`() {
        clock.set(monday, 14, 0)
        // ── 준비: 원장·교사·반·목적지·원생 2명 ──
        val owner = call(HttpMethod.POST, "/api/v1/auth/institutions", null, null,
            mapOf("institutionName" to "똑똑영어", "ownerName" to "정원장", "email" to "att-owner-$run@ttok.dev", "password" to "Passw0rd!"))
            .expect(201)
        val ownerToken = owner["accessToken"].asText()
        val instId = owner["user"]["memberships"][0]["institutionId"].asText()
        val teacherId = call(HttpMethod.POST, "/api/v1/staff", ownerToken, instId,
            mapOf("name" to "이선생", "email" to "att-teacher-$run@ttok.dev", "temporaryPassword" to "Temp1234!", "role" to "TEACHER"))
            .expect(201)["userId"].asText()
        val teacherToken = call(HttpMethod.POST, "/api/v1/auth/login", null, null,
            mapOf("loginId" to "att-teacher-$run@ttok.dev", "password" to "Temp1234!")).expect(200)["accessToken"].asText()
        val classId = call(HttpMethod.POST, "/api/v1/classes", ownerToken, instId, mapOf(
            "name" to "중등 A반", "capacity" to 10, "days" to listOf("MONDAY", "WEDNESDAY", "FRIDAY"),
            "startTime" to "15:00", "endTime" to "17:00", "teacherIds" to listOf(teacherId),
        )).expect(201)["id"].asText()
        val a = student(ownerToken, instId, classId, "가나영")
        val b = student(ownerToken, instId, classId, "나다현")
        batch.generateScheduled(monday)

        // ── ATT-005~006 오프라인 큐 일괄: 정상 · 같은 키 재전송 · 목적지 누락 ──
        clock.set(monday, 15, 5)
        val bulk = call(HttpMethod.POST, "/api/v1/attendance/bulk", teacherToken, instId, mapOf("items" to listOf(
            mapOf("type" to "CHECK_IN", "studentId" to a, "classroomId" to classId, "clientAt" to at(15, 0), "idempotencyKey" to "q-$run-1"),
            mapOf("type" to "CHECK_IN", "studentId" to a, "classroomId" to classId, "clientAt" to at(15, 1), "idempotencyKey" to "q-$run-1"),
            mapOf("type" to "CHECK_OUT", "studentId" to a, "classroomId" to classId, "clientAt" to at(15, 2), "idempotencyKey" to "q-$run-2"),
        ))).expect(200)
        bulk[0]["ok"].asBoolean() shouldBe true
        bulk[0]["attendance"]["checkInAt"].asText() shouldBe at(15, 0)
        bulk[1]["ok"].asBoolean() shouldBe true // 멱등 재전송 → 기존 결과
        bulk[2]["ok"].asBoolean() shouldBe false
        bulk[2]["errorCode"].asText() shouldBe "DESTINATION_REQUIRED"

        // ── DASH-001·005: 15:30, B는 지각 기준(15:10) 경과 → 미등원 ──
        clock.set(monday, 15, 30)
        var today = call(HttpMethod.GET, "/api/v1/dashboard/today", ownerToken, instId, null).expect(200)
        today["kpi"]["total"].asInt() shouldBe 2
        today["kpi"]["present"].asInt() shouldBe 1
        today["kpi"]["notArrived"].asInt() shouldBe 1
        today["kpi"]["attendanceRate"].asDouble() shouldBe 50.0
        today["widgets"]["notArrived"][0]["studentName"].asText() shouldBe "나다현"
        today["widgets"]["notArrived"][0]["minutesLate"].asInt() shouldBe 30

        // ── ATT-002 수동 변경: 사유 없으면 400, 있으면 결석 + 사유 ──
        val report = call(HttpMethod.GET, "/api/v1/attendance/report?date=$monday", teacherToken, instId, null).expect(200)
        val bDayId = report["classes"][0]["rows"].first { it["studentId"].asText() == b }["dayId"].asText()
        call(HttpMethod.PATCH, "/api/v1/attendance/$bDayId/status", teacherToken, instId, mapOf("status" to "ABSENT", "reason" to ""))
            .expect(400)
        val absent = call(HttpMethod.PATCH, "/api/v1/attendance/$bDayId/status", teacherToken, instId,
            mapOf("status" to "ABSENT", "reason" to "감기 (어머니 전화)", "source" to "TEACHER_APP")).expect(200)
        absent["status"].asText() shouldBe "ABSENT"
        absent["absenceReason"].asText() shouldBe "감기 (어머니 전화)"

        // 사유 있는 결석은 등원율 분모에서 빠진다 → 1 / (2 - 1) = 100%
        today = call(HttpMethod.GET, "/api/v1/dashboard/today", ownerToken, instId, null).expect(200)
        today["kpi"]["attendanceRate"].asDouble() shouldBe 100.0
        today["kpi"]["excusedAbsent"].asInt() shouldBe 1
        today["widgets"]["absent"][0]["absenceReason"].asText() shouldBe "감기 (어머니 전화)"

        // 결석이 아닌 날엔 결석 사유를 달 수 없다
        val aDayId = report["classes"][0]["rows"].first { it["studentId"].asText() == a }["dayId"].asText()
        call(HttpMethod.PATCH, "/api/v1/attendance/$aDayId/absence-reason", ownerToken, instId, mapOf("reason" to "x")).expect(409)

        // ── DASH-002 타임라인: 최신순, 행위자 이름 ──
        val timeline = call(HttpMethod.GET, "/api/v1/dashboard/timeline", ownerToken, instId, null).expect(200)
        timeline.size() shouldBe 2
        timeline[0]["toStatus"].asText() shouldBe "ABSENT"
        timeline[0]["actorName"].asText() shouldBe "이선생"
        timeline[0]["source"].asText() shouldBe "TEACHER_APP"
        timeline[1]["type"].asText() shouldBe "CHECK_IN"
        timeline[1]["classroomName"].asText() shouldBe "중등 A반"

        // ── ATT-001 데일리 리포트 반 요약 ──
        val counts = call(HttpMethod.GET, "/api/v1/attendance/report?date=$monday&classId=$classId", ownerToken, instId, null)
            .expect(200)["classes"][0]["counts"]
        counts["present"].asInt() shouldBe 1
        counts["absent"].asInt() shouldBe 1

        // ── ATT-003 월간 출석부: 2026-10 월·수·금 = 13일 ──
        val monthly = call(HttpMethod.GET, "/api/v1/attendance/monthly?month=2026-10&classId=$classId", teacherToken, instId, null).expect(200)
        monthly["classDays"].size() shouldBe 13
        val rowA = monthly["rows"].first { it["studentId"].asText() == a }
        val rowB = monthly["rows"].first { it["studentId"].asText() == b }
        rowA["present"].asInt() shouldBe 1
        rowA["cells"][0]["mark"].asText() shouldBe "O"
        rowB["absent"].asInt() shouldBe 1
        rowB["remarks"].asText() shouldBe "10/5 감기 (어머니 전화)"
        call(HttpMethod.GET, "/api/v1/attendance/monthly?month=2026-13&classId=$classId", teacherToken, instId, null).expect(400)

        // ── ATT-004 엑셀: 교사는 403, 관리자는 평문(zip) / 비밀번호 시 암호화(OLE2) ──
        call(HttpMethod.POST, "/api/v1/attendance/export", teacherToken, instId, mapOf("classId" to classId, "month" to "2026-10")).expect(403)
        val plain = download(ownerToken, instId, mapOf("classId" to classId, "month" to "2026-10"))
        (plain[0] == 'P'.code.toByte() && plain[1] == 'K'.code.toByte()) shouldBe true
        val encrypted = download(ownerToken, instId, mapOf("classId" to classId, "month" to "2026-10", "password" to "1234"))
        (encrypted[0] == 0xD0.toByte() && encrypted[1] == 0xCF.toByte()) shouldBe true
        jdbc.queryForObject(
            "select count(*) from audit_log where institution_id = ?::uuid and action = 'ATTENDANCE_EXPORT'", Int::class.java, instId,
        ) shouldBe 2
        jdbc.queryForObject(
            "select count(*) from audit_log where institution_id = ?::uuid and action = 'ATTENDANCE_STATUS_CHANGE'", Int::class.java, instId,
        ) shouldBe 1

        // ── 23:50 마감 + 일별 집계 ──
        clock.set(monday, 23, 50)
        batch.closeUnprocessed(monday)
        val stat = jdbc.queryForMap("select scheduled, present, absent from daily_attendance_stat where classroom_id = ?::uuid and date = ?", classId, monday)
        stat["scheduled"] shouldBe 2
        stat["present"] shouldBe 1
        stat["absent"] shouldBe 1
    }

    private fun student(token: String, instId: String, classId: String, name: String) =
        call(HttpMethod.POST, "/api/v1/students", token, instId, mapOf(
            "name" to name, "birthDate" to "2013-05-01", "classroomId" to classId,
            "guardians" to listOf(mapOf("phone" to phone(), "relation" to "모", "isPrimary" to true)),
        )).expect(201)["id"].asText()

    private fun at(h: Int, m: Int) = monday.atTime(h, m).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toString()

    private fun download(token: String, instId: String, body: Any): ByteArray {
        val res = mvc.perform(
            request(HttpMethod.POST, "/api/v1/attendance/export").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token").header("X-Institution-Id", instId)
                .content(json.writeValueAsString(body)),
        ).andReturn().response
        if (res.status != 200) throw AssertionError("export failed ${res.status}: ${res.contentAsString}")
        res.getHeader("Content-Disposition")!!.contains("attachment") shouldBe true
        return res.contentAsByteArray
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
