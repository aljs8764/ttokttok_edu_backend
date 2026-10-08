package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeKind
import java.time.Instant
import java.util.UUID

data class NoticeTemplateView(
    val id: UUID,
    val kind: NoticeKind,
    val name: String,
    val title: String,
    val body: String,
    val createdById: UUID,
    val createdByName: String,
    val useCount: Int,
    val updatedAt: Instant,
)

/** NTC-003 알림장 템플릿 라이브러리. 전체 공지 양식(ANNOUNCEMENT)은 원장·실장만 만들 수 있다 */
interface NoticeTemplateUseCase {
    fun list(actor: UserId, institutionId: InstitutionId, kind: NoticeKind?): List<NoticeTemplateView>
    fun create(actor: UserId, institutionId: InstitutionId, command: Command): NoticeTemplateView
    /** 만든 사람 또는 원장·실장 */
    fun update(actor: UserId, institutionId: InstitutionId, id: UUID, command: Command): NoticeTemplateView
    fun delete(actor: UserId, institutionId: InstitutionId, id: UUID)
    /** 작성 화면에서 템플릿을 불러올 때 호출 — 인기 순 정렬용 */
    fun markUsed(actor: UserId, institutionId: InstitutionId, id: UUID)

    data class Command(val kind: NoticeKind, val name: String, val title: String, val body: String)
}
