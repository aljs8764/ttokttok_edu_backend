package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.institution.InstitutionType
import com.ttokttok.domain.user.Role

data class AuthenticatedUser(
    val userId: UserId,
    val name: String,
    val mustChangePassword: Boolean,
    val memberships: List<MembershipView>,
)

data class MembershipView(val institutionId: InstitutionId, val institutionName: String, val role: Role)

/** 원장 가입 = 기관 + 계정 + OWNER 소속 생성 (ONB-001 최소형) */
interface SignUpInstitutionUseCase {
    fun signUp(command: Command): AuthenticatedUser
    data class Command(
        val institutionName: String, val ownerName: String, val email: String, val password: String,
        val type: InstitutionType = InstitutionType.ACADEMY,
    )
}

/** 학부모 가입 (AUTH-003). 같은 번호로 등록된 보호자 매핑을 자동 연결한다. */
interface RegisterParentUseCase {
    fun register(command: Command): AuthenticatedUser
    data class Command(val name: String, val phone: String, val password: String)
}

/** 로그인 (AUTH-001~003). loginId = 이메일 또는 휴대폰 번호 */
interface LoginUseCase {
    fun login(command: Command): AuthenticatedUser
    data class Command(val loginId: String, val password: String)
}

/** 토큰 재발급 시 계정·소속을 다시 읽는다 */
interface LoadAuthenticatedUserQuery {
    fun load(userId: UserId): AuthenticatedUser
}

/** 직원 계정 생성 (STF-002 직접 생성형) */
interface CreateStaffUseCase {
    fun create(command: Command): StaffView
    data class Command(
        val actor: UserId, val institutionId: InstitutionId,
        val name: String, val email: String, val temporaryPassword: String, val role: Role, val title: String?,
    )
    data class StaffView(val userId: UserId, val name: String, val email: String, val role: Role)
}
