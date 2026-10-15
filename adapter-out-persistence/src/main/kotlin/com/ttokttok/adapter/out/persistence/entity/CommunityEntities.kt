package com.ttokttok.adapter.out.persistence.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/*
 * COM-001~009 익명 커뮤니티 (V26).
 * 작성자 user_id 는 운영 책임을 위해 저장하지만 API 응답에는 절대 내보내지 않는다.
 */

@Entity @Table(name = "community_post")
class CommunityPostEntity(
    @Id var id: UUID,
    var scope: String,
    var audience: String,
    var institutionId: UUID?,
    var authorId: UUID,
    var authorRole: String,
    var authorRegion: String?,
    var category: String,
    var title: String,
    var body: String,
    var likeCount: Int = 0,
    var commentCount: Int = 0,
    var reportCount: Int = 0,
    var hidden: String? = null,
    var deletedAt: Instant? = null,
    var createdAt: Instant,
    var updatedAt: Instant,
)

/** 글마다 사람별 고정 익명 번호 (0 = 글쓴이) */
@Entity @Table(name = "community_anon") @IdClass(CommunityAnonKey::class)
class CommunityAnonEntity(
    @Id var postId: UUID,
    @Id var userId: UUID,
    var anonNo: Int,
)

data class CommunityAnonKey(var postId: UUID? = null, var userId: UUID? = null) : Serializable

@Entity @Table(name = "community_comment")
class CommunityCommentEntity(
    @Id var id: UUID,
    var postId: UUID,
    var parentId: UUID?,
    var authorId: UUID,
    var anonNo: Int,
    var body: String,
    var likeCount: Int = 0,
    var reportCount: Int = 0,
    var hidden: String? = null,
    var deletedAt: Instant? = null,
    var createdAt: Instant,
)

@Entity @Table(name = "community_like") @IdClass(CommunityLikeKey::class)
class CommunityLikeEntity(
    @Id var targetType: String,
    @Id var targetId: UUID,
    @Id var userId: UUID,
    var createdAt: Instant,
)

data class CommunityLikeKey(var targetType: String? = null, var targetId: UUID? = null, var userId: UUID? = null) : Serializable

@Entity @Table(name = "community_report")
class CommunityReportEntity(
    @Id var id: UUID,
    var targetType: String,
    var targetId: UUID,
    var reporterId: UUID,
    var reason: String,
    var memo: String?,
    var status: String = "OPEN",
    var resolution: String? = null,
    var resolvedBy: UUID? = null,
    var resolvedAt: Instant? = null,
    var createdAt: Instant,
)

/** COM-008 이용 제한 (until_at null = 영구) */
@Entity @Table(name = "community_sanction")
class CommunitySanctionEntity(
    @Id var userId: UUID,
    var untilAt: Instant?,
    var reason: String,
    var createdBy: UUID,
    var createdAt: Instant,
)

/** COM-002 이용 규칙 동의 / COM-009 내 글 확인 시각 */
@Entity @Table(name = "community_member")
class CommunityMemberEntity(
    @Id var userId: UUID,
    var rulesAgreedAt: Instant? = null,
    var activitySeenAt: Instant? = null,
)
