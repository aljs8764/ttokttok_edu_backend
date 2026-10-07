package com.ttokttok.domain.child

import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.Uuid7
import java.time.LocalDate
import java.util.UUID

// 다기관 아이 (스펙 7-8)

@JvmInline value class ChildId(val value: UUID) { companion object { fun new() = ChildId(Uuid7.next()) } }

/**
 * 가족이 관리하는 "아이". 기관이 관리하는 원생(student)은 기관마다 따로 있고 child_id 로 이 아이를 가리킨다.
 * 한 아이 = 여러 학원·학교의 원생. 보호자 계정은 child_guardian 으로 연결 (엄마·아빠 모두).
 *
 * 원생이 처음 보호자 계정과 연결될 때 원생 정보로 아이를 1:1 로 만들고,
 * 같은 아이인지 합치는 것은 보호자가 앱에서 확인한다 (서버가 이름·생일로 자동으로 합치지 않음 — 오병합 방지).
 */
data class Child(
    val id: ChildId,
    val name: String,
    val birthDate: LocalDate?,
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "아이 이름은 필수입니다")
        if (name.length > 50) throw InvalidInputException("INVALID_NAME", "아이 이름은 50자 이내입니다")
    }

    fun rename(name: String) = copy(name = name.trim())

    /** 합치기 후보: 같은 보호자의 서로 다른 아이 중 이름(공백 무시)과 생일이 같은 경우 */
    fun looksSameAs(other: Child): Boolean =
        id != other.id && birthDate != null && birthDate == other.birthDate &&
            name.replace(" ", "") == other.name.replace(" ", "")
}
