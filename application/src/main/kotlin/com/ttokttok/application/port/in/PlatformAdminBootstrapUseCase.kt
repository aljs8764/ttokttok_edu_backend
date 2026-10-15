package com.ttokttok.application.port.`in`

/**
 * 기동 시 운영사(슈퍼관리자) 계정 보장.
 * 운영사가 한 명도 없을 때만 동작한다 — 이메일 계정이 있으면 그 계정을 지정하고, 없으면 원장 가입과 같은 방식으로
 * (기관 + 계정 + OWNER 소속) 만든 뒤 지정한다. 운영사도 소속이 하나는 있어야 로그인·기관 선택이 되기 때문.
 */
interface PlatformAdminBootstrapUseCase {
    fun ensure(command: Command): Result
    data class Command(val email: String, val password: String, val name: String, val institutionName: String)
    enum class Result { ALREADY_EXISTS, GRANTED_EXISTING_USER, CREATED }
}
