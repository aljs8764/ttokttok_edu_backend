package com.ttokttok.domain.institution

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException

data class Institution(
    val id: InstitutionId,
    val name: String,
    val ownerName: String,
    /** 수업 시작 후 이 분(分)이 지나 등원하면 지각 */
    val lateThresholdMinutes: Int = 10,
    /** 수업 종료 이 분 이전에 하원하면 조퇴 */
    val earlyLeaveThresholdMinutes: Int = 10,
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "기관명은 필수입니다")
        if (lateThresholdMinutes !in 0..120 || earlyLeaveThresholdMinutes !in 0..120)
            throw InvalidInputException("INVALID_THRESHOLD", "지각·조퇴 기준은 0~120분입니다")
    }
}
