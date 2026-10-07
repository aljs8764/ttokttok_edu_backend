package com.ttokttok.application.port.out

import com.ttokttok.domain.child.Child
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.UserId

// 다기관 아이 (스펙 7-8)

interface ChildPort {
    fun save(child: Child): Child
    fun find(id: ChildId): Child?
    fun findAllByIds(ids: Collection<ChildId>): List<Child>
    /** child_guardian 까지 함께 지운다 (합치기 후 빈 아이) */
    fun delete(id: ChildId)

    fun addGuardian(childId: ChildId, userId: UserId)
    fun removeGuardian(childId: ChildId, userId: UserId)
    fun guardianUserIds(childId: ChildId): List<UserId>
    /** 이 보호자가 볼 수 있는 아이 id */
    fun findIdsByGuardian(userId: UserId): List<ChildId>
}
