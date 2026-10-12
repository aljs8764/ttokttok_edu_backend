package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.CommunityReportView
import com.ttokttok.application.port.`in`.LikeResult
import com.ttokttok.application.port.`in`.MyCommunityComment
import com.ttokttok.application.port.out.BoardSpec
import com.ttokttok.application.port.out.CommentRow
import com.ttokttok.application.port.out.CommunityAffiliation
import com.ttokttok.application.port.out.CommunityMemberRow
import com.ttokttok.application.port.out.CommunityPort
import com.ttokttok.application.port.out.PostRow
import com.ttokttok.application.port.out.SanctionRow
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.cmId(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.cmTs(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun cmP(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }
private fun cmT(i: Instant?) = i?.let { Timestamp.from(it) }

/** 커뮤니티에 노출해도 되는 기관 (체험 샘플·정지·승인 대기 제외) */
private const val LIVE_INSTITUTION = "i.is_sample = false and i.service_status = 'ACTIVE'"

private const val POST_COLS = """p.id, p.scope, p.audience, p.institution_id, p.author_id, p.author_role, p.author_region, p.category,
    p.title, p.body, p.like_count, p.comment_count, p.hidden, p.deleted_at, p.created_at"""

@Component
class CommunityJdbcAdapter(jdbc: JdbcTemplate) : CommunityPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private fun postRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = PostRow(
        id = rs.cmId("id")!!,
        board = BoardSpec(rs.getString("scope"), rs.getString("audience"), rs.cmId("institution_id")),
        authorId = rs.cmId("author_id")!!, authorRole = rs.getString("author_role"), authorRegion = rs.getString("author_region"),
        category = rs.getString("category"), title = rs.getString("title"), body = rs.getString("body"),
        likeCount = rs.getInt("like_count"), commentCount = rs.getInt("comment_count"),
        hidden = rs.getString("hidden"), deleted = rs.cmTs("deleted_at") != null, createdAt = rs.cmTs("created_at")!!,
    )

    private fun commentRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = CommentRow(
        id = rs.cmId("id")!!, postId = rs.cmId("post_id")!!, parentId = rs.cmId("parent_id"), authorId = rs.cmId("author_id")!!,
        anonNo = rs.getInt("anon_no"), body = rs.getString("body"), likeCount = rs.getInt("like_count"),
        hidden = rs.getString("hidden"), deleted = rs.cmTs("deleted_at") != null, createdAt = rs.cmTs("created_at")!!,
    )

    private fun table(targetType: String) = if (targetType == "POST") "community_post" else "community_comment"

    // ───────── 자격 ─────────
    override fun staffAffiliations(user: UserId): List<CommunityAffiliation> = named.query(
        """select i.id, i.name, m.role, i.address
             from membership m join institution i on i.id = m.institution_id
            where m.user_id = :u and m.role in ('OWNER', 'ADMIN', 'TEACHER') and $LIVE_INSTITUTION
            order by i.name""",
        cmP("u" to user.value),
    ) { rs, _ -> CommunityAffiliation(rs.cmId("id")!!, rs.getString("name"), rs.getString("role"), rs.getString("address")) }

    override fun parentAffiliations(user: UserId): List<CommunityAffiliation> = named.query(
        """select distinct i.id, i.name, i.address
             from guardian g join student s on s.id = g.student_id
                             join institution i on i.id = g.institution_id
            where g.user_id = :u and g.link_status = 'LINKED' and s.status <> 'WITHDRAWN' and $LIVE_INSTITUTION
            order by i.name""",
        cmP("u" to user.value),
    ) { rs, _ -> CommunityAffiliation(rs.cmId("id")!!, rs.getString("name"), "PARENT", rs.getString("address")) }

    override fun member(user: UserId): CommunityMemberRow? = named.query(
        "select rules_agreed_at, activity_seen_at from community_member where user_id = :u",
        cmP("u" to user.value),
    ) { rs, _ -> CommunityMemberRow(rs.cmTs("rules_agreed_at"), rs.cmTs("activity_seen_at")) }.firstOrNull()

    override fun agreeRules(user: UserId, now: Instant) {
        named.update(
            """insert into community_member (user_id, rules_agreed_at) values (:u, :now)
               on conflict (user_id) do update set rules_agreed_at = coalesce(community_member.rules_agreed_at, :now)""",
            cmP("u" to user.value, "now" to cmT(now)),
        )
    }

    override fun markActivitySeen(user: UserId, now: Instant) {
        named.update(
            """insert into community_member (user_id, activity_seen_at) values (:u, :now)
               on conflict (user_id) do update set activity_seen_at = :now""",
            cmP("u" to user.value, "now" to cmT(now)),
        )
    }

    override fun sanction(user: UserId): SanctionRow? = named.query(
        "select until_at, reason from community_sanction where user_id = :u",
        cmP("u" to user.value),
    ) { rs, _ -> SanctionRow(rs.cmTs("until_at"), rs.getString("reason")) }.firstOrNull()

    override fun newCommentsOnMyPosts(user: UserId, since: Instant?): Int = named.queryForObject(
        """select count(*) from community_comment c join community_post p on p.id = c.post_id
            where p.author_id = :u and p.deleted_at is null and c.author_id <> :u and c.deleted_at is null
              and (cast(:since as timestamptz) is null or c.created_at > :since)""",
        cmP("u" to user.value, "since" to cmT(since)), Int::class.java,
    ) ?: 0

    override fun recentPostCount(user: UserId, since: Instant): Int = named.queryForObject(
        "select count(*) from community_post where author_id = :u and created_at > :since",
        cmP("u" to user.value, "since" to cmT(since)), Int::class.java,
    ) ?: 0

    override fun recentCommentCount(user: UserId, since: Instant): Int = named.queryForObject(
        "select count(*) from community_comment where author_id = :u and created_at > :since",
        cmP("u" to user.value, "since" to cmT(since)), Int::class.java,
    ) ?: 0

    // ───────── 글 ─────────
    override fun posts(board: BoardSpec, viewer: UserId, category: String?, q: String?, popularSince: Instant?, offset: Int, limit: Int): List<PostRow> {
        val where = StringBuilder(
            """p.scope = :scope and p.audience = :aud and p.deleted_at is null
               and (p.hidden is null or p.author_id = :viewer)""",
        )
        val params = cmP("scope" to board.scope, "aud" to board.audience, "viewer" to viewer.value, "offset" to offset, "limit" to limit)
        if (board.institutionId == null) where.append(" and p.institution_id is null")
        else { where.append(" and p.institution_id = :inst"); params.addValue("inst", board.institutionId) }
        if (category != null) { where.append(" and p.category = :cat"); params.addValue("cat", category) }
        if (q != null) {
            where.append(" and (p.title ilike :q escape '\\' or p.body ilike :q escape '\\')")
            params.addValue("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        }
        val order = if (popularSince != null) {
            where.append(" and p.created_at >= :since"); params.addValue("since", cmT(popularSince))
            "(p.like_count * 2 + p.comment_count) desc, p.created_at desc"
        } else "p.created_at desc"
        return named.query("select $POST_COLS from community_post p where $where order by $order offset :offset limit :limit", params, ::postRow)
    }

    override fun findPost(id: UUID): PostRow? =
        named.query("select $POST_COLS from community_post p where p.id = :id", cmP("id" to id), ::postRow).firstOrNull()

    override fun insertPost(board: BoardSpec, author: UserId, role: String, region: String?, category: String, title: String, body: String, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into community_post (id, scope, audience, institution_id, author_id, author_role, author_region, category, title, body, created_at, updated_at)
               values (:id, :scope, :aud, :inst, :u, :role, :region, :cat, :title, :body, :now, :now)""",
            cmP(
                "id" to id, "scope" to board.scope, "aud" to board.audience, "inst" to board.institutionId, "u" to author.value,
                "role" to role, "region" to region, "cat" to category, "title" to title, "body" to body, "now" to cmT(now),
            ),
        )
        // 글쓴이 익명 번호 0 고정
        named.update("insert into community_anon (post_id, user_id, anon_no) values (:id, :u, 0)", cmP("id" to id, "u" to author.value))
        return id
    }

    override fun updatePost(id: UUID, category: String, title: String, body: String, now: Instant) {
        named.update(
            "update community_post set category = :cat, title = :title, body = :body, updated_at = :now where id = :id",
            cmP("id" to id, "cat" to category, "title" to title, "body" to body, "now" to cmT(now)),
        )
    }

    override fun deletePost(id: UUID, now: Instant) {
        named.update("update community_post set deleted_at = :now, updated_at = :now where id = :id and deleted_at is null", cmP("id" to id, "now" to cmT(now)))
    }

    // ───────── 댓글 ─────────
    override fun comments(postId: UUID): List<CommentRow> = named.query(
        """select id, post_id, parent_id, author_id, anon_no, body, like_count, hidden, deleted_at, created_at
             from community_comment where post_id = :p
            order by coalesce(parent_id, id), parent_id nulls first, created_at""",
        cmP("p" to postId), ::commentRow,
    ).let { rows ->
        // uuid7 은 시간순이지만 정렬 안정성을 위해 부모 생성 순 → 자식 생성 순으로 다시 묶는다
        val parents = rows.filter { it.parentId == null }.sortedBy { it.createdAt }
        val children = rows.filter { it.parentId != null }.groupBy { it.parentId }
        parents.flatMap { p -> listOf(p) + (children[p.id]?.sortedBy { it.createdAt } ?: emptyList()) }
    }

    override fun findComment(id: UUID): CommentRow? = named.query(
        "select id, post_id, parent_id, author_id, anon_no, body, like_count, hidden, deleted_at, created_at from community_comment where id = :id",
        cmP("id" to id), ::commentRow,
    ).firstOrNull()

    override fun anonNo(postId: UUID, user: UserId, isAuthor: Boolean): Int {
        named.query("select anon_no from community_anon where post_id = :p and user_id = :u", cmP("p" to postId, "u" to user.value)) { rs, _ -> rs.getInt(1) }
            .firstOrNull()?.let { return it }
        if (isAuthor) {
            named.update("insert into community_anon (post_id, user_id, anon_no) values (:p, :u, 0) on conflict do nothing", cmP("p" to postId, "u" to user.value))
            return 0
        }
        // 글 행을 잠가 같은 글의 번호 발급을 직렬화
        named.query("select id from community_post where id = :p for update", cmP("p" to postId)) { rs, _ -> rs.cmId("id") }
        val next = named.queryForObject(
            "select coalesce(max(anon_no), 0) + 1 from community_anon where post_id = :p", cmP("p" to postId), Int::class.java,
        ) ?: 1
        named.update("insert into community_anon (post_id, user_id, anon_no) values (:p, :u, :n)", cmP("p" to postId, "u" to user.value, "n" to next))
        return next
    }

    override fun insertComment(postId: UUID, parentId: UUID?, author: UserId, anonNo: Int, body: String, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into community_comment (id, post_id, parent_id, author_id, anon_no, body, created_at)
               values (:id, :p, :parent, :u, :anon, :body, :now)""",
            cmP("id" to id, "p" to postId, "parent" to parentId, "u" to author.value, "anon" to anonNo, "body" to body, "now" to cmT(now)),
        )
        named.update("update community_post set comment_count = comment_count + 1 where id = :p", cmP("p" to postId))
        return id
    }

    override fun deleteComment(id: UUID, now: Instant) {
        val n = named.update("update community_comment set deleted_at = :now where id = :id and deleted_at is null", cmP("id" to id, "now" to cmT(now)))
        if (n > 0) {
            named.update(
                "update community_post set comment_count = greatest(comment_count - 1, 0) where id = (select post_id from community_comment where id = :id)",
                cmP("id" to id),
            )
        }
    }

    // ───────── 공감 ─────────
    override fun liked(targetType: String, targetId: UUID, user: UserId): Boolean = named.queryForObject(
        "select exists (select 1 from community_like where target_type = :t and target_id = :id and user_id = :u)",
        cmP("t" to targetType, "id" to targetId, "u" to user.value), Boolean::class.java,
    ) == true

    override fun likedComments(postId: UUID, user: UserId): Set<UUID> = named.query(
        """select l.target_id from community_like l join community_comment c on c.id = l.target_id
            where l.target_type = 'COMMENT' and l.user_id = :u and c.post_id = :p""",
        cmP("u" to user.value, "p" to postId),
    ) { rs, _ -> rs.cmId("target_id")!! }.toSet()

    override fun toggleLike(targetType: String, targetId: UUID, user: UserId, now: Instant): LikeResult {
        val params = cmP("t" to targetType, "id" to targetId, "u" to user.value, "now" to cmT(now))
        val removed = named.update("delete from community_like where target_type = :t and target_id = :id and user_id = :u", params)
        val liked = if (removed > 0) {
            false
        } else {
            named.update("insert into community_like (target_type, target_id, user_id, created_at) values (:t, :id, :u, :now) on conflict do nothing", params)
            true
        }
        val delta = if (liked) "+ 1" else "- 1"
        val count = named.queryForObject(
            "update ${table(targetType)} set like_count = greatest(like_count $delta, 0) where id = :id returning like_count",
            params, Int::class.java,
        ) ?: 0
        return LikeResult(liked, count)
    }

    // ───────── 신고 ─────────
    override fun report(targetType: String, targetId: UUID, reporter: UserId, reason: String, memo: String?, now: Instant): Int? {
        try {
            named.update(
                """insert into community_report (id, target_type, target_id, reporter_id, reason, memo, created_at)
                   values (:id, :t, :target, :u, :reason, :memo, :now)""",
                cmP("id" to Uuid7.next(), "t" to targetType, "target" to targetId, "u" to reporter.value, "reason" to reason, "memo" to memo, "now" to cmT(now)),
            )
        } catch (e: DuplicateKeyException) {
            return null
        }
        return named.queryForObject(
            "update ${table(targetType)} set report_count = report_count + 1 where id = :target returning report_count",
            cmP("target" to targetId), Int::class.java,
        ) ?: 0
    }

    override fun setHidden(targetType: String, targetId: UUID, hidden: String?) {
        named.update("update ${table(targetType)} set hidden = :h where id = :id", cmP("h" to hidden, "id" to targetId))
    }

    // ───────── 내 활동 ─────────
    override fun myPosts(user: UserId, offset: Int, limit: Int): List<PostRow> = named.query(
        "select $POST_COLS from community_post p where p.author_id = :u and p.deleted_at is null order by p.created_at desc offset :offset limit :limit",
        cmP("u" to user.value, "offset" to offset, "limit" to limit), ::postRow,
    )

    override fun myComments(user: UserId, offset: Int, limit: Int): List<MyCommunityComment> = named.query(
        """select c.id, c.post_id, p.title, c.body, c.hidden, c.deleted_at, c.created_at
             from community_comment c join community_post p on p.id = c.post_id
            where c.author_id = :u and p.deleted_at is null
            order by c.created_at desc offset :offset limit :limit""",
        cmP("u" to user.value, "offset" to offset, "limit" to limit),
    ) { rs, _ ->
        val deleted = rs.cmTs("deleted_at") != null
        MyCommunityComment(
            id = rs.cmId("id")!!, postId = rs.cmId("post_id")!!, postTitle = rs.getString("title"),
            body = if (deleted) null else rs.getString("body"), deleted = deleted, createdAt = rs.cmTs("created_at")!!,
        )
    }

    // ───────── COM-008 운영사 ─────────
    override fun reports(open: Boolean, offset: Int, limit: Int): List<CommunityReportView> {
        // 대상(글·댓글)별로 묶는다. open=true 면 열린 신고가 하나라도 있는 대상, false 면 모두 처리된 대상
        val groups = named.query(
            """select r.target_type, r.target_id, count(*) as cnt, min(r.created_at) as first_at, bool_or(r.status = 'OPEN') as is_open
                 from community_report r
                group by r.target_type, r.target_id
               having bool_or(r.status = 'OPEN') = :open
                order by min(r.created_at) ${if (open) "asc" else "desc"}
               offset :offset limit :limit""",
            cmP("open" to open, "offset" to offset, "limit" to limit),
        ) { rs, _ ->
            listOf(rs.getString("target_type"), rs.cmId("target_id")!!, rs.getInt("cnt"), rs.cmTs("first_at")!!, rs.getBoolean("is_open"))
        }
        return groups.mapNotNull { g ->
            val type = g[0] as String
            val target = g[1] as UUID
            val post: PostRow
            val title: String?
            val body: String?
            val hidden: String?
            val deleted: Boolean
            val authorId: UUID
            if (type == "POST") {
                post = findPost(target) ?: return@mapNotNull null
                title = post.title; body = post.body; hidden = post.hidden; deleted = post.deleted; authorId = post.authorId
            } else {
                val c = findComment(target) ?: return@mapNotNull null
                post = findPost(c.postId) ?: return@mapNotNull null
                title = post.title; body = c.body; hidden = c.hidden; deleted = c.deleted; authorId = c.authorId
            }
            val reasons = named.query(
                "select reason, count(*) from community_report where target_type = :t and target_id = :id group by reason",
                cmP("t" to type, "id" to target),
            ) { rs, _ -> rs.getString(1) to rs.getInt(2) }.toMap()
            val memos = named.query(
                "select memo from community_report where target_type = :t and target_id = :id and memo is not null order by created_at limit 20",
                cmP("t" to type, "id" to target),
            ) { rs, _ -> rs.getString(1) }
            val sanctioned = named.queryForObject(
                "select exists (select 1 from community_sanction where user_id = :u and (until_at is null or until_at > now()))",
                cmP("u" to authorId), Boolean::class.java,
            ) == true
            CommunityReportView(
                targetType = type, targetId = target, postId = post.id, boardKey = post.board.key,
                title = title, body = body, hidden = hidden, deleted = deleted, reportCount = g[2] as Int,
                reasons = reasons, memos = memos, firstReportedAt = g[3] as Instant, open = g[4] as Boolean, authorSanctioned = sanctioned,
            )
        }
    }

    override fun resolveReports(targetType: String, targetId: UUID, actor: UserId, resolution: String, now: Instant) {
        named.update(
            """update community_report set status = 'RESOLVED', resolution = :res, resolved_by = :u, resolved_at = :now
                where target_type = :t and target_id = :id and status = 'OPEN'""",
            cmP("res" to resolution, "u" to actor.value, "now" to cmT(now), "t" to targetType, "id" to targetId),
        )
    }

    override fun authorOf(targetType: String, targetId: UUID): UUID? = named.query(
        "select author_id from ${table(targetType)} where id = :id", cmP("id" to targetId),
    ) { rs, _ -> rs.cmId("author_id") }.firstOrNull()

    override fun setSanction(user: UUID, until: Instant?, reason: String, actor: UserId, now: Instant) {
        named.update(
            """insert into community_sanction (user_id, until_at, reason, created_by, created_at) values (:u, :until, :reason, :by, :now)
               on conflict (user_id) do update set until_at = excluded.until_at, reason = excluded.reason,
                                                  created_by = excluded.created_by, created_at = excluded.created_at""",
            cmP("u" to user, "until" to cmT(until), "reason" to reason, "by" to actor.value, "now" to cmT(now)),
        )
    }

    override fun deleteSanction(user: UUID) {
        named.update("delete from community_sanction where user_id = :u", cmP("u" to user))
    }
}
