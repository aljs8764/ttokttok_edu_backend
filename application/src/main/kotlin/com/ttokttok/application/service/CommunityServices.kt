package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.CommunityBoard
import com.ttokttok.application.port.`in`.CommunityComment
import com.ttokttok.application.port.`in`.CommunityMe
import com.ttokttok.application.port.`in`.CommunityModerationUseCase
import com.ttokttok.application.port.`in`.CommunityPage
import com.ttokttok.application.port.`in`.CommunityPostDetail
import com.ttokttok.application.port.`in`.CommunityPostSummary
import com.ttokttok.application.port.`in`.CommunityReportView
import com.ttokttok.application.port.`in`.CommunityUseCase
import com.ttokttok.application.port.`in`.LikeResult
import com.ttokttok.application.port.`in`.MyCommunityComment
import com.ttokttok.application.port.out.BoardSpec
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.CommunityAffiliation
import com.ttokttok.application.port.out.CommunityPort
import com.ttokttok.application.port.out.PostRow
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * COM-001~009 익명 커뮤니티.
 *  - 게시판 자격은 매 요청마다 소속(교직원 membership, 학부모 = 앱 연결 자녀)으로 다시 판단한다.
 *  - 작성자 신원은 응답에 넣지 않는다. 라운지는 '역할 · 시도', 기관 게시판은 표시 없음.
 *  - 신고 누적(라운지 5, 기관 3) 자동 숨김, 개인정보 가림·금칙어 차단, 도배 제한.
 */
