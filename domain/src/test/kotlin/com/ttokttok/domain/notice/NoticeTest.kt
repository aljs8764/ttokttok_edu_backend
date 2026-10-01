package com.ttokttok.domain.notice

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NoticeTest {
    private val now = Instant.parse("2026-10-05T06:00:00Z")
    private val inst = InstitutionId.new()
    private val teacher = UserId.new()
    private val cls = ClassroomId.new()

    private fun note(sendAt: Instant? = null) = Notice.compose(
        inst, teacher, NoticeKind.NOTE, " 내일 준비물 ", "색연필을 챙겨 주세요", false, listOf(NoticeTarget.classroom(cls)), sendAt, now,
    )

    @Test
    fun `즉시 발송은 지금 시각으로 예약된 상태로 만들어져 바로 발송 가능하다`() {
        val n = note()
        n.title shouldBe "내일 준비물"
        n.isDue(now) shouldBe true
        val sent = n.publish(now)
        sent.status shouldBe NoticeStatus.SENT
        sent.sentAt shouldBe now
        shouldThrow<ConflictException> { sent.cancel() }
    }

    @Test
    fun `예약은 미래 30일 이내만, 예약 상태에서만 수정·취소`() {
        shouldThrow<InvalidInputException> { note(now.minusSeconds(1)) }
        shouldThrow<InvalidInputException> { note(now.plus(Duration.ofDays(31))) }
        val scheduled = note(now.plus(Duration.ofHours(2)))
        scheduled.isDue(now) shouldBe false
        scheduled.isDue(now.plus(Duration.ofHours(2))) shouldBe true
        scheduled.edit("변경", "본문", true, listOf(NoticeTarget.classroom(cls)), now.plus(Duration.ofHours(3)), now).pinned shouldBe true
        scheduled.cancel().status shouldBe NoticeStatus.CANCELED
    }

    @Test
    fun `전체 공지는 기관 전체 대상만`() {
        shouldThrow<InvalidInputException> {
            Notice.compose(inst, teacher, NoticeKind.ANNOUNCEMENT, "휴원 안내", "추석 휴원", false, listOf(NoticeTarget.classroom(cls)), null, now)
        }
        shouldThrow<InvalidInputException> {
            Notice.compose(inst, teacher, NoticeKind.NOTE, "t", "b", false, listOf(NoticeTarget.all(), NoticeTarget.classroom(cls)), null, now)
        }
    }

    @Test
    fun `미열람 재발송은 30분 쿨타임`() {
        val sent = note().publish(now)
        val first = sent.markResent(now.plus(Duration.ofMinutes(5)))
        shouldThrow<ConflictException> { first.markResent(now.plus(Duration.ofMinutes(20))) }
        first.markResent(now.plus(Duration.ofMinutes(35))).lastResentAt shouldBe now.plus(Duration.ofMinutes(35))
    }

    @Test
    fun `열람률은 학생 기준 - 보호자 중 한 명만 읽어도 열람`() {
        val id = NoticeId.new()
        val a = StudentId.new()
        val b = StudentId.new()
        val c = StudentId.new()
        val mom = UserId.new()
        val dad = UserId.new()
        val rows = listOf(
            NoticeRecipient(id, a, mom, readAt = now),
            NoticeRecipient(id, a, dad),
            NoticeRecipient(id, b, UserId.new()),
            NoticeRecipient(id, c, null), // 앱 미연결
        )
        val s = NoticeReadSummary.of(rows)
        s.targetStudents shouldBe 3
        s.readStudents shouldBe 1
        s.unreadStudents shouldBe 2
        val unread = NoticeReadSummary.unreadGuardians(rows)
        unread.keys shouldBe setOf(b, c)
        unread.getValue(c) shouldBe emptyList()
    }
}
