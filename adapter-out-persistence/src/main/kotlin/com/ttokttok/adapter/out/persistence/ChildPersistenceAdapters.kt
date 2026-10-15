package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.ChildEntity
import com.ttokttok.adapter.out.persistence.entity.ChildGuardianKey
import com.ttokttok.adapter.out.persistence.repository.ChildGuardianJpaRepository
import com.ttokttok.adapter.out.persistence.repository.ChildJpaRepository
import com.ttokttok.application.port.out.ChildPort
import com.ttokttok.domain.child.Child
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.UserId
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

// 다기관 아이 (스펙 7-8) — V9 테이블. 생년월일은 원생과 같은 키로 암호화

@Component
class ChildJpaAdapter(
    private val children: ChildJpaRepository,
    private val guardians: ChildGuardianJpaRepository,
    private val crypto: FieldCrypto,
) : ChildPort {

    private fun ChildEntity.toDomain() = Child(ChildId(id), name, birthEnc?.let { LocalDate.parse(crypto.decrypt(it)) })

    override fun save(child: Child): Child {
        val birthEnc = child.birthDate?.let { crypto.encrypt(it.toString()) }
        val e = children.findByIdOrNull(child.id.value) ?: ChildEntity(child.id.value, child.name, birthEnc)
        e.name = child.name
        e.birthEnc = birthEnc
        e.updatedAt = Instant.now()
        children.save(e)
        return child
    }

    override fun find(id: ChildId): Child? = children.findByIdOrNull(id.value)?.toDomain()

    override fun findAllByIds(ids: Collection<ChildId>): List<Child> {
        if (ids.isEmpty()) return emptyList()
        return ids.map { it.value }.distinct().chunked(500).flatMap { children.findAllById(it) }.map { it.toDomain() }
    }

    override fun delete(id: ChildId) {
        guardians.deleteByChild(id.value)
        if (children.existsById(id.value)) children.deleteById(id.value)
    }

    override fun addGuardian(childId: ChildId, userId: UserId) {
        // 네이티브 insert 전에 같은 트랜잭션에서 JPA 로 막 저장한 app_user·child 를 내보낸다 (@Modifying(flushAutomatically))
        guardians.insertIfAbsent(childId.value, userId.value)
    }

    override fun removeGuardian(childId: ChildId, userId: UserId) {
        val key = ChildGuardianKey(childId.value, userId.value)
        if (guardians.existsById(key)) guardians.deleteById(key)
    }

    override fun guardianUserIds(childId: ChildId): List<UserId> = guardians.findByChildId(childId.value).map { UserId(it.userId) }

    override fun findIdsByGuardian(userId: UserId): List<ChildId> = guardians.findByUserId(userId.value).map { ChildId(it.childId) }
}