@Service
class CommunityService(
    private val community: CommunityPort,
    private val clock: ClockPort,
) : CommunityUseCase {

    companion object {
        val CATEGORIES = setOf("FREE", "QUESTION", "INFO", "WORRY")
        val REASONS = setOf("ABUSE", "PRIVACY", "SPAM", "OBSCENE", "OTHER")
        const val PAGE_SIZE = 20
        const val HIDE_AT_LOUNGE = 5
        const val HIDE_AT_INSTITUTION = 3

        private val PHONE = Regex("""(?<!\d)(01[016789]|0\d{1,2})[-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)""")
        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
        /** 욕설·비하 금칙어 (띄어쓰기·기호를 뺀 뒤 포함 여부로 판단). 운영하며 보강 */
        private val BANNED = listOf("씨발", "시발", "ㅅㅂ", "병신", "ㅂㅅ", "개새끼", "새끼야", "좆", "니애미", "느금마", "애미뒤", "창녀", "fuck", "shit")

        /** 전화번호·이메일 가림 (COM-007) */
        fun mask(text: String): String = text
            .replace(PHONE) { m -> m.value.replace(Regex("""\d"""), "*").let { masked -> m.groupValues[1] + masked.drop(m.groupValues[1].length) } }
            .replace(EMAIL, "***@***")

        fun hasBanned(text: String): Boolean {
            val compact = text.lowercase().replace(Regex("""[\s\p{Punct}·ㆍ_~]"""), "")
            return BANNED.any { compact.contains(it) }
        }

        /** 주소 첫 단어 → 시·도 약칭 (서울특별시 → 서울, 경기도 → 경기, 충청북도 → 충북 …) */
        fun region(address: String?): String? {
            val first = address?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val table = mapOf(
                "충청북도" to "충북", "충청남도" to "충남", "전라북도" to "전북", "전북특별자치도" to "전북", "전라남도" to "전남",
                "경상북도" to "경북", "경상남도" to "경남", "강원도" to "강원", "강원특별자치도" to "강원", "제주특별자치도" to "제주",
                "세종특별자치시" to "세종",
            )
            table[first]?.let { return it }
            return first.removeSuffix("특별시").removeSuffix("광역시").removeSuffix("도").takeIf { it.isNotEmpty() }?.take(10)
        }
    }

    // ───────── 자격 ─────────
    private data class Access(val boards: List<CommunityBoard>, val staff: List<CommunityAffiliation>, val parent: List<CommunityAffiliation>)

    private fun access(actor: UserId): Access {
        val staff = community.staffAffiliations(actor)
        val parent = community.parentAffiliations(actor)
        val boards = mutableListOf<CommunityBoard>()
        if (staff.any { it.role == "OWNER" }) boards += CommunityBoard("L-OWNER", "LOUNGE", "OWNER", null, null)
        if (staff.any { it.role == "ADMIN" || it.role == "TEACHER" }) boards += CommunityBoard("L-STAFF", "LOUNGE", "STAFF", null, null)
        if (parent.isNotEmpty()) boards += CommunityBoard("L-PARENT", "LOUNGE", "PARENT", null, null)
        staff.distinctBy { it.institutionId }.sortedBy { it.institutionName }.forEach {
            boards += CommunityBoard(BoardSpec("INSTITUTION", "STAFF", it.institutionId).key, "INSTITUTION", "STAFF", it.institutionId, it.institutionName)
        }
        parent.distinctBy { it.institutionId }.sortedBy { it.institutionName }.forEach {
            boards += CommunityBoard(BoardSpec("INSTITUTION", "PARENT", it.institutionId).key, "INSTITUTION", "PARENT", it.institutionId, it.institutionName)
        }
        return Access(boards, staff, parent)
    }

    private fun requireBoard(actor: UserId, key: String): Pair<BoardSpec, Access> {
        val spec = BoardSpec.parse(key) ?: throw NotFoundException("게시판")
        val a = access(actor)
        if (a.boards.none { it.key == spec.key }) throw ForbiddenException("이 게시판을 이용할 수 없습니다")
        return spec to a
    }

    private fun requirePostAccess(actor: UserId, postId: UUID): Pair<PostRow, Access> {
        val p = community.findPost(postId)?.takeIf { !it.deleted } ?: throw NotFoundException("게시글")
        val a = access(actor)
        if (a.boards.none { it.key == p.board.key }) throw NotFoundException("게시글")
        if (p.hidden != null && p.authorId != actor.value) throw NotFoundException("게시글")
        return p to a
    }

    private fun requireWritable(actor: UserId) {
        val m = community.member(actor)
        if (m?.rulesAgreedAt == null) throw ConflictException("RULES_REQUIRED", "커뮤니티 이용 규칙에 먼저 동의해 주세요")
        community.sanction(actor)?.let { s ->
            if (s.untilAt == null || s.untilAt.isAfter(clock.now())) throw ConflictException("SANCTIONED", "커뮤니티 이용이 제한된 계정입니다")
        }
    }

    private fun summary(p: PostRow, viewer: UserId) = CommunityPostSummary(
        id = p.id, boardKey = p.board.key, category = p.category, title = p.title,
        preview = p.body.replace(Regex("""\s+"""), " ").take(120),
        authorRole = if (p.board.scope == "LOUNGE") p.authorRole else null,
        authorRegion = if (p.board.scope == "LOUNGE") p.authorRegion else null,
        likeCount = p.likeCount, commentCount = p.commentCount, mine = p.authorId == viewer.value,
        hidden = if (p.authorId == viewer.value) p.hidden else null, createdAt = p.createdAt,
    )

    private fun detail(p: PostRow, viewer: UserId): CommunityPostDetail {
        val liked = community.likedComments(p.id, viewer)
        val comments = community.comments(p.id).map { c ->
            val gone = c.deleted || c.hidden != null
            CommunityComment(
                id = c.id, parentId = c.parentId, anonNo = c.anonNo, body = if (gone) null else c.body,
                likeCount = c.likeCount, liked = c.id in liked, mine = c.authorId == viewer.value,
                deleted = c.deleted, hidden = !c.deleted && c.hidden != null, createdAt = c.createdAt,
            )
        }
        return CommunityPostDetail(summary(p, viewer), p.body, community.liked("POST", p.id, viewer), comments)
    }

    private fun cleanPost(command: CommunityUseCase.WritePost): CommunityUseCase.WritePost {
        if (command.category !in CATEGORIES) throw InvalidInputException("INVALID_CATEGORY", "카테고리를 다시 골라 주세요")
        val title = command.title.trim()
        val body = command.body.trim()
        if (title.isEmpty() || title.length > 100) throw InvalidInputException("INVALID_TITLE", "제목은 1~100자입니다")
        if (body.isEmpty() || body.length > 5000) throw InvalidInputException("INVALID_BODY", "본문은 1~5000자입니다")
        if (hasBanned(title) || hasBanned(body)) throw InvalidInputException("INAPPROPRIATE", "사용할 수 없는 표현이 들어 있습니다. 고쳐서 다시 올려 주세요")
        return CommunityUseCase.WritePost(command.category, mask(title), mask(body))
    }

    // ───────── API ─────────
    @Transactional(readOnly = true)
    override fun boards(actor: UserId): List<CommunityBoard> = access(actor).boards

    @Transactional(readOnly = true)
    override fun me(actor: UserId): CommunityMe {
        val m = community.member(actor)
        val s = community.sanction(actor)?.takeIf { it.untilAt == null || it.untilAt.isAfter(clock.now()) }
        return CommunityMe(
            rulesAgreed = m?.rulesAgreedAt != null, sanctionedUntil = s?.untilAt, sanctionPermanent = s != null && s.untilAt == null,
            sanctionReason = s?.reason, newComments = community.newCommentsOnMyPosts(actor, m?.activitySeenAt),
        )
    }

    @Transactional
    override fun agreeRules(actor: UserId): CommunityMe {
        if (access(actor).boards.isEmpty()) throw ForbiddenException("이 게시판을 이용할 수 없습니다")
        community.agreeRules(actor, clock.now())
        return me(actor)
    }

    @Transactional(readOnly = true)
    override fun posts(actor: UserId, boardKey: String, sort: String, category: String?, q: String?, page: Int): CommunityPage {
        val (spec, _) = requireBoard(actor, boardKey)
        if (page !in 0..500) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~50입니다")
        if (category != null && category !in CATEGORIES) throw InvalidInputException("INVALID_CATEGORY", "카테고리를 다시 골라 주세요")
        val query = q?.trim()?.takeIf { it.isNotEmpty() }?.take(50)
        val popularSince = if (sort.uppercase() == "POPULAR") clock.now().minus(Duration.ofDays(7)) else null
        val rows = community.posts(spec, actor, category, query, popularSince, page * PAGE_SIZE, PAGE_SIZE + 1)
        return CommunityPage(rows.take(PAGE_SIZE).map { summary(it, actor) }, page, rows.size > PAGE_SIZE)
    }

    @Transactional(readOnly = true)
    override fun post(actor: UserId, postId: UUID): CommunityPostDetail {
        val (p, _) = requirePostAccess(actor, postId)
        return detail(p, actor)
    }

    @Transactional
    override fun write(actor: UserId, boardKey: String, command: CommunityUseCase.WritePost): CommunityPostDetail {
        val (spec, a) = requireBoard(actor, boardKey)
        requireWritable(actor)
        val now = clock.now()
        if (community.recentPostCount(actor, now.minus(Duration.ofMinutes(10))) >= 5) {
            throw ConflictException("TOO_MANY_POSTS", "글을 너무 자주 올렸습니다. 잠시 후 다시 올려 주세요")
        }
        val c = cleanPost(command)
        // 라운지 작성자 표시: 그 라운지 자격이 되는 소속 중 첫 기관의 역할·지역
        val (role, region) = when (spec.audience) {
            "OWNER" -> a.staff.filter { it.role == "OWNER" }.minBy { it.institutionName }.let { it.role to region(it.address) }
            "STAFF" -> if (spec.scope == "LOUNGE") {
                a.staff.filter { it.role == "ADMIN" || it.role == "TEACHER" }.minBy { it.institutionName }.let { it.role to region(it.address) }
            } else {
                a.staff.first { it.institutionId == spec.institutionId }.let { it.role to null }
            }
            else -> (a.parent.firstOrNull { spec.institutionId == null || it.institutionId == spec.institutionId } ?: a.parent.first())
                .let { "PARENT" to region(it.address) }
        }
        val id = community.insertPost(spec, actor, role, if (spec.scope == "LOUNGE") region else null, c.category, c.title, c.body, now)
        return detail(community.findPost(id)!!, actor)
    }

    @Transactional
    override fun edit(actor: UserId, postId: UUID, command: CommunityUseCase.WritePost): CommunityPostDetail {
        val (p, _) = requirePostAccess(actor, postId)
        if (p.authorId != actor.value) throw ForbiddenException("쓴 사람만 고칠 수 있습니다")
        requireWritable(actor)
        val c = cleanPost(command)
        community.updatePost(postId, c.category, c.title, c.body, clock.now())
        return detail(community.findPost(postId)!!, actor)
    }

    @Transactional
    override fun deletePost(actor: UserId, postId: UUID) {
        val p = community.findPost(postId)?.takeIf { !it.deleted } ?: throw NotFoundException("게시글")
        if (p.authorId != actor.value) throw ForbiddenException("쓴 사람만 지울 수 있습니다")
        community.deletePost(postId, clock.now())
    }

    @Transactional
    override fun comment(actor: UserId, postId: UUID, parentId: UUID?, body: String): CommunityPostDetail {
        val (p, _) = requirePostAccess(actor, postId)
        requireWritable(actor)
        val now = clock.now()
        if (community.recentCommentCount(actor, now.minus(Duration.ofMinutes(1))) >= 10) {
            throw ConflictException("TOO_MANY_COMMENTS", "댓글을 너무 자주 달았습니다. 잠시 후 다시 달아 주세요")
        }
        val text = body.trim()
        if (text.isEmpty() || text.length > 1000) throw InvalidInputException("INVALID_BODY", "댓글은 1~1000자입니다")
        if (hasBanned(text)) throw InvalidInputException("INAPPROPRIATE", "사용할 수 없는 표현이 들어 있습니다. 고쳐서 다시 올려 주세요")
        if (parentId != null) {
            val parent = community.findComment(parentId)
            if (parent == null || parent.postId != postId) throw NotFoundException("댓글")
            if (parent.parentId != null) throw InvalidInputException("INVALID_PARENT", "답글에는 다시 답글을 달 수 없습니다")
        }
        val anon = community.anonNo(postId, actor, p.authorId == actor.value)
        community.insertComment(postId, parentId, actor, anon, mask(text), now)
        return detail(community.findPost(postId)!!, actor)
    }

    @Transactional
    override fun deleteComment(actor: UserId, commentId: UUID) {
        val c = community.findComment(commentId)?.takeIf { !it.deleted } ?: throw NotFoundException("댓글")
        if (c.authorId != actor.value) throw ForbiddenException("쓴 사람만 지울 수 있습니다")
        community.deleteComment(commentId, clock.now())
    }

    private fun targetPost(actor: UserId, targetType: String, targetId: UUID): Pair<PostRow, UUID> = when (targetType) {
        "POST" -> requirePostAccess(actor, targetId).first.let { it to it.authorId }
        "COMMENT" -> {
            val c = community.findComment(targetId)?.takeIf { !it.deleted && it.hidden == null } ?: throw NotFoundException("댓글")
            requirePostAccess(actor, c.postId).first to c.authorId
        }
        else -> throw InvalidInputException("INVALID_TYPE", "대상은 POST 또는 COMMENT 입니다")
    }

    @Transactional
    override fun toggleLike(actor: UserId, targetType: String, targetId: UUID): LikeResult {
        targetPost(actor, targetType, targetId)
        return community.toggleLike(targetType, targetId, actor, clock.now())
    }

    @Transactional
    override fun report(actor: UserId, targetType: String, targetId: UUID, reason: String, memo: String?) {
        val (post, author) = targetPost(actor, targetType, targetId)
        if (author == actor.value) throw InvalidInputException("OWN_CONTENT", "내가 쓴 글은 신고할 수 없습니다")
        if (reason !in REASONS) throw InvalidInputException("INVALID_REASON", "신고 사유를 다시 골라 주세요")
        val m = memo?.trim()?.takeIf { it.isNotEmpty() }
        if (m != null && m.length > 300) throw InvalidInputException("MEMO_TOO_LONG", "메모는 300자까지입니다")
        val count = community.report(targetType, targetId, actor, reason, m, clock.now())
            ?: throw ConflictException("ALREADY_REPORTED", "이미 신고했습니다")
        val threshold = if (post.board.scope == "LOUNGE") HIDE_AT_LOUNGE else HIDE_AT_INSTITUTION
        if (count >= threshold) community.setHidden(targetType, targetId, "REPORTED")
    }

    @Transactional
    override fun myPosts(actor: UserId, page: Int): CommunityPage {
        if (page !in 0..500) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~50입니다")
        if (page == 0) community.markActivitySeen(actor, clock.now())
        val rows = community.myPosts(actor, page * PAGE_SIZE, PAGE_SIZE + 1)
        return CommunityPage(rows.take(PAGE_SIZE).map { summary(it, actor) }, page, rows.size > PAGE_SIZE)
    }

    @Transactional(readOnly = true)
    override fun myComments(actor: UserId, page: Int): List<MyCommunityComment> {
        if (page !in 0..500) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~50입니다")
        return community.myComments(actor, page * PAGE_SIZE, PAGE_SIZE)
    }
}

