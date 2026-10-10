package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId

/** SAAS-001: 정지·승인 대기 기관도 볼 수 있는 자기 기관의 서비스 상태 (웹 상단 안내용) */
data class InstitutionServiceStatusView(val status: ServiceStatus, val reason: String?, val approvalRequired: Boolean)

interface InstitutionServiceStatusQuery {
    fun status(actor: UserId, institutionId: InstitutionId): InstitutionServiceStatusView
}
