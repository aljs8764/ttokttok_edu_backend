package com.ttokttok.domain.attendance

import com.ttokttok.domain.common.AttendanceDayId
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class AttendanceManagementTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 5)
    private val rule = ScheduleRule(LocalTime.of(15, 0), LocalTime.of(17, 0), 10, 10, zone)
    private val admin = UserId.new()
    private val inst = InstitutionId.new()
    private val cls = ClassroomId.new()

    private fun day(student: StudentId = StudentId.new(), d: LocalDate = date) = AttendanceDay(AttendanceDayId.new(), inst, student, cls, d)
    private fun at(h: Int, m: Int, d: LocalDate = date) = d.atTime(h, m).atZone(zone).toInstant()

    // ── ATT-002 수동 변경 ──

    @Test
    fun `하원 후 재등원은 수동 변경으로만 가능하고 하원 정보가 지워진다`() {
        val out = day().checkIn(rule, at(15, 0), admin, AttendanceSource.TEACHER_APP).day
            .checkOut(rule, at(17, 0), DestinationId.new(), admin, AttendanceSource.TEACHER_APP).day
        shouldThrow<ConflictException> { out.checkIn(rule, at(17, 5), admin, AttendanceSource.TEACHER_APP) }

        val t = out.overrideStatus(AttendanceStatus.IN, "하원 버튼 오터치", at(17, 6), admin)
        t.day.status shouldBe AttendanceStatus.IN
        t.day.checkInAt shouldBe at(15, 0)
        t.day.checkOutAt shouldBe null
        t.day.nextDestinationId shouldBe null
        t.event.type shouldBe AttendanceEventType.STATUS_CHANGE
        t.event.fromStatus shouldBe AttendanceStatus.OUT
        t.event.reason shouldBe "하원 버튼 오터치"
    }

    @Test
    fun `결석으로 바꾸면 사유가 결석 사유로 저장되고 지각 플래그는 지워진다`() {
        val late = day().checkIn(rule, at(15, 30), admin, AttendanceSource.TEACHER_APP).day
        late.isLate shouldBe true
        val absent = late.overrideStatus(AttendanceStatus.ABSENT, "  감기  ", at(16, 0), admin).day
        absent.isLate shouldBe false
        absent.checkInAt shouldBe null
        absent.absenceReason shouldBe "감기"
        absent.monthlyMark shouldBe MonthlyMark.X
    }

    @Test
    fun `지각 플래그만 정정할 수 있다`() {
        val late = day().checkIn(rule, at(15, 30), admin, AttendanceSource.TEACHER_APP).day
        val fixed = late.overrideStatus(AttendanceStatus.IN, "버스 지연, 원장 확인", at(16, 0), admin, isLate = false).day
        fixed.isLate shouldBe false
        fixed.monthlyMark shouldBe MonthlyMark.O
    }

    @Test
    fun `같은 값으로 바꾸거나 사유가 없으면 거절`() {
        val inDay = day().checkIn(rule, at(15, 0), admin, AttendanceSource.TEACHER_APP).day
        shouldThrow<ConflictException> { inDay.overrideStatus(AttendanceStatus.IN, "그대로", at(16, 0), admin) }
        shouldThrow<InvalidInputException> { inDay.overrideStatus(AttendanceStatus.ABSENT, "   ", at(16, 0), admin) }
        shouldThrow<InvalidInputException> { inDay.overrideStatus(AttendanceStatus.SCHEDULED, "되돌리기", at(16, 0), admin) }
    }

    @Test
    fun `결석 사유는 결석일 때만 등록`() {
        shouldThrow<ConflictException> { day().withAbsenceReason("감기") }
        val absent = day().closeAsAbsent(at(23, 50), admin)!!.day
        absent.withAbsenceReason("병원 진료").absenceReason shouldBe "병원 진료"
        absent.withAbsenceReason(" ").absenceReason shouldBe null
    }

    // ── ATT-003 월간 출석부 ──

    @Test
    fun `월간 출석부는 O 세모 X 를 세고 비고에 결석 사유를 모은다`() {
        val kim = StudentId.new()
        val d1 = LocalDate.of(2026, 10, 1)
        val d2 = LocalDate.of(2026, 10, 2)
        val d3 = LocalDate.of(2026, 10, 7)
        val days = listOf(
            day(kim, d1).checkIn(rule, at(15, 0, d1), admin, AttendanceSource.TEACHER_APP).day,
            day(kim, d2).checkIn(rule, at(15, 20, d2), admin, AttendanceSource.TEACHER_APP).day, // 지각
            day(kim, d3).overrideStatus(AttendanceStatus.ABSENT, "감기", at(14, 0, d3), admin).day,
            day(kim, LocalDate.of(2026, 9, 30)).overrideStatus(AttendanceStatus.ABSENT, "지난달", at(14, 0), admin).day,
        )
        val rows = MonthlyAttendanceSheet.build(YearMonth.of(2026, 10), mapOf(kim to "김하늘", StudentId.new() to "가나다"), days)
        rows.size shouldBe 2
        rows[0].studentName shouldBe "가나다"
        val r = rows[1]
        r.cells.size shouldBe 3
        r.presentDays shouldBe 1
        r.partialDays shouldBe 1
        r.absentDays shouldBe 1
        r.attendedDays shouldBe 2
        r.cells.getValue(d2).mark.symbol shouldBe "△"
        r.remarks shouldBe "10/7 감기"
    }

    // ── DASH-001·005 KPI ──

    @Test
    fun `등원율 분모에서 사전 연락 결석을 빼고 미등원은 지각 기준 경과 후부터 센다`() {
        val now = at(15, 20)
        val inOnTime = day().checkIn(rule, at(15, 0), admin, AttendanceSource.TEACHER_APP).day
        val inLate = day().checkIn(rule, at(15, 15), admin, AttendanceSource.TEACHER_APP).day
        val excused = day().overrideStatus(AttendanceStatus.ABSENT, "가족여행", at(9, 0), admin).day
        val waiting = day()
        val laterClass = ScheduleRule(LocalTime.of(16, 0), LocalTime.of(18, 0), 10, 10, zone)
        val kpi = TodayKpi.compute(
            listOf(inOnTime to rule, inLate to rule, excused to rule, waiting to rule, day() to laterClass),
            now,
        )
        kpi.total shouldBe 5
        kpi.present shouldBe 2
        kpi.excusedAbsent shouldBe 1
        kpi.attendanceRate shouldBe 50.0 // 2 / (5 - 1)
        kpi.late shouldBe 1
        kpi.notArrived.size shouldBe 1 // 16시 반은 아직 미등원 아님
        kpi.notArrived[0].id shouldBe waiting.id
    }

    @Test
    fun `출결 행이 없으면 등원율은 null`() {
        TodayKpi.compute(emptyList(), at(15, 0)).attendanceRate shouldBe null
    }
}
