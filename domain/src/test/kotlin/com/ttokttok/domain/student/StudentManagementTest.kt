package com.ttokttok.domain.student

import com.ttokttok.domain.classroom.Classroom
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class StudentManagementTest {
    private val student = Student(StudentId.new(), InstitutionId.new(), "박하늘", LocalDate.of(2017, 3, 2))
    private val today = LocalDate.of(2026, 10, 5)

    @Test
    fun `퇴원은 사유가 필수이고 이력이 남는다`() {
        shouldThrow<InvalidInputException> { student.changeStatus(StudentStatus.WITHDRAWN, null, today, null) }
        val t = student.changeStatus(StudentStatus.WITHDRAWN, WithdrawalReason.MOVING, today, "이사")
        t.student.status shouldBe StudentStatus.WITHDRAWN
        t.change.from shouldBe StudentStatus.ACTIVE
        t.change.reason shouldBe WithdrawalReason.MOVING
    }

    @Test
    fun `휴원은 사유 없이 가능하고 같은 상태로는 바꿀 수 없다`() {
        student.changeStatus(StudentStatus.PAUSED, null, today, null).student.status shouldBe StudentStatus.PAUSED
        shouldThrow<ConflictException> { student.changeStatus(StudentStatus.ACTIVE, null, today, null) }
    }

    @Test
    fun `기본정보 수정은 지정한 값만 바꾼다`() {
        val u = student.updateInfo(null, null, "초4", "알레르기 없음")
        u.name shouldBe "박하늘"
        u.grade shouldBe "초4"
        u.memo shouldBe "알레르기 없음"
    }

    private val classroom = Classroom(
        ClassroomId.new(), InstitutionId.new(), "초등 3반", 10, setOf(DayOfWeek.MONDAY), LocalTime.of(15, 0), LocalTime.of(17, 0),
    )

    @Test
    fun `반 정원은 현재 인원보다 줄일 수 없다`() {
        shouldThrow<InvalidInputException> { classroom.update(null, 4, null, null, null, currentHeadcount = 5) }
        classroom.update("초등 3-1반", 6, null, null, null, currentHeadcount = 5).capacity shouldBe 6
    }

    @Test
    fun `소속 학생이 있으면 반을 삭제할 수 없다`() {
        shouldThrow<ConflictException> { classroom.ensureDeletable(1) }
        classroom.ensureDeletable(0)
    }
}
