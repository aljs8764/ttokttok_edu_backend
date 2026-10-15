package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.util.UUID

/*
 * 복합 키 테이블은 @IdClass 로 매핑한다. 키 클래스는 기본값을 둬서 인자 없는 생성자가 생기게 한다 (JPA 요구사항).
 */

/**
 * classroom_teacher 조회 전용 매핑. 쓰기는 [ClassroomEntity.teacherIds] (ElementCollection) 로만 한다.
 * JPQL 서브쿼리에서 "이 교사가 담당하는 반" 조건을 쓰기 위해 둔다.
 */
@Entity @Immutable @Table(name = "classroom_teacher") @IdClass(ClassroomTeacherKey::class)
class ClassroomTeacherEntity(
    // ElementCollection 쪽 매핑과 같은 논리 컬럼명을 써야 한다 (다르면 Hibernate DuplicateMappingException)
    @Id @Column(name = "classroom_id") var classroomId: UUID,
    @Id @Column(name = "user_id") var userId: UUID,
)

data class ClassroomTeacherKey(var classroomId: UUID? = null, var userId: UUID? = null) : Serializable