// ───────── COM-008 운영사 신고 처리 ─────────
@Service
class CommunityModerationService(
    private val guard: PlatformGuard,
    private val community: CommunityPort,
    private val clock: ClockPort,
) : CommunityModerationUseCase {
    private val actions = setOf("HIDE", "RESTORE", "DELETE", "DISMISS")

    private fun type(t: String) = t.uppercase().also {
        if (it != "POST" && it != "COMMENT") throw InvalidInputException("INVALID_TYPE", "대상은 POST 또는 COMMENT 입니다")
    }

    @Transactional(readOnly = true)
    override fun reports(actor: UserId, open: Boolean, page: Int): List<CommunityReportView> {
        guard.require(actor)
        if (page !in 0..500) throw InvalidInputException("INVALID_PAGE", "page는 0 이상, size는 1~50입니다")
        return community.reports(open, page * 30, 30)
    }

    @Transactional
    override fun resolve(actor: UserId, targetType: String, targetId: UUID, action: String) {
        guard.require(actor)
        val t = type(targetType)
        val a = action.uppercase()
        if (a !in actions) throw InvalidInputException("INVALID_ACTION", "처리 방법은 HIDE·RESTORE·DELETE·DISMISS 입니다")
        community.authorOf(t, targetId) ?: throw NotFoundException(if (t == "POST") "게시글" else "댓글")
        val now = clock.now()
        when (a) {
            "HIDE" -> community.setHidden(t, targetId, "MODERATED")
            "RESTORE" -> community.setHidden(t, targetId, null)
            "DELETE" -> if (t == "POST") community.deletePost(targetId, now) else community.deleteComment(targetId, now)
            "DISMISS" -> Unit
        }
        community.resolveReports(t, targetId, actor, a, now)
    }

    @Transactional
    override fun sanction(actor: UserId, targetType: String, targetId: UUID, days: Int?, reason: String) {
        guard.require(actor)
        val t = type(targetType)
        if (days != null && days !in setOf(1, 7, 30)) throw InvalidInputException("INVALID_DAYS", "제한 기간은 1·7·30일 또는 영구입니다")
        val r = reason.trim()
        if (r.isEmpty() || r.length > 200) throw InvalidInputException("INVALID_REASON", "정지 사유는 1~200자입니다")
        val author = community.authorOf(t, targetId) ?: throw NotFoundException(if (t == "POST") "게시글" else "댓글")
        val now = clock.now()
        community.setSanction(author, days?.let { now.plus(Duration.ofDays(it.toLong())) }, r, actor, now)
    }

    @Transactional
    override fun liftSanction(actor: UserId, targetType: String, targetId: UUID) {
        guard.require(actor)
        val author = community.authorOf(type(targetType), targetId) ?: throw NotFoundException("게시글")
        community.deleteSanction(author)
    }
}
