package com.ttokttok.domain.user

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.MembershipId
import com.ttokttok.domain.common.PhoneNumber
import com.ttokttok.domain.common.UserId

/** 기관과 무관한 전역 계정. 한 사람이 여러 기관에 여러 역할로 소속될 수 있다. */
data class User(
    val id: UserId,
    val name: String,
    val email: String?,
    val phone: PhoneNumber?,
    val passwordHash: String,
    val mustChangePassword: Boolean = false,
) {
    init {
        if (email == null && phone == null) throw InvalidInputException("NO_LOGIN_ID", "이메일 또는 휴대폰 번호가 필요합니다")
    }

    /** AUTH-004 임시 비밀번호 발급 → 다음 로그인 때 변경 강제 / AUTH-005 변경 완료 */
    fun withPassword(hash: String, mustChange: Boolean) = copy(passwordHash = hash, mustChangePassword = mustChange)
}

enum class Role { OWNER, ADMIN, TEACHER, PARENT;
    val isStaff get() = this != PARENT
    val isManager get() = this == OWNER || this == ADMIN
}

data class Membership(
    val id: MembershipId,
    val userId: UserId,
    val institutionId: InstitutionId,
    val role: Role,
    val title: String? = null,
)
