package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.NoticeTemplateEntity
import com.ttokttok.adapter.out.persistence.repository.NoticeTemplateJpaRepository
import com.ttokttok.application.port.`in`.NoticeTemplateView
import com.ttokttok.application.port.out.NoticeTemplatePort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import com.ttokttok.domain.notice.NoticeKind
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** NTC-003 알림장 템플릿 */
@Component
class NoticeTemplateJpaAdapter(private val repo: NoticeTemplateJpaRepository, private val q: JpaQueries) : NoticeTemplatePort {

    private companion object {
        const val SELECT = "select t, u.name from NoticeTemplateEntity t join UserEntity u on u.id = t.createdBy"

        fun view(r: Array<Any?>): NoticeTemplateView {
            val t = r[0] as NoticeTemplateEntity
            return NoticeTemplateView(
                id = t.id, kind = NoticeKind.valueOf(t.kind), name = t.name, title = t.title, body = t.body,
                createdById = t.createdBy, createdByName = r[1] as String, useCount = t.useCount, updatedAt = t.updatedAt,
            )
        }
    }

    override fun list(institutionId: InstitutionId, kind: NoticeKind?): List<NoticeTemplateView> {
        val p = params("inst" to institutionId.value)
        val kindClause = if (kind != null) {
            p["kind"] = kind.name
            " and t.kind = :kind"
        } else ""
        return q.jpqlRows("$SELECT where t.institutionId = :inst$kindClause order by t.useCount desc, t.name", p).map(::view)
    }

    override fun find(id: UUID, institutionId: InstitutionId): NoticeTemplateView? =
        q.jpqlRows("$SELECT where t.id = :id and t.institutionId = :inst", params("id" to id, "inst" to institutionId.value)).firstOrNull()?.let(::view)

    override fun insert(institutionId: InstitutionId, creator: UserId, kind: NoticeKind, name: String, title: String, body: String, now: Instant): UUID {
        val id = Uuid7.next()
        repo.save(NoticeTemplateEntity(id, institutionId.value, kind.name, name, title, body, creator.value, createdAt = now, updatedAt = now))
        return id
    }

    override fun update(id: UUID, kind: NoticeKind, name: String, title: String, body: String, now: Instant) {
        val e = repo.findByIdOrNull(id) ?: return
        e.kind = kind.name
        e.name = name
        e.title = title
        e.body = body
        e.updatedAt = now
        repo.save(e)
    }

    override fun delete(id: UUID) {
        if (repo.existsById(id)) repo.deleteById(id)
    }

    override fun incrementUse(id: UUID) {
        repo.incrementUse(id)
    }

    override fun count(institutionId: InstitutionId): Int = repo.countByInstitutionId(institutionId.value).toInt()
}
