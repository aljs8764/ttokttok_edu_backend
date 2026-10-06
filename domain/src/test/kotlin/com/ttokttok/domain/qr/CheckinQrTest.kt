package com.ttokttok.domain.qr

import com.ttokttok.domain.attendance.AttendanceStatus
import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class CheckinQrTest {
    private val inst = InstitutionId.new()
    private val now = Instant.parse("2026-10-06T05:00:00Z")

    @Test
    fun `재발급하면 토큰이 바뀐다`() {
        val qr = CheckinQr.issue(inst, "1층 입구", now)
        val rotated = qr.rotate(now)
        rotated.token shouldNotBe qr.token
        rotated.id shouldBe qr.id
    }

    @Test
    fun `반경 판정은 GPS 정확도를 최대 100m 까지 봐준다`() {
        // 서울시청 근처
        val fence = Geofence(37.5665, 126.9780, 150)
        fence.contains(37.5665, 126.9780, null) shouldBe true
        // 약 220m 북쪽
        val lat = 37.5685
        fence.contains(lat, 126.9780, 10.0) shouldBe false
        fence.contains(lat, 126.9780, 80.0) shouldBe true
        // 정확도 5km 라고 보고해도 100m 만 인정
        fence.contains(37.5765, 126.9780, 5000.0) shouldBe false
    }

    @Test
    fun `연결 코드는 한 번만 쓸 수 있고 10분 뒤 만료`() {
        val code = StudentLinkCode.issue(StudentId.new(), inst, UserId.new(), now)
        code.code.length shouldBe StudentLinkCode.LENGTH
        val used = code.use(now.plusSeconds(60))
        shouldThrow<ConflictException> { used.use(now.plusSeconds(61)) }
        shouldThrow<ConflictException> { code.use(now.plus(StudentLinkCode.VALIDITY).plusSeconds(1)) }
        StudentLinkCode.normalize("ab cd-23 45") shouldBe "ABCD2345"
    }

    private fun cls(start: String, end: String) =
        Classroom(ClassroomId.new(), inst, "반$start", 20, DayOfWeek.entries.toSet(), LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun `반 고르기 - 등원 중인 반이 있으면 하원 대상`() {
        val a = cls("14:00", "16:00")
        val b = cls("16:30", "18:00")
        val picked = QrClassPicker.pick(
            listOf(QrClassPicker.Candidate(a, AttendanceStatus.IN), QrClassPicker.Candidate(b, AttendanceStatus.SCHEDULED)),
            LocalTime.parse("16:20"),
        )
        picked?.classroom shouldBe a
    }

    @Test
    fun `반 고르기 - 시작 60분 전부터 등원, 다 끝났으면 없음`() {
        val a = cls("14:00", "16:00")
        val b = cls("16:30", "18:00")
        val list = listOf(QrClassPicker.Candidate(a, AttendanceStatus.OUT), QrClassPicker.Candidate(b, AttendanceStatus.SCHEDULED))
        QrClassPicker.pick(list, LocalTime.parse("15:40"))?.classroom shouldBe b
        QrClassPicker.pick(list, LocalTime.parse("13:00"))?.classroom shouldBe b // 다음 수업으로 일찍 등원
        QrClassPicker.pick(list, LocalTime.parse("18:30")) shouldBe null
    }
}
