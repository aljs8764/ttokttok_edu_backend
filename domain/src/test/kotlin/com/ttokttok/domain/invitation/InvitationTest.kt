package com.ttokttok.domain.invitation

import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class InvitationTest {
    private val now = Instant.parse("2026-10-05T01:00:00Z")
    private val inv = Invitation.issue(InstitutionId.new(), PhoneNumber.of("010-1111-2222"), UserId.new(), now)

    @Test
    fun `초대 링크는 7일 후 만료된다`() {
        inv.ensureUsable(now.plus(Duration.ofDays(6)))
        shouldThrow<ConflictException> { inv.ensureUsable(now.plus(Duration.ofDays(7))) }
    }

    @Test
    fun `토큰은 22자이고 매번 다르다`() {
        inv.token.length shouldBe 22
        (Invitation.newToken() == Invitation.newToken()) shouldBe false
    }

    @Test
    fun `가입 요청은 한 번만 승인·거절할 수 있다`() {
        val req = JoinRequest(
            JoinRequestId.new(), inv.institutionId, inv.id, "박하늘", LocalDate.of(2017, 3, 2), "최엄마", inv.phone, "모", now,
        )
        val approved = req.approve(ClassroomId.new(), UserId.new(), now)
        approved.status shouldBe JoinRequestStatus.APPROVED
        shouldThrow<ConflictException> { approved.reject("중복", UserId.new(), now) }
    }
}
