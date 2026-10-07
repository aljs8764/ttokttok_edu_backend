package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.ChildPort
import com.ttokttok.domain.child.Child
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

// 다기관 아이 (스펙 7-8) — V9 테이블, JDBC. 생년월일은 원생과 같은 키로 암호화

@Component
class ChildJdbcAdapter(private val jdbc: JdbcTemplate, private val crypto: FieldCrypto) : ChildPort {
    private val mapper = RowMapper { rs, _ ->
        Child(
            ChildId(rs.getObject("id", UUID::class.java)),
            rs.getString("name"),
            rs.getString("birth_enc")?.let { LocalDate.parse(crypto.decrypt(it)) },
        )
    }

    override fun save(child: Child): Child {
        jdbc.update(
            """insert into child (id, name, birth_enc) values (?, ?, ?)
               on conflict (id) do update set name = excluded.name, birth_enc = excluded.birth_enc, updated_at = now()""",
            child.id.value, child.name, child.birthDate?.let { crypto.encrypt(it.toString()) },
        )
        return child
    }

    override fun find(id: ChildId): Child? =
        jdbc.query("select * from child where id = ?", mapper, id.value).firstOrNull()

    override fun findAllByIds(ids: Collection<ChildId>): List<Child> {
        if (ids.isEmpty()) return emptyList()
        return ids.map { it.value }.distinct().chunked(500).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            jdbc.query("select * from child where id in ($marks)", mapper, *chunk.toTypedArray())
        }
    }

    override fun delete(id: ChildId) {
        jdbc.update("delete from child_guardian where child_id = ?", id.value)
        jdbc.update("delete from child where id = ?", id.value)
    }

    override fun addGuardian(childId: ChildId, userId: UserId) {
        jdbc.update("insert into child_guardian (child_id, user_id) values (?, ?) on conflict do nothing", childId.value, userId.value)
    }

    override fun removeGuardian(childId: ChildId, userId: UserId) {
        jdbc.update("delete from child_guardian where child_id = ? and user_id = ?", childId.value, userId.value)
    }

    override fun guardianUserIds(childId: ChildId): List<UserId> =
        jdbc.queryForList("select user_id from child_guardian where child_id = ?", UUID::class.java, childId.value).map(::UserId)

    override fun findIdsByGuardian(userId: UserId): List<ChildId> =
        jdbc.queryForList("select child_id from child_guardian where user_id = ?", UUID::class.java, userId.value).map(::ChildId)
}
