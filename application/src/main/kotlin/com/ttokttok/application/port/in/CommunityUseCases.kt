package com.ttokttok.application.port.`in`

import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

// COM-001~009 익명 커뮤니티. 기관 헤더 없이 사용자 단위로 쓴다 (원장·교사·학부모 공통).
// 응답에는 작성자 user id·이름을 절대 넣지 않는다 — mine(본인 여부)과 익명 번호만.

/**
 * 게시판 키: L-OWNER · L-STAFF · L-PARENT (전국 라운지), I-{기관ID}-STAFF · I-{기관ID}-PARENT (우리 기관)
 */
data class CommunityBoard(
    val key: String,
    val scope: String,
    val audience: String,
    val institutionId: UUID?,
    val institutionName: String?,
)

data class CommunityPostSummary(
    val id: UUID,
    val boardKey: String,
    val category: String,
    val title: String,
    val preview: String,
    /** 라운지에서만: 작성자 역할(OWNER·ADMIN·TEACHER·PARENT)·시도. 기관 게시판은 null */
    val authorRole: String?,
    val authorRegion: String?,
    val likeCount: Int,
    val commentCount: Int,
    val mine: Boolean,
    /** 본인 글이 숨김 처리됐을 때만 REPORTED/MODERATED (남의 숨김 글은 목록에 안 나옴) */
    val hidden: String?,
    val createdAt: Instant,
)

data class CommunityComment(
    val id: UUID,
    val parentId: UUID?,
    /** 0 = 글쓴이 */
    val anonNo: Int,
    val body: String?,
    val likeCount: Int,
    val liked: Boolean,
    val mine: Boolean,
    val deleted: Boolean,
    /** 숨김(신고·운영사) — body 는 null */
    val hidden: Boolean,
    val createdAt: Instant,
)

data class CommunityPostDetail(
    val post: CommunityPostSummary,
    val body: String,
    val liked: Boolean,
    val comments: List<CommunityComment>,
)

data class CommunityPage(val items: List<CommunityPostSummary>, val page: Int, val hasMore: Boolean)

data class CommunityMe(
    val rulesAgreed: Boolean,
    /** 이용 제한 중이면 끝나는 시각 (영구면 permanent=true) */
    val sanctionedUntil: Instant?,
    val sanctionPermanent: Boolean,
    val sanctionReason: String?,
    /** 마지막으로 '내 활동'을 본 뒤 내 글에 달린 새 댓글 수 */
    val newComments: Int,
)

data class LikeResult(val liked: Boolean, val likeCount: Int)

interface CommunityUseCase {
    data class WritePost(val category: String, val title: String, val body: String)

    fun boards(actor: UserId): List<CommunityBoard>
    fun me(actor: UserId): CommunityMe
    fun agreeRules(actor: UserId): CommunityMe

    /** sort = LATEST | POPULAR(최근 7일, 공감×2+댓글) */
    fun posts(actor: UserId, boardKey: String, sort: String, category: String?, q: String?, page: Int): CommunityPage
    fun post(actor: UserId, postId: UUID): CommunityPostDetail
    fun write(actor: UserId, boardKey: String, command: WritePost): CommunityPostDetail
    fun edit(actor: UserId, postId: UUID, command: WritePost): CommunityPostDetail
    fun deletePost(actor: UserId, postId: UUID)

    fun comment(actor: UserId, postId: UUID, parentId: UUID?, body: String): CommunityPostDetail
    fun deleteComment(actor: UserId, commentId: UUID)

    /** targetType = POST | COMMENT */
    fun toggleLike(actor: UserId, targetType: String, targetId: UUID): LikeResult
    /** reason = ABUSE · PRIVACY · SPAM · OBSCENE · OTHER */
    fun report(actor: UserId, targetType: String, targetId: UUID, reason: String, memo: String?)

    fun myPosts(actor: UserId, page: Int): CommunityPage
    fun myComments(actor: UserId, page: Int): List<MyCommunityComment>
}

data class MyCommunityComment(val id: UUID, val postId: UUID, val postTitle: String, val body: String?, val deleted: Boolean, val createdAt: Instant)

// ───────── COM-008 운영사 신고 처리 ─────────
data class CommunityReportView(
    val targetType: String,
    val targetId: UUID,
    val postId: UUID,
    val boardKey: String,
    val title: String?,
    val body: String?,
    val hidden: String?,
    val deleted: Boolean,
    val reportCount: Int,
    /** 사유별 건수 */
    val reasons: Map<String, Int>,
    val memos: List<String>,
    val firstReportedAt: Instant,
    val open: Boolean,
    /** 작성자가 이용 제한 중인지 (누구인지는 보여 주지 않음) */
    val authorSanctioned: Boolean,
)

interface CommunityModerationUseCase {
    fun reports(actor: UserId, open: Boolean, page: Int): List<CommunityReportView>
    /** action = HIDE · RESTORE · DELETE · DISMISS(신고만 닫음). 모두 해당 대상의 열린 신고를 처리 완료로 */
    fun resolve(actor: UserId, targetType: String, targetId: UUID, action: String)
    /** days = 1·7·30, null = 영구. 대상 글·댓글의 작성자에게 적용 */
    fun sanction(actor: UserId, targetType: String, targetId: UUID, days: Int?, reason: String)
    fun liftSanction(actor: UserId, targetType: String, targetId: UUID)
}
