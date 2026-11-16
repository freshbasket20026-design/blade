package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@JsonClass(generateAdapter = true)
data class AuthSession(
    @Json(name = "access_token") val accessToken: String = "",
    @Json(name = "refresh_token") val refreshToken: String = "",
    @Json(name = "expires_in") val expiresIn: Long = 3600,
    @Json(name = "user") val user: AuthUserDto? = null
)

@JsonClass(generateAdapter = true)
data class AuthUserDto(
    val id: String = "",
    val email: String? = null,
    val phone: String? = null
)

@JsonClass(generateAdapter = true)
data class SignInPasswordRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class SignUpRequest(
    val email: String,
    val password: String,
    val data: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class RefreshTokenRequest(
    @Json(name = "refresh_token") val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class IdTokenRequest(
    val provider: String = "google",
    @Json(name = "id_token") val idToken: String
)

@JsonClass(generateAdapter = true)
data class PhoneOtpRequest(
    val phone: String
)

@JsonClass(generateAdapter = true)
data class VerifyOtpRequest(
    val phone: String,
    val token: String,
    val type: String = "sms"
)

@JsonClass(generateAdapter = true)
data class RecoverPasswordRequest(
    val email: String
)

@JsonClass(generateAdapter = true)
data class UserProfile(
    val id: String = "",
    val username: String = "",
    @Json(name = "display_name") val displayName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val bio: String = "",
    @Json(name = "avatar_url") val avatarUrl: String = "",
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "is_verified") val isVerified: Boolean = false,
    @Json(name = "is_private") val isPrivate: Boolean = false,
    val role: String = "user",
    @Json(name = "followers_count") val followersCount: Int = 0,
    @Json(name = "following_count") val followingCount: Int = 0,
    @Json(name = "total_likes") val totalLikes: Int = 0,
    @Json(name = "coins_balance") val coinsBalance: Int = 100,
    @Json(name = "earnings_usd") val earningsUsd: Double = 0.0,
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class UserProfileUpsert(
    val id: String,
    val username: String,
    @Json(name = "display_name") val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    val bio: String = "",
    @Json(name = "avatar_url") val avatarUrl: String = "",
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "is_private") val isPrivate: Boolean = false
)

@JsonClass(generateAdapter = true)
data class VideoItem(
    val id: String = "",
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "video_url") val videoUrl: String = "",
    @Json(name = "thumbnail_url") val thumbnailUrl: String = "",
    val title: String = "",
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val category: String = "All",
    val location: String = "",
    val privacy: String = "public",
    @Json(name = "sound_id") val soundId: String? = null,
    @Json(name = "sound_title") val soundTitle: String = "Original Sound",
    @Json(name = "filter_name") val filterName: String = "Normal",
    @Json(name = "overlay_text") val overlayText: String = "",
    @Json(name = "trim_start_ms") val trimStartMs: Long = 0L,
    @Json(name = "trim_end_ms") val trimEndMs: Long = 0L,
    @Json(name = "duration_ms") val durationMs: Long = 0L,
    @Json(name = "views_count") val viewsCount: Int = 0,
    @Json(name = "likes_count") val likesCount: Int = 0,
    @Json(name = "comments_count") val commentsCount: Int = 0,
    @Json(name = "shares_count") val sharesCount: Int = 0,
    @Json(name = "saves_count") val savesCount: Int = 0,
    @Json(name = "is_sponsored") val isSponsored: Boolean = false,
    @Json(name = "sponsor_label") val sponsorLabel: String = "",
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "users") val creator: UserProfile? = null,
    // Client-side hydrated flags
    @Transient val isLiked: Boolean = false,
    @Transient val isSaved: Boolean = false,
    @Transient val isFollowingCreator: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CreateVideoRequest(
    @Json(name = "user_id") val userId: String,
    @Json(name = "video_url") val videoUrl: String,
    @Json(name = "thumbnail_url") val thumbnailUrl: String = "",
    val title: String = "",
    val caption: String,
    val hashtags: List<String>,
    val category: String,
    val location: String,
    val privacy: String,
    @Json(name = "sound_id") val soundId: String? = null,
    @Json(name = "sound_title") val soundTitle: String = "Original Sound",
    @Json(name = "filter_name") val filterName: String = "Normal",
    @Json(name = "overlay_text") val overlayText: String = "",
    @Json(name = "trim_start_ms") val trimStartMs: Long = 0L,
    @Json(name = "trim_end_ms") val trimEndMs: Long = 0L,
    @Json(name = "duration_ms") val durationMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class CreateVideoLegacyRequest(
    @Json(name = "user_id") val userId: String,
    @Json(name = "video_url") val videoUrl: String,
    @Json(name = "thumbnail_url") val thumbnailUrl: String = "",
    val caption: String,
    val hashtags: List<String>,
    val category: String,
    val location: String,
    val privacy: String,
    @Json(name = "sound_id") val soundId: String? = null,
    @Json(name = "sound_title") val soundTitle: String = "Original Sound",
    @Json(name = "filter_name") val filterName: String = "Normal",
    @Json(name = "overlay_text") val overlayText: String = "",
    @Json(name = "trim_start_ms") val trimStartMs: Long = 0L,
    @Json(name = "trim_end_ms") val trimEndMs: Long = 0L,
    @Json(name = "duration_ms") val durationMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class CommentItem(
    val id: String = "",
    @Json(name = "video_id") val videoId: String = "",
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "parent_id") val parentId: String? = null,
    val content: String = "",
    @Json(name = "likes_count") val likesCount: Int = 0,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "users") val author: UserProfile? = null
)

