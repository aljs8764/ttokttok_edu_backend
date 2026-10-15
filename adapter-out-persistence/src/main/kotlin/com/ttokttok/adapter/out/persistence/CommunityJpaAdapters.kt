package com.ttokttok.adapter.out.persistence

import com.ttokttok.adapter.out.persistence.entity.CommunityAnonEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityAnonKey
import com.ttokttok.adapter.out.persistence.entity.CommunityCommentEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityLikeKey
import com.ttokttok.adapter.out.persistence.entity.CommunityMemberEntity
import com.ttokttok.adapter.out.persistence.entity.CommunityPostEntity
import com.ttokttok.adapter.out.persistence.entity.CommunitySanctionEntity
import com.ttokttok.adapter.out.persistence.repository.CommunityAnonJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunityCommentJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunityLikeJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunityMemberJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunityPostJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunityReportJpaRepository
import com.ttokttok.adapter.out.persistence.repository.CommunitySanctionJpaRepository
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 커뮤니티에 노출해도 되는 기관 (체험 샘플·정지·승인 대기 제외) */
private const val LIVE_INSTITUTION = "i.isSample = false and i.serviceStatus = 'ACTIVE'"

@Component
class CommunityJpaAdapter(
    private val posts: CommunityPostJpaRepository,
    private val anons: CommunityAnonJpaRepository,
    private val comments: CommunityCommentJpaRepository,
    private val likes: CommunityLikeJpaRepository,
    private val reports: CommunityReportJpaRepository,
    private val sanctions: CommunitySanctionJpaRepository,
    private val members: CommunityMemberJpaRepository,
    private val q: JpaQueries,
) : CommunityPort {

    private fun CommunityPostEntity.toRow() = PostRow(
        id = id,
        board = BoardSpec(scope, audience, institutionId),
        authorId = authorId, authorRole = authorRole, authorRegion = authorRegion,
        category = category, title = title, body = body,
        likeCount = likeCount, commentCount = commentCount,
        hidden = hidden, deleted = deletedAt != null, createdAt = createdAt,
    )

    private fun CommunityCommentEntity.toRow() = CommentRow(
        id = id, postId = postId, parentId = parentId, authorId = authorId,
        anonNo = anonNo, body = body, likeCount = likeCount,
        hidden = hidden, deleted = deletedAt != null, createdAt = createdAt,
    )

    // ───────── 자격 ─────────
    override fun staffAffiliations(user: UserId): List<CommunityAffiliation> = q.jpqlRows(
        """select i.id, i.name, m.role, i.address
             from MembershipEntity m join InstitutionEntity i on i.id = m.institutionId
            where m.userId = :u and m.role in ('OWNER', 'ADMIN', 'TEACHER') and $LIVE_INSTITUTION
            order by i.name""",
        params("u" to user.value),
    ).map { CommunityAffiliation(it[0] as UUID, it[1] as String, it[2] as String, it[3] as String?) }

    override fun parentAffiliations(user: UserId): List<CommunityAffiliation> = q.jpqlRows(
        """select distinct i.id, i.name, i.address
             from GuardianEntity g join StudentEntity s on s.id = g.studentId
                                   join InstitutionEntity i on i.id = g.institutionId
            where g.userId = :u and g.linkStatus = 'LINKED' and s.status <> 'WITHDRAWN' and $LIVE_INSTITUTION
            order by i.name""",
        params("u" to user.value),
    ).map { CommunityAffiliation(it[0] as UUID, it[1] as String, "PARENT", it[2] as String?) }

    override fun member(user: UserId): CommunityMemberRow? =
        members.findByIdOrNull(user.value)?.let { CommunityMemberRow(it.rulesAgreedAt, it.activitySeenAt) }

    override fun agreeRules(user: UserId, now: Instant) {
        val m = members.findByIdOrNull(user.value) ?: CommunityMemberEntity(user.value)
        if (m.rulesAgreedAt == null) m.rulesAgreedAt = now
        members.save(m)
    }

    override fun markActivitySeen(user: UserId, now: Instant) {
        val m = members.findByIdOrNull(user.value) ?: CommunityMemberEntity(user.value)
        m.activitySeenAt = now
        members.save(m)
    }

    override fun sanction(user: UserId): SanctionRow? = sanctions.findByIdOrNull(user.value)?.let { SanctionRow(it.untilAt, it.reason) }

    override fun newCommentsOnMyPosts(user: UserId, since: Instant?): Int {
        val p = params("u" to user.value)
        val sinceClause = if (since != null) {
            p["since"] = since
            " and c.createdAt > :since"
        } else ""
        return q.jpqlCount(
            """select count(c) from CommunityCommentEntity c, CommunityPostEntity p
                where p.id = c.postId and p.authorId = :u and p.deletedAt is null and c.authorId <> :u and c.deletedAt is null$sinceClause""",
            p,
        ).toInt()
    }

    override fun recentPostCount(user: UserId, since: Instant): Int = posts.countByAuthorIdAndCreatedAtAfter(user.value, since).toInt()

    override fun recentCommentCount(user: UserId, since: Instant): Int = comments.countByAuthorIdAndCreatedAtAfter(user.value, since).toInt()

    // ───────── 글 ─────────
    override fun posts(board: BoardSpec, viewer: UserId, category: String?, q: String?, popularSince: Instant?, offset: Int, limit: Int): List<PostRow> {
        val where = StringBuilder(
            """p.scope = :scope and p.audience = :aud and p.deletedAt is null
               and (p.hidden is null or p.authorId = :viewer)""",
        )
        val p = params("scope" to board.scope, "aud" to board.audience, "viewer" to viewer.value)
        if (board.institutionId == null) where.append(" and p.institutionId is null")
        else { where.append(" and p.institutionId = :inst"); p["inst"] = board.institutionId }
        if (category != null) { where.append(" and p.category = :cat"); p["cat"] = category }
        if (q != null) {
            where.append(" and (lower(p.title) like lower(:q) escape '\\' or lower(p.body) like lower(:q) escape '\\')")
            p["q"] = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val order = if (popularSince != null) {
            where.append(" and p.createdAt >= :since"); p["since"] = popularSince
            "(p.likeCount * 2 + p.commentCount) desc, p.createdAt desc"
        } else "p.createdAt desc"
        return this.q.jpql("select p from CommunityPostEntity p where $where order by $order", CommunityPostEntity::class.java, p, limit = limit, offset = offset)
            .map { it.toRow() }
    }

    override fun findPost(id: UUID): PostRow? = posts.findByIdOrNull(id)?.toRow()

    override fun insertPost(board: BoardSpec, author: UserId, role: String, region: String?, category: String, title: String, body: String, now: Instant): UUID {
        val id = Uuid7.next()
        posts.save(
            CommunityPostEntity(
                id, board.scope, board.audience, board.institutionId, author.value, role, region, category, title, body,
                createdAt = now, updatedAt = now,
            ),
        )
        // 글쓴이 익명 번호 0 고정
        anons.save(CommunityAnonEntity(id, author.value, 0))
        return id
    }

    override fun updatePost(id: UUID, category: String, title: String, body: String, now: Instant) {
        val e = posts.findByIdOrNull(id) ?: return
        e.category = category
        e.title = title
        e.body = body
        e.updatedAt = now
        posts.save(e)
    }

    override fun deletePost(id: UUID, now: Instant) {
        posts.softDelete(id, now)
    }

    // ───────── 댓글 ─────────
    override fun comments(postId: UUID): List<CommentRow> {
        val rows = comments.findByPostId(postId).map { it.toRow() }
        // uuid7 은 시간순이지만 정렬 안정성을 위해 부모 생성 순 → 자식 생성 순으로 묶는다
        val parents = rows.filter { it.parentId == null }.sortedBy { it.createdAt }
        val children = rows.filter { it.parentId != null }.groupBy { it.parentId }
        return parents.flatMap { p -> listOf(p) + (children[p.id]?.sortedBy { it.createdAt } ?: emptyList()) }
    }

    override fun findComment(id: UUID): CommentRow? = comments.findByIdOrNull(id)?.toRow()

    override fun anonNo(postId: UUID, user: UserId, isAuthor: Boolean): Int {
        anons.findByIdOrNull(CommunityAnonKey(postId, user.value))?.let { return it.anonNo }
        if (isAuthor) {
            anons.insertIfAbsent(postId, user.value, 0)
            return 0
        }
        // 글 행을 잠가 같은 글의 번호 발급을 직렬화
        posts.lockById(postId)
        val next = anons.maxAnonNo(postId) + 1
        anons.save(CommunityAnonEntity(postId, user.value, next))
        return next
    }

    override fun insertComment(postId: UUID, parentId: UUID?, author: UserId, anonNo: Int, body: String, now: Instant): UUID {
        val id = Uuid7.next()
        comments.save(CommunityCommentEntity(id, postId, parentId, author.value, anonNo, body, createdAt = now))
        posts.addCommentCount(postId, 1)
        return id
    }

    override fun deleteComment(id: UUID, now: Instant) {
        val postId = comments.findByIdOrNull(id)?.postId ?: return
        if (comments.softDelete(id, now) > 0) posts.addCommentCount(postId, -1)
    }

    // ───────── 공감 ─────────
    override fun liked(targetType: String, targetId: UUID, user: UserId): Boolean = likes.existsById(CommunityLikeKey(targetType, targetId, user.value))

    override fun likedComments(postId: UUID, user: UserId): Set<UUID> = comments.likedIn(postId, user.value).toSet()

    override fun toggleLike(targetType: String, targetId: UUID, user: UserId, now: Instant): LikeResult {
        val removed = likes.remove(targetType, targetId, user.value)
        val liked = if (removed > 0) {
            false
        } else {
            likes.insertIfAbsent(targetType, targetId, user.value, now)
            true
        }
        val delta = if (liked) 1 else -1
        val count = if (targetType == "POST") {
            posts.addLikeCount(targetId, delta)
            posts.findByIdOrNull(targetId)?.likeCount
        } else {
            comments.addLikeCount(targetId, delta)
            comments.findByIdOrNull(targetId)?.likeCount
        } ?: 0
        return LikeResult(liked, count)
    }

    // ───────── 신고 ─────────
    override fun report(targetType: String, targetId: UUID, reporter: UserId, reason: String, memo: String?, now: Instant): Int? {
        // 같은 사람이 같은 대상을 다시 신고하면(유니크 키) 0 행 → null
        val inserted = q.update(
            """insert into community_report (id, target_type, target_id, reporter_id, reason, memo, created_at)
               values (:id, :t, :target, :u, :reason, :memo, :now)
               on conflict (target_type, target_id, reporter_id) do nothing""",
            params("id" to Uuid7.next(), "t" to targetType, "target" to targetId, "u" to reporter.value, "reason" to reason, "memo" to typed(memo), "now" to now),
        )
        if (inserted == 0) return null
        return if (targetType == "POST") {
            posts.incrementReportCount(targetId)
            posts.findByIdOrNull(targetId)?.reportCount
        } else {
            comments.incrementReportCount(targetId)
            comments.findByIdOrNull(targetId)?.reportCount
        } ?: 0
    }

    override fun setHidden(targetType: String, targetId: UUID, hidden: String?) {
        if (targetType == "POST") posts.setHidden(targetId, hidden) else comments.setHidden(targetId, hidden)
    }

    // ───────── 내 활동 ─────────
    override fun myPosts(user: UserId, offset: Int, limit: Int): List<PostRow> =
        q.jpql(
            "select p from CommunityPostEntity p where p.authorId = :u and p.deletedAt is null order by p.createdAt desc",
            CommunityPostEntity::class.java, params("u" to user.value), limit = limit, offset = offset,
        ).map { it.toRow() }

    override fun myComments(user: UserId, offset: Int, limit: Int): List<MyCommunityComment> = q.jpqlRows(
        """select c, p.title from CommunityCommentEntity c, CommunityPostEntity p
            where p.id = c.postId and c.authorId = :u and p.deletedAt is null
            order by c.createdAt desc""",
        params("u" to user.value), limit = limit, offset = offset,
    ).map { r ->
        val c = r[0] as CommunityCommentEntity
        val deleted = c.deletedAt != null
        MyCommunityComment(
            id = c.id, postId = c.postId, postTitle = r[1] as String,
            body = if (deleted) null else c.body, deleted = deleted, createdAt = c.createdAt,
        )
    }

    // ───────── COM-008 운영사 ─────────
    override fun reports(open: Boolean, offset: Int, limit: Int): List<CommunityReportView> {
        // 대상(글·댓글)별로 묶는다. open=true 면 열린 신고가 하나라도 있는 대상, false 면 모두 처리된 대상 (bool_or 집계라 네이티브)
        val groups = q.rows(
            """select r.target_type, r.target_id, count(*) as cnt, min(r.created_at) as first_at, bool_or(r.status = 'OPEN') as is_open
                 from community_report r
                group by r.target_type, r.target_id
               having bool_or(r.status = 'OPEN') = :open
                order by min(r.created_at) ${if (open) "asc" else "desc"}
               offset :offset limit :limit""",
            params("open" to open, "offset" to offset, "limit" to limit),
        )
        val now = Instant.now()
        return groups.mapNotNull { g ->
            val type = g.str("target_type")
            val target = g.uuid("target_id")
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
            val reasons = reports.reasonCounts(type, target).associate { (it[0] as String) to (it[1] as Number).toInt() }
            val memos = reports.memos(type, target, PageRequest.of(0, 20))
            val sanctioned = sanctions.countActive(authorId, now) > 0
            CommunityReportView(
                targetType = type, targetId = target, postId = post.id, boardKey = post.board.key,
                title = title, body = body, hidden = hidden, deleted = deleted, reportCount = g.int("cnt"),
                reasons = reasons, memos = memos, firstReportedAt = g.instant("first_at"), open = g.bool("is_open"), authorSanctioned = sanctioned,
            )
        }
    }

    override fun resolveReports(targetType: String, targetId: UUID, actor: UserId, resolution: String, now: Instant) {
        reports.resolve(targetType, targetId, actor.value, resolution, now)
    }

    override fun authorOf(targetType: String, targetId: UUID): UUID? =
        if (targetType == "POST") posts.findByIdOrNull(targetId)?.authorId else comments.findByIdOrNull(targetId)?.authorId

    override fun setSanction(user: UUID, until: Instant?, reason: String, actor: UserId, now: Instant) {
        val s = sanctions.findByIdOrNull(user) ?: CommunitySanctionEntity(user, until, reason, actor.value, now)
        s.untilAt = until
        s.reason = reason
        s.createdBy = actor.value
        s.createdAt = now
        sanctions.save(s)
    }

    override fun deleteSanction(user: UUID) {
        if (sanctions.existsById(user)) sanctions.deleteById(user)
    }
}
