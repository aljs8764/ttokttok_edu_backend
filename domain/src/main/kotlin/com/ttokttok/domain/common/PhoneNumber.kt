package com.ttokttok.domain.common

/** 휴대폰 번호 값 객체. 숫자만 보관(01012345678). */
@JvmInline
value class PhoneNumber private constructor(val digits: String) {
    val last4: String get() = digits.takeLast(4)
    val masked: String get() = "${digits.take(3)}-****-$last4"

    companion object {
        private val PATTERN = Regex("^01[016789]\\d{7,8}$")

        fun of(raw: String): PhoneNumber {
            val digits = raw.filter { it.isDigit() }
            if (!PATTERN.matches(digits)) throw InvalidInputException("INVALID_PHONE", "휴대폰 번호 형식이 올바르지 않습니다: $raw")
            return PhoneNumber(digits)
        }
    }
}
