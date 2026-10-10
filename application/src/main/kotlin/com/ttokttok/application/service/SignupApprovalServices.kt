package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.InstitutionServiceStatusQuery
import com.ttokttok.application.port.`in`.InstitutionServiceStatusView
import com.ttokttok.application.port.`in`.ServiceStatus
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.PlatformInstitutionPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * SAAS-001 가입 승인. `ttok.platform.require-approval=true` 이면 새로 가입한 기관을 '승인 대기'로 두고,
 * 운영사가 운영 콘솔에서 승인해야 업무 API 를 쓸 수 있다(그동안 로그인·문의하기·상태 확인은 된다). 기본은 꺼짐(바로 이용).
 */
@Component
class SignupApprovalPolicy(
    @Value("\${ttok.platform.require-approval:false}") val required: Boolean,
    private val institutions: PlatformInstitutionPort,
    private val clock: ClockPort,
) {
    fun onSignedUp(institutionId: InstitutionId) {
        if (required) institutions.setStatus(institutionId.value, ServiceStatus.PENDING, "가입 승인 대기", clock.now())
    }
}

@Service
class InstitutionServiceStatusService(
    private val guard: AccessGuard,
    private val institutions: PlatformInstitutionPort,
    private val policy: SignupApprovalPolicy,
) : InstitutionServiceStatusQuery {
    @Transactional(readOnly = true)
    override fun status(actor: UserId, institutionId: InstitutionId): InstitutionServiceStatusView {
        guard.requireMemberAnyStatus(actor, institutionId)
        val row = institutions.find(institutionId.value)
        return InstitutionServiceStatusView(row?.serviceStatus ?: ServiceStatus.ACTIVE, row?.statusReason, policy.required)
    }
}
