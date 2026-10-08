package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.NoticeTemplateView
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeKind
import java.time.Instant
import java.util.UUID

interface NoticeTemplatePort {
    fun list(institutionId: InstitutionId, kind: NoticeKind?): List<NoticeTemplateView>
    fun find(id: UUID, institutionId: InstitutionId): NoticeTemplateView?
    fun insert(institutionId: InstitutionId, creator: UserId, kind: NoticeKind, name: String, title: String, body: String, now: Instant): UUID
    fun update(id: UUID, kind: NoticeKind, name: String, title: String, body: String, now: Instant)
    fun delete(id: UUID)
    fun incrementUse(id: UUID)
    fun count(institutionId: InstitutionId): Int
}
