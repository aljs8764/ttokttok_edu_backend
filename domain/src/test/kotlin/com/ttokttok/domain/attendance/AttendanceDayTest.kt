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
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AttendanceDayTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 5)
    private val rule = ScheduleRule(LocalTime.of(15, 0), LocalTime.of(17, 0), 10, 10, zone)
    private val teacher = UserId.new()
    private val home = DestinationId.new()

    private fun day() = AttendanceDay(AttendanceDayId.new(), InstitutionId.new(), StudentId.new(), ClassroomId.new(), date)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant()

    @Test
    fun `정시 등원은 지각이 아니다`() {
        val t = day().checkIn(rule, at(15, 10), teacher, AttendanceSource.TEACHER_APP)
        t.day.status shouldBe AttendanceStatus.IN
        t.day.isLate shouldBe false
        t.event.fromStatus shouldBe AttendanceStatus.SCHEDULED
        t.event.type shouldBe AttendanceEventType.CHECK_IN
    }

    @Test
    fun `지각 기준 이후 등원은 지각 플래그가 선다`() {
        val t = day().checkIn(rule, at(15, 11), teacher, AttendanceSource.TEACHER_APP)
        t.day.isLate shouldBe true
        t.day.monthlyMark shouldBe MonthlyMark.TRIANGLE
    }

    @Test
    fun `하원은 목적지를 기록하고 조퇴를 판정한다`() {
        val inDay = day().checkIn(rule, at(15, 0), teacher, AttendanceSource.TEACHER_APP).day
        val normal = inDay.checkOut(rule, at(16, 55), home, teacher, AttendanceSource.TEACHER_APP)
        normal.day.status shouldBe AttendanceStatus.OUT
        normal.day.nextDestinationId shouldBe home
        normal.day.isEarlyLeave shouldBe false
        normal.day.monthlyMark shouldBe MonthlyMark.O

        val early = inDay.checkOut(rule, at(16, 49), home, teacher, AttendanceSource.TEACHER_APP)
        early.day.isEarlyLeave shouldBe true
    }

    @Test
    fun `등원 없이 하원할 수 없다`() {
        shouldThrow<ConflictException> { day().checkOut(rule, at(17, 0), home, teacher, AttendanceSource.TEACHER_APP) }
    }

    @Test
    fun `두 번 등원할 수 없다`() {
        val inDay = day().checkIn(rule, at(15, 0), teacher, AttendanceSource.TEACHER_APP).day
        shouldThrow<ConflictException> { inDay.checkIn(rule, at(15, 1), teacher, AttendanceSource.TEACHER_APP) }
    }

    @Test
    fun `수동 변경은 사유가 필수다`() {
        shouldThrow<InvalidInputException> { day().overrideStatus(AttendanceStatus.ABSENT, " ", at(16, 0), teacher) }
        val t = day().overrideStatus(AttendanceStatus.ABSENT, "병결", at(16, 0), teacher)
        t.day.monthlyMark shouldBe MonthlyMark.X
        t.event.reason shouldBe "병결"
    }

    @Test
    fun `자동 결석 확정은 예정 상태에만 적용된다`() {
        day().closeAsAbsent(at(23, 50), teacher) shouldNotBe null
        val inDay = day().checkIn(rule, at(15, 0), teacher, AttendanceSource.TEACHER_APP).day
        inDay.closeAsAbsent(at(23, 50), teacher) shouldBe null
    }
}
