package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.AlbumEntity
import com.ttokttok.adapter.out.persistence.entity.AlbumItemEntity
import com.ttokttok.adapter.out.persistence.entity.AlbumItemKey
import com.ttokttok.adapter.out.persistence.entity.AlbumRecipientEntity
import com.ttokttok.adapter.out.persistence.entity.AlbumRecipientKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AlbumJpaRepository : JpaRepository<AlbumEntity, UUID> {
    /** 앨범 + 기관명·작성자명·사진 수·공개 대상 수 */
    @Query(
        """select a, i.name, u.name,
                  (select count(x) from AlbumItemEntity x where x.albumId = a.id),
                  (select count(r) from AlbumRecipientEntity r where r.albumId = a.id)
             from AlbumEntity a join InstitutionEntity i on i.id = a.institutionId join UserEntity u on u.id = a.authorId
            where a.id = :id""",
    )
    fun findView(@Param("id") id: UUID): List<Array<Any?>>

    /** album_item·album_recipient 는 DB 의 on delete cascade 로 함께 지워진다 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AlbumEntity a where a.id = :id")
    fun deleteAlbum(@Param("id") id: UUID): Int
}

interface AlbumItemJpaRepository : JpaRepository<AlbumItemEntity, AlbumItemKey> {
    fun findByAlbumIdOrderByPosition(albumId: UUID): List<AlbumItemEntity>
    fun findByAlbumIdInAndPositionLessThanOrderByAlbumIdAscPositionAsc(albumIds: Collection<UUID>, position: Int): List<AlbumItemEntity>
}

interface AlbumRecipientJpaRepository : JpaRepository<AlbumRecipientEntity, AlbumRecipientKey> {
    fun existsByAlbumIdAndStudentIdIn(albumId: UUID, studentIds: Collection<UUID>): Boolean
}
