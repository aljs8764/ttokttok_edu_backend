package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.PasswordHasherPort
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

/** 비밀번호 해시 — argon2id. api·worker 모두 같은 구현을 쓰도록 공통 아웃바운드 모듈에 둔다. */
@Component
class Argon2PasswordHasher : PasswordHasherPort {
    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    override fun hash(raw: String): String = encoder.encode(raw)
    override fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}
