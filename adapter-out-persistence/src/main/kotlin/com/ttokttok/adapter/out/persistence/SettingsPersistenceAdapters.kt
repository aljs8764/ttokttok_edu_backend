package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.LoginAttemptPort
import com.ttokttok.application.port.out.RefreshTokenPort
import com.ttokttok.application.port.out.RefreshTokenRecord
import com.ttokttok.application.port.out.StaffInvitationPort
import com.ttokttok.application.port.out.StoredFilePort
import com.ttokttok.application.port.out.TermsPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.file.FileId
import com.ttokttok.domain.file.FilePurpose
import com.ttokttok.domain.file.FileStatus
import com.ttokttok.domain.file.StoredFile
import com.ttokttok.domain.staff.StaffInvitation
import com.ttokttok.domain.staff.StaffInvitationId
import com.ttokttok.domain.terms.Terms
import com.ttokttok.domain.terms.TermsAgreement
import com.ttokttok.domain.terms.TermsId
import com.ttokttok.domain.terms.TermsType
import com.ttokttok.domain.user.LoginAttempt
import com.ttokttok.domain.user.Role
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
private fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
private fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun Instant?.ts(): Timestamp? = this?.let(Timestamp::from)

@Component
class StoredFileJdbcAdapter(jdbc: JdbcTemplate) : StoredFilePort {
    private val plain = jdbc
    private val named = NamedParameterJdbcTemplate(jdbc)

    private val mapper = RowMapper { rs, _ ->
        StoredFile(
            id = FileId(rs.uuid("id")), institutionId = InstitutionId(rs.uuid("institution_id")),
            uploaderId = UserId(rs.uuid("uploader_id")), purpose = FilePurpose.valueOf(rs.getString("purpose")),
            originalName = rs.getString("original_name"), mime = rs.getString("mime"), size = rs.getLong("size"),
            storageKey = rs.getString("storage_key"), status = FileStatus.valueOf(rs.getString("status")),
            createdAt = rs.instant("created_at"),
        )
    }

    override fun save(file: StoredFile): StoredFile {
        plain.update(
            """insert into stored_file (id, institution_id, uploader_id, purpose, original_name, mime, size, storage_key, status, created_at)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               on conflict (id) do update set status = excluded.status""",
            file.id.value, file.institutionId.value, file.uploaderId.value, file.purpose.name, file.originalName,
            file.mime, file.size, file.storageKey, file.status.name, Timestamp.from(file.createdAt),
        )
        return file
    }

    override fun find(id: FileId): StoredFile? =
        plain.query("select * from stored_file where id = ?", mapper, id.value).firstOrNull()

    override fun findAllByIds(ids: Collection<FileId>): List<StoredFile> =
        ids.map { it.value }.distinct().chunked(1000).flatMap {
            named.query("select * from stored_file where id in (:ids)", MapSqlParameterSource("ids", it), mapper)
        }

    override fun findPendingBefore(before: Instant, limit: Int): List<StoredFile> =
        plain.query(
            "select * from stored_file where status = 'PENDING' and created_at < ? order by created_at limit ? for update skip locked",
            mapper, Timestamp.from(before), limit,
        )

    override fun delete(id: FileId) {
        plain.update("delete from stored_file where id = ? and status = 'PENDING'", id.value)
    }
}

@Component
class LoginAttemptJdbcAdapter(private val jdbc: JdbcTemplate) : LoginAttemptPort {
    override fun find(userId: UserId): LoginAttempt? =
        jdbc.query("select * from login_attempt where user_id = ?", { rs, _ ->
            LoginAttempt(UserId(rs.uuid("user_id")), rs.getInt("failed_count"), rs.instantOrNull("locked_until"))
        }, userId.value).firstOrNull()

    override fun save(attempt: LoginAttempt) {
        jdbc.update(
            """insert into login_attempt (user_id, failed_count, locked_until, updated_at) values (?, ?, ?, now())
               on conflict (user_id) do update set failed_count = excluded.failed_count,
                   locked_until = excluded.locked_until, updated_at = now()""",
            attempt.userId.value, attempt.failedCount, attempt.lockedUntil.ts(),
        )
    }
}

@Component
class RefreshTokenJdbcAdapter(private val jdbc: JdbcTemplate) : RefreshTokenPort {
    private val mapper = RowMapper { rs, _ ->
        RefreshTokenRecord(
            jti = rs.uuid("jti"), familyId = rs.uuid("family_id"), userId = UserId(rs.uuid("user_id")),
            rememberMe = rs.getBoolean("remember_me"), expiresAt = rs.instant("expires_at"),
            revokedAt = rs.instantOrNull("revoked_at"), replacedBy = rs.uuidOrNull("replaced_by"),
        )
    }

