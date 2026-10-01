package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.EnrollmentEntity
import com.ttokttok.adapter.out.persistence.entity.GuardianEntity
import com.ttokttok.adapter.out.persistence.entity.StudentEntity
import com.ttokttok.application.port.out.StudentSearchCriteria
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.LocalDate
import java.util.UUID

/**
 * STU-001 원생 검색 조건.
 * - 반 필터: 현재 소속(enrollment.to_date is null) 존재 여부 서브쿼리
 * - 검색어: 이름 부분 일치, 숫자 4자리면 보호자 번호 뒷자리(phone_last4)도 함께
 */
object StudentSpecs {
    fun of(c: StudentSearchCriteria): Specification<StudentEntity> = Specification { root, query, cb ->
        val ps = mutableListOf<Predicate>(cb.equal(root.get<UUID>("institutionId"), c.institutionId.value))
        c.status?.let { ps += cb.equal(root.get<String>("status"), it.name) }

        c.classroomIds?.let { ids ->
            val sq = query!!.subquery(Long::class.javaObjectType)
            val e = sq.from(EnrollmentEntity::class.java)
            sq.select(e.get("id")).where(
                cb.equal(e.get<UUID>("studentId"), root.get<UUID>("id")),
                cb.isNull(e.get<LocalDate>("toDate")),
                e.get<UUID>("classroomId").`in`(ids.map { it.value }),
            )
            ps += cb.exists(sq)
        }

        c.keyword?.let { kw ->
            val byName = cb.like(root.get("name"), "%${kw.replace("%", "\\%").replace("_", "\\_")}%", '\\')
            ps += if (kw.matches(Regex("^\\d{4}$"))) {
                val sq = query!!.subquery(UUID::class.java)
                val g = sq.from(GuardianEntity::class.java)
                sq.select(g.get("id")).where(cb.equal(g.get<UUID>("studentId"), root.get<UUID>("id")), cb.equal(g.get<String>("phoneLast4"), kw))
                cb.or(byName, cb.exists(sq))
            } else byName
        }
        cb.and(*ps.toTypedArray())
    }
}