@JsonClass(generateAdapter = true)
data class CreateCommentRequest(
    @Json(name = "video_id") val videoId: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "parent_id") val parentId: String? = null,
    val content: String
)

@JsonClass(generateAdapter = true)
data class LikeRecord(
    @Json(name = "video_id") val videoId: String = "",
    @Json(name = "user_id") val userId: String = ""
)

@JsonClass(generateAdapter = true)
data class SavedRecord(
    @Json(name = "video_id") val videoId: String = "",
    @Json(name = "user_id") val userId: String = ""
)

@JsonClass(generateAdapter = true)
data class FollowerRecord(
    @Json(name = "follower_id") val followerId: String = "",
    @Json(name = "following_id") val followingId: String = ""
)

@JsonClass(generateAdapter = true)
data class HashtagItem(
    val id: String = "",
    val tag: String = "",
    @Json(name = "videos_count") val videosCount: Int = 0,
    @Json(name = "views_count") val viewsCount: Long = 0,
    @Json(name = "is_trending") val isTrending: Boolean = true
)

@JsonClass(generateAdapter = true)
data class SoundItem(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    @Json(name = "audio_url") val audioUrl: String = "",
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "duration_seconds") val durationSeconds: Int = 15,
    @Json(name = "usage_count") val usageCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class NotificationItem(
    val id: String = "",
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "actor_id") val actorId: String? = null,
    val type: String = "like",
    val title: String = "",
    val body: String = "",
    @Json(name = "video_id") val videoId: String? = null,
    @Json(name = "is_read") val isRead: Boolean = false,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "users") val actor: UserProfile? = null
)

@JsonClass(generateAdapter = true)
data class ConversationItem(
    val id: String = "",
    @Json(name = "participant_one") val participantOne: String = "",
    @Json(name = "participant_two") val participantTwo: String = "",
    @Json(name = "last_message") val lastMessage: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
    @Transient val otherUser: UserProfile? = null
)

@JsonClass(generateAdapter = true)
data class CreateConversationRequest(
    @Json(name = "participant_one") val participantOne: String,
    @Json(name = "participant_two") val participantTwo: String,
    @Json(name = "last_message") val lastMessage: String = ""
)

@JsonClass(generateAdapter = true)
data class MessageItem(
    val id: String = "",
    @Json(name = "conversation_id") val conversationId: String = "",
    @Json(name = "sender_id") val senderId: String = "",
    val content: String = "",
    @Json(name = "is_read") val isRead: Boolean = false,
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class CreateMessageRequest(
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "sender_id") val senderId: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class GiftItem(
    val id: String = "",
    val name: String = "",
    @Json(name = "icon_name") val iconName: String = "bolt",
    @Json(name = "coin_cost") val coinCost: Int = 10
)

@JsonClass(generateAdapter = true)
data class TransactionItem(
    val id: String = "",
    @Json(name = "sender_id") val senderId: String = "",
    @Json(name = "receiver_id") val receiverId: String? = null,
    @Json(name = "video_id") val videoId: String? = null,
    val type: String = "",
    @Json(name = "coins_amount") val coinsAmount: Int = 0,
    @Json(name = "amount_usd") val amountUsd: Double = 0.0,
    val description: String = "",
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class AdItem(
    val id: String = "",
    val title: String = "",
    @Json(name = "sponsor_name") val sponsorName: String = "",
    @Json(name = "media_url") val mediaUrl: String = "",
    @Json(name = "cta_url") val ctaUrl: String = "",
    val placement: String = "feed",
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class ReportItem(
    val id: String = "",
    @Json(name = "reporter_id") val reporterId: String = "",
    @Json(name = "video_id") val videoId: String? = null,
    @Json(name = "reported_user_id") val reportedUserId: String? = null,
    val reason: String = "",
    val status: String = "pending",
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class CreateReportRequest(
    @Json(name = "reporter_id") val reporterId: String,
    @Json(name = "video_id") val videoId: String? = null,
    @Json(name = "reported_user_id") val reportedUserId: String? = null,
    val reason: String
)

@JsonClass(generateAdapter = true)
data class BlockUserRequest(
    @Json(name = "blocker_id") val blockerId: String,
    @Json(name = "blocked_id") val blockedId: String
)

@JsonClass(generateAdapter = true)
data class BlockedUserRecord(
    @Json(name = "blocker_id") val blockerId: String = "",
    @Json(name = "blocked_id") val blockedId: String = ""
)

@JsonClass(generateAdapter = true)
data class LiveStreamItem(
    val id: String = "",
    @Json(name = "host_id") val hostId: String = "",
    val title: String = "",
    @Json(name = "stream_url") val streamUrl: String = "",
    @Json(name = "viewers_count") val viewersCount: Int = 0,
    @Json(name = "is_live") val isLive: Boolean = true,
    @Json(name = "users") val host: UserProfile? = null
)

data class AdminDashboardStats(
    val totalUsers: Int = 0,
    val totalVideos: Int = 0,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
    val pendingReports: List<ReportItem> = emptyList(),
    val recentUsers: List<UserProfile> = emptyList(),
    val recentVideos: List<VideoItem> = emptyList(),
    val activeAds: List<AdItem> = emptyList(),
    val liveStreams: List<LiveStreamItem> = emptyList()
)
