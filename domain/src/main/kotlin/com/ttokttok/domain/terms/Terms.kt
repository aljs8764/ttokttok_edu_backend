package com.ttokttok.domain.terms

import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import java.time.Instant
import java.util.UUID

@JvmInline value class TermsId(val value: UUID) { companion object { fun new() = TermsId(Uuid7.next()) } }

/** 약관 종류. audience = 누구에게 받는가 (학부모 앱 / 기관 관리자) */
enum class TermsType(val audience: TermsAudience) {
    PARENT_SERVICE(TermsAudience.PARENT),
    PARENT_PRIVACY(TermsAudience.PARENT),
    /** 만 14세 미만 아동 정보 — 학부모 동의를 법정대리인 동의로 처리 (Open Issue 10) */
    CHILD_PRIVACY_GUARDIAN(TermsAudience.PARENT),
    PARENT_MARKETING(TermsAudience.PARENT),
    INSTITUTION_SERVICE(TermsAudience.INSTITUTION),
    INSTITUTION_PRIVACY_PROCESSING(TermsAudience.INSTITUTION),
}

enum class TermsAudience { PARENT, INSTITUTION }

/** 약관 버전 (SET-005). 개정 시 새 버전을 추가하고, 필수 약관이면 재동의를 받는다 */
data class Terms(
    val id: TermsId,
    val type: TermsType,
    val version: Int,
    val title: String,
    val body: String,
    val required: Boolean,
    val effectiveAt: Instant,
)

data class TermsAgreement(val userId: UserId, val termsId: TermsId, val agreedAt: Instant, val ip: String?)

object TermsPolicy {
    /** 시행 중인 최신 버전 (종류별 1개) */
    fun current(all: List<Terms>, now: Instant): List<Terms> =
        all.filter { !it.effectiveAt.isAfter(now) }.groupBy { it.type }.map { (_, list) -> list.maxBy { it.version } }

    /** 아직 동의하지 않은 시행 중 필수 약관 — 개정되면 새 버전이 다시 여기에 잡힌다 */
    fun pendingRequired(current: List<Terms>, agreedTermsIds: Set<TermsId>, audience: TermsAudience): List<Terms> =
        current.filter { it.required && it.type.audience == audience && it.id !in agreedTermsIds }
}
