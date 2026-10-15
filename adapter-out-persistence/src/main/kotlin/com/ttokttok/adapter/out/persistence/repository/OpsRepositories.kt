package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.ClassroomSlotEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomTeacherEntity
import com.ttokttok.adapter.out.persistence.entity.ClassroomTeacherKey
import com.ttokttok.adapter.out.persistence.entity.ClassroomWaitlistEntity
import com.ttokttok.adapter.out.persistence.entity.EventTemplateEntity
import com.ttokttok.adapter.out.persistence.entity.StaffAttendanceEntity
import com.ttokttok.adapter.out.persistence.entity.StudentCounselNoteEntity
import com.ttokttok.adapter.out.persistence.entity.StudentHealthEntity
import com.ttokttok.adapter.out.persistence.entity.StudentTagEntity
import com.ttokttok.adapter.out.persistence.entity.StudentTagMapEntity
import com.ttokttok.adapter.out.persistence.entity.StudentTagMapKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 조회 전용 (쓰기는 ClassroomEntity.teacherIds) */
interface ClassroomTeacherJpaRepository : JpaRepository<ClassroomTeacherEntity, ClassroomTeacherKey> {
    fun findByUserId(userId: UUID): List<ClassroomTeacherEntity>
    fun findByClassroomIdIn(classroomIds: Collection<UUID>): List<ClassroomTeacherEntity>
}

interface StudentHealthJpaRepository : JpaRepository<StudentHealthEntity, UUID> {
    @Query("select h, u.name from StudentHealthEntity h join UserEntity u on u.id = h.updatedBy where h.studentId = :id")
    fun findView(@Param("id") studentId: UUID): List<Array<Any?>>

    /** 반의 재원 원생 중 알레르기 기록이 있는 원생 (id, 이름, 알레르기 암호문) */
    @Query(
        """select s.id, s.name, h.allergiesEnc from EnrollmentEntity e
             join StudentEntity s on s.id = e.studentId
             join StudentHealthEntity h on h.studentId = s.id
            where e.classroomId = :c and e.toDate is null and s.status = 'ACTIVE' and h.allergiesEnc is not null
            order by s.name""",
    )
    fun allergyRows(@Param("c") classroomId: UUID): List<Array<Any?>>
}

interface StudentCounselNoteJpaRepository : JpaRepository<StudentCounselNoteEntity, UUID>

interface StudentTagJpaRepository : JpaRepository<StudentTagEntity, UUID> {
    fun existsByInstitutionIdAndName(institutionId: UUID, name: String): Boolean
    fun countByInstitutionId(institutionId: UUID): Long

    /** student_tag_map 은 DB 의 on delete cascade 로 함께 지워진다 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StudentTagEntity t where t.id = :id")
    fun deleteTag(@Param("id") id: UUID): Int
}

interface StudentTagMapJpaRepository : JpaRepository<StudentTagMapEntity, StudentTagMapKey> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StudentTagMapEntity m where m.studentId = :s")
    fun deleteByStudent(@Param("s") studentId: UUID): Int
}

interface ClassroomSlotJpaRepository : JpaRepository<ClassroomSlotEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ClassroomSlotEntity sl where sl.classroomId = :c")
    fun deleteByClassroom(@Param("c") classroomId: UUID): Int
}

interface ClassroomWaitlistJpaRepository : JpaRepository<ClassroomWaitlistEntity, UUID> {
    fun countByClassroomIdAndStatus(classroomId: UUID, status: String): Long
}

interface StaffAttendanceJpaRepository : JpaRepository<StaffAttendanceEntity, UUID>

interface EventTemplateJpaRepository : JpaRepository<EventTemplateEntity, UUID> {
    fun countByInstitutionId(institutionId: UUID): Long

    @Query("select t, u.name from EventTemplateEntity t join UserEntity u on u.id = t.createdBy where t.institutionId = :i order by t.name")
    fun findViews(@Param("i") institutionId: UUID): List<Array<Any?>>

    @Query("select t, u.name from EventTemplateEntity t join UserEntity u on u.id = t.createdBy where t.id = :id and t.institutionId = :i")
    fun findView(@Param("id") id: UUID, @Param("i") institutionId: UUID): List<Array<Any?>>
}