    override fun save(record: RefreshTokenRecord) {
        jdbc.update(
            "insert into refresh_token (jti, family_id, user_id, remember_me, expires_at) values (?, ?, ?, ?, ?)",
            record.jti, record.familyId, record.userId.value, record.rememberMe, Timestamp.from(record.expiresAt),
        )
    }

    override fun find(jti: UUID): RefreshTokenRecord? = jdbc.query("select * from refresh_token where jti = ?", mapper, jti).firstOrNull()

    override fun revoke(jti: UUID, at: Instant, replacedBy: UUID?) {
        jdbc.update("update refresh_token set revoked_at = coalesce(revoked_at, ?), replaced_by = ? where jti = ?", Timestamp.from(at), replacedBy, jti)
    }

    override fun revokeFamily(familyId: UUID, at: Instant) {
        jdbc.update("update refresh_token set revoked_at = ? where family_id = ? and revoked_at is null", Timestamp.from(at), familyId)
    }

    override fun revokeAllForUser(userId: UserId, at: Instant) {
        jdbc.update("update refresh_token set revoked_at = ? where user_id = ? and revoked_at is null", Timestamp.from(at), userId.value)
    }
}

@Component
class StaffInvitationJdbcAdapter(private val jdbc: JdbcTemplate) : StaffInvitationPort {
    private val mapper = RowMapper { rs, _ ->
        StaffInvitation(
            id = StaffInvitationId(rs.uuid("id")), institutionId = InstitutionId(rs.uuid("institution_id")),
            email = rs.getString("email"), name = rs.getString("name"), role = Role.valueOf(rs.getString("role")),
            token = rs.getString("token"), invitedBy = UserId(rs.uuid("invited_by")), expiresAt = rs.instant("expires_at"),
            acceptedAt = rs.instantOrNull("accepted_at"), revokedAt = rs.instantOrNull("revoked_at"), createdAt = rs.instant("created_at"),
        )
    }

    override fun save(invitation: StaffInvitation): StaffInvitation {
        jdbc.update(
            """insert into staff_invitation (id, institution_id, email, name, role, token, invited_by, expires_at, accepted_at, revoked_at, created_at)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               on conflict (id) do update set accepted_at = excluded.accepted_at, revoked_at = excluded.revoked_at""",
            invitation.id.value, invitation.institutionId.value, invitation.email, invitation.name, invitation.role.name,
            invitation.token, invitation.invitedBy.value, Timestamp.from(invitation.expiresAt),
            invitation.acceptedAt.ts(), invitation.revokedAt.ts(), Timestamp.from(invitation.createdAt),
        )
        return invitation
    }

    override fun find(id: StaffInvitationId, institutionId: InstitutionId): StaffInvitation? =
        jdbc.query("select * from staff_invitation where id = ? and institution_id = ?", mapper, id.value, institutionId.value).firstOrNull()

    override fun findByToken(token: String): StaffInvitation? =
        jdbc.query("select * from staff_invitation where token = ?", mapper, token).firstOrNull()

    override fun findByInstitution(institutionId: InstitutionId): List<StaffInvitation> =
        jdbc.query("select * from staff_invitation where institution_id = ? order by created_at desc", mapper, institutionId.value)
}

@Component
class TermsJdbcAdapter(private val jdbc: JdbcTemplate) : TermsPort {
    override fun findAll(): List<Terms> = jdbc.query("select * from terms") { rs, _ ->
        Terms(
            id = TermsId(rs.uuid("id")), type = TermsType.valueOf(rs.getString("type")), version = rs.getInt("version"),
            title = rs.getString("title"), body = rs.getString("body"), required = rs.getBoolean("required"),
            effectiveAt = rs.instant("effective_at"),
        )
    }

    override fun findAgreedIds(userId: UserId): Set<TermsId> =
        jdbc.query("select terms_id from terms_agreement where user_id = ?", { rs, _ -> TermsId(rs.uuid("terms_id")) }, userId.value).toSet()

    override fun saveAgreements(agreements: List<TermsAgreement>) {
        if (agreements.isEmpty()) return
        jdbc.batchUpdate(
            "insert into terms_agreement (user_id, terms_id, agreed_at, ip) values (?, ?, ?, ?) on conflict do nothing",
            agreements.map { arrayOf(it.userId.value, it.termsId.value, Timestamp.from(it.agreedAt), it.ip) },
        )
    }
}
