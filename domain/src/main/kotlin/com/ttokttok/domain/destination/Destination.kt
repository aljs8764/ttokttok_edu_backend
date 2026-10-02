package com.ttokttok.domain.destination

import com.ttokttok.domain.common.DestinationId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException

enum class DestinationType { HOME, ACADEMY, SHUTTLE, ETC }

/** 하원 시 교사 앱에서 고르는 '다음 목적지' (SET-002). */
data class Destination(
    val id: DestinationId,
    val institutionId: InstitutionId,
    val name: String,
    val type: DestinationType,
    val sortOrder: Int = 0,
) {
    init {
        if (name.isBlank() || name.length > 50) throw InvalidInputException("INVALID_NAME", "목적지 이름은 1~50자입니다")
    }

    fun rename(name: String, type: DestinationType) = copy(name = name.trim(), type = type)
}
