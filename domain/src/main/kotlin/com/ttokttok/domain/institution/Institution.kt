package com.ttokttok.domain.institution

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.file.FileId

data class Institution(
    val id: InstitutionId,
    val name: String,
    val ownerName: String,
    /** 수업 시작 후 이 분(分)이 지나 등원하면 지각 */
    val lateThresholdMinutes: Int = 10,
    /** 수업 종료 이 분 이전에 하원하면 조퇴 */
    val earlyLeaveThresholdMinutes: Int = 10,
    val address: String? = null,
    val phone: String? = null,
    /** 출석부·안내문에 쓰는 로고·직인 이미지 (SET-001) */
    val logoFileId: FileId? = null,
    val sealFileId: FileId? = null,
) {
    init {
        if (name.isBlank()) throw InvalidInputException("INVALID_NAME", "기관명은 필수입니다")
        if (lateThresholdMinutes !in 0..120 || earlyLeaveThresholdMinutes !in 0..120)
            throw InvalidInputException("INVALID_THRESHOLD", "지각·조퇴 기준은 0~120분입니다")
        if ((address?.length ?: 0) > 200) throw InvalidInputException("INVALID_ADDRESS", "주소는 200자 이내입니다")
        if (phone != null && !phone.matches(Regex("^[0-9-]{8,15}$"))) throw InvalidInputException("INVALID_PHONE", "대표 전화번호 형식이 올바르지 않습니다")
    }
}
