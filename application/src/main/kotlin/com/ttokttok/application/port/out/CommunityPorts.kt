package com.ttokttok.application.port.out

import com.ttokttok.application.port.`in`.CommunityReportView
import com.ttokttok.application.port.`in`.LikeResult
import com.ttokttok.application.port.`in`.MyCommunityComment
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.util.UUID

/** 게시판 위치. key 문자열과 1:1 */
data class BoardSpec(val scope: String, val audience: String, val institutionId: UUID?) {
    val key: String
        get() = if (scope == "LOUNGE") "L-$audience" else "I-$institutionId-$audience"

    companion object {
        private val INST = Regex("^I-([0-9a-fA-F-]{36})-(STAFF|PARENT)$")

        fun parse(key: String): BoardSpec? = when (key) {
            "L-OWNER", "L-STAFF", "L-PARENT" -> BoardSpec("LOUNGE", key.removePrefix("L-"), null)
            else -> INST.matchEntire(key)?.let { m ->
                runCatching { UUID.fromString(m.groupValues[1]) }.getOrNull()?.let { BoardSpec("INSTITUTION", m.groupValues[2], it) }
            }
        }
    }
}

/** 커뮤니티 자격 판단 재료 */
data class CommunityAffiliation(
    val institutionId: UUID,
    val institutionName: String,
    /** OWNER · ADMIN · TEACHER · PARENT */
    val role: String,
    val address: String?,
)

data class PostRow(
    val id: UUID,
    val board: BoardSpec,
    val authorId: UUID,
    val authorRole: String,
    val authorRegion: String?,
    val category: String,
    val title: String,
    val body: String,
    val likeCount: Int,
    val commentCount: Int,
    val hidden: String?,
    val deleted: Boolean,
    val createdAt: Instant,
)

data class CommentRow(
    val id: UUID,
    val postId: UUID,
    val parentId: UUID?,
    val authorId: UUID,
    val anonNo: Int,
    val body: String,
    val likeCount: Int,
    val hidden: String?,
    val deleted: Boolean,
    val createdAt: Instant,
)

data class CommunityMemberRow(val rulesAgreedAt: Instant?, val activitySeenAt: Instant?)
data class SanctionRow(val untilAt: Instant?, val reason: String)

interface CommunityPort {
    // 자격 (샘플 기관·정지 기관 제외)
    fun staffAffiliations(user: UserId): List<CommunityAffiliation>
    /** 앱에 연결된(LINKED) 재원·휴원 자녀가 다니는 기관 */
    fun parentAffiliations(user: UserId): List<CommunityAffiliation>

    fun member(user: UserId): CommunityMemberRow?
    fun agreeRules(user: UserId, now: Instant)
    fun markActivitySeen(user: UserId, now: Instant)
    fun sanction(user: UserId): SanctionRow?
    fun newCommentsOnMyPosts(user: UserId, since: Instant?): Int
    fun recentPostCount(user: UserId, since: Instant): Int
    fun recentCommentCount(user: UserId, since: Instant): Int

    /** viewer 의 숨김 글은 포함, 남의 숨김·삭제 글은 제외. popularSince 가 있으면 그 뒤 글만 공감×2+댓글 순 */
    fun posts(board: BoardSpec, viewer: UserId, category: String?, q: String?, popularSince: Instant?, offset: Int, limit: Int): List<PostRow>
    fun findPost(id: UUID): PostRow?
    fun insertPost(board: BoardSpec, author: UserId, role: String, region: String?, category: String, title: String, body: String, now: Instant): UUID
    fun updatePost(id: UUID, category: String, title: String, body: String, now: Instant)
    fun deletePost(id: UUID, now: Instant)

    fun comments(postId: UUID): List<CommentRow>
    fun findComment(id: UUID): CommentRow?
    /** 글쓴이는 0, 나머지는 처음 댓글 단 순서대로 1, 2, … (글마다 고정) */
    fun anonNo(postId: UUID, user: UserId, isAuthor: Boolean): Int
    fun insertComment(postId: UUID, parentId: UUID?, author: UserId, anonNo: Int, body: String, now: Instant): UUID
    fun deleteComment(id: UUID, now: Instant)

    fun liked(targetType: String, targetId: UUID, user: UserId): Boolean
    fun likedComments(postId: UUID, user: UserId): Set<UUID>
    fun toggleLike(targetType: String, targetId: UUID, user: UserId, now: Instant): LikeResult

    /** 같은 사람이 이미 신고했으면 null, 아니면 신고 뒤 서로 다른 신고자 수 */
    fun report(targetType: String, targetId: UUID, reporter: UserId, reason: String, memo: String?, now: Instant): Int?
    fun setHidden(targetType: String, targetId: UUID, hidden: String?)

    fun myPosts(user: UserId, offset: Int, limit: Int): List<PostRow>
    fun myComments(user: UserId, offset: Int, limit: Int): List<MyCommunityComment>

    // COM-008
    fun reports(open: Boolean, offset: Int, limit: Int): List<CommunityReportView>
    fun resolveReports(targetType: String, targetId: UUID, actor: UserId, resolution: String, now: Instant)
    fun authorOf(targetType: String, targetId: UUID): UUID?
    fun setSanction(user: UUID, until: Instant?, reason: String, actor: UserId, now: Instant)
    fun deleteSanction(user: UUID)
}
