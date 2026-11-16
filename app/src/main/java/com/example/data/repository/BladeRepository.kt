package com.example.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import com.example.core.moderation.ModerationResult
import com.example.core.moderation.VideoContentModerator
import com.example.data.local.AppPreferences
import com.example.data.local.StoredSession
import com.example.data.model.AdItem
import com.example.data.model.AdminDashboardStats
import com.example.data.model.AuthSession
import com.example.data.model.BlockUserRequest
import com.example.data.model.CommentItem
import com.example.data.model.ConversationItem
import com.example.data.model.CreateCommentRequest
import com.example.data.model.CreateConversationRequest
import com.example.data.model.CreateMessageRequest
import com.example.data.model.CreateReportRequest
import com.example.data.model.CreateVideoLegacyRequest
import com.example.data.model.CreateVideoRequest
import com.example.data.model.FollowerRecord
import com.example.data.model.GiftItem
import com.example.data.model.HashtagItem
import com.example.data.model.IdTokenRequest
import com.example.data.model.LikeRecord
import com.example.data.model.LiveStreamItem
import com.example.data.model.MessageItem
import com.example.data.model.NotificationItem
import com.example.data.model.PhoneOtpRequest
import com.example.data.model.RecoverPasswordRequest
import com.example.data.model.RefreshTokenRequest
import com.example.data.model.SavedRecord
import com.example.data.model.SignInPasswordRequest
import com.example.data.model.SignUpRequest
import com.example.data.model.SoundItem
import com.example.data.model.TransactionItem
import com.example.data.model.UserProfile
import com.example.data.model.UserProfileUpsert
import com.example.data.model.VerifyOtpRequest
import com.example.data.model.VideoItem
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.util.UUID

class BladeRepository(
    private val preferences: AppPreferences,
    private val client: SupabaseClient
) {
    val isDarkMode: Flow<Boolean> = preferences.isDarkMode
    val languageCode: Flow<String> = preferences.languageCode
    val sessionFlow: Flow<StoredSession> = preferences.sessionFlow

    suspend fun setDarkMode(enabled: Boolean) = preferences.setDarkMode(enabled)
    suspend fun setLanguageCode(code: String) = preferences.setLanguageCode(code)

    fun isSupabaseConfigured(): Boolean = SupabaseConfig.isConfigured

    private fun extractErrorDetails(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank()) return "" to ""
        return try {
            val json = JSONObject(raw)
            val errorCode = json.optString("error_code")
            val code = errorCode.ifBlank { json.optString("code") }
            val msg = json.optString("msg")
                .ifBlank { json.optString("error_description") }
                .ifBlank { json.optString("message") }
                .ifBlank { json.optString("error") }
            val hint = json.optString("hint")
            val combined = listOf(msg, hint).filter { it.isNotBlank() }.joinToString(" — ")
            code to combined
        } catch (_: Exception) {
            "" to ""
        }
    }

    private fun format404Message(code: String, detail: String, raw: String?): String {
        val lower = "$detail ${raw.orEmpty()}".lowercase()
        return when {
            code == "PGRST205" || lower.contains("could not find the table") || lower.contains("does not exist") ->
                "Database tables not found (HTTP 404). Open Supabase SQL Editor and run supabase/schema.sql."
            code == "PGRST202" || lower.contains("could not find the function") ->
                "Supabase RPC function missing (HTTP 404). Open Supabase SQL Editor and run supabase/schema.sql."
            code == "PGRST200" || lower.contains("could not find a relationship") ->
                "Supabase table relationships need setup (HTTP 404). Run supabase/schema.sql in Supabase SQL Editor."
            lower.contains("bucket not found") ->
                "Supabase Storage bucket missing (HTTP 404). Run supabase/schema.sql to create 'videos' and 'avatars' buckets."
            else ->
                "Supabase endpoint returned HTTP 404. Verify SUPABASE_URL in Secrets is https://<project-ref>.supabase.co and run supabase/schema.sql."
        }
    }

    private fun parseSupabaseError(raw: String?, fallback: String, httpCode: Int? = null): String {
        val (code, detail) = extractErrorDetails(raw)
        if (httpCode == 404 || code == "PGRST205" || code == "PGRST202") {
            return format404Message(code, detail, raw)
        }
        val lower = "$code $detail".lowercase()
        if (code == "email_not_confirmed" || lower.contains("email not confirmed")) {
            return "Email confirmation is enabled in Supabase. Turn OFF 'Confirm email' in Supabase -> Authentication -> Providers -> Email."
        }
        if (code == "invalid_credentials" || lower.contains("invalid login credentials")) {
            return "Invalid email or password. If you haven't registered yet, tap Create Account below."
        }
        return detail.ifBlank { fallback }
    }

    private fun friendlyException(e: Throwable): Throwable {
        if (e is HttpException) {
            val httpCode = e.code()
            val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val (pgCode, detail) = extractErrorDetails(raw)
            val message = when (httpCode) {
                404 -> format404Message(pgCode, detail, raw)
                401 -> detail.ifBlank { "Session expired or invalid Supabase key (HTTP 401). Please sign in again." }
                403 -> detail.ifBlank { "Permission denied by Supabase security policy (HTTP 403)." }
                else -> detail.ifBlank { "Supabase request failed (HTTP $httpCode)." }
            }
            return IllegalStateException(message, e)
        }
        return e
    }

    private suspend fun saveAndHydrateProfile(
        session: AuthSession,
        usernameHint: String? = null,
        displayNameHint: String? = null
    ): Result<UserProfile> {
        val uid = session.user?.id ?: return Result.failure(IllegalStateException("Missing user ID"))
        val email = session.user.email ?: ""
        preferences.saveSession(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = uid,
            email = email
        )
        val existing = runCatching { client.restApi.getUserById("eq.$uid") }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            return Result.success(existing.first())
        }
        val cleanHandle = (usernameHint ?: email.substringBefore("@").ifBlank { "blade_${uid.take(6)}" })
            .lowercase()
            .replace(Regex("[^a-z0-9_.]"), "")
            .ifBlank { "blade_${uid.take(6)}" }
        val upsert = UserProfileUpsert(
            id = uid,
            username = cleanHandle,
            displayName = displayNameHint?.ifBlank { cleanHandle } ?: cleanHandle,
            email = email.ifBlank { null },
            phone = session.user.phone
        )
        val created = try {
            client.restApi.upsertUserProfile(upsert)
        } catch (e: HttpException) {
            if (e.code() == 409) {
                val uniqueHandle = "${cleanHandle}_${uid.take(4)}"
                client.restApi.upsertUserProfile(upsert.copy(username = uniqueHandle))
            } else {
                throw e
            }
        }
        return Result.success(created.firstOrNull() ?: UserProfile(id = uid, username = cleanHandle, displayName = cleanHandle))
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================
    suspend fun signInWithEmail(email: String, password: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val cleanEmail = email.trim().lowercase()
        try {
            val resp = client.authApi.signInWithPassword(SignInPasswordRequest(cleanEmail, password))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Invalid email or password.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signUpWithEmail(
        email: String,
        password: String,
        username: String = "",
        displayName: String = ""
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val cleanEmail = email.trim().lowercase()
        val resolvedHandle = username.trim().ifBlank { cleanEmail.substringBefore("@") }
        val resolvedDisplay = displayName.trim().ifBlank { resolvedHandle }
        try {
            val resp = client.authApi.signUp(
                SignUpRequest(
                    email = cleanEmail,
                    password = password,
                    data = mapOf("username" to resolvedHandle, "display_name" to resolvedDisplay)
                )
            )
            if (!resp.isSuccessful || resp.body() == null) {
                val errRaw = resp.errorBody()?.string()
                val (errCode, errDetail) = extractErrorDetails(errRaw)
                val alreadyExists = errCode == "user_already_exists" ||
                    errDetail.lowercase().contains("already registered") ||
                    errDetail.lowercase().contains("already exists")
                if (alreadyExists) {
                    val loginResp = client.authApi.signInWithPassword(SignInPasswordRequest(cleanEmail, password))
                    if (loginResp.isSuccessful && loginResp.body() != null && loginResp.body()!!.accessToken.isNotBlank()) {
                        return@withContext saveAndHydrateProfile(
                            loginResp.body()!!,
                            usernameHint = resolvedHandle,
                            displayNameHint = resolvedDisplay
                        )
                    }
                    return@withContext Result.failure(
                        IllegalStateException("An account with this email already exists. Check your password or use Forgot Password.")
                    )
                }
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(errRaw, "Unable to create account.", resp.code())
                    )
                )
            }
            val body = resp.body()!!
            if (body.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(body, usernameHint = resolvedHandle, displayNameHint = resolvedDisplay)
            }
            // Email verification is off: immediately sign in so user enters the app right away
            val loginResp = client.authApi.signInWithPassword(SignInPasswordRequest(cleanEmail, password))
            if (loginResp.isSuccessful && loginResp.body() != null && loginResp.body()!!.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(
                    loginResp.body()!!,
                    usernameHint = resolvedHandle,
                    displayNameHint = resolvedDisplay
                )
            }
            val errMsg = parseSupabaseError(
                loginResp.errorBody()?.string(),
                "Turn off 'Confirm email' in Supabase Auth -> Providers -> Email to allow instant sign-up.",
                loginResp.code()
            )
            Result.failure(IllegalStateException(errMsg))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.signInWithIdToken(IdTokenRequest(idToken = idToken))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Google sign-in failed.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun sendPhoneOtp(phoneE164: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.sendPhoneOtp(PhoneOtpRequest(phoneE164.trim()))
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Failed to send OTP.", resp.code())
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun verifyPhoneOtp(phoneE164: String, code: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.verifyPhoneOtp(VerifyOtpRequest(phoneE164.trim(), code.trim()))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Invalid OTP code.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.recoverPassword(RecoverPasswordRequest(email.trim()))
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Could not send reset email.", resp.code())
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun restoreCurrentUser(): UserProfile? = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext null
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext null
        try {
            val list = client.restApi.getUserById("eq.${session.userId}")
            if (list.isNotEmpty()) return@withContext list.first()
        } catch (_: Exception) {
            // Try refreshing expired token
            if (session.refreshToken.isNotBlank()) {
                val refreshResp = runCatching {
                    client.authApi.refreshSession(RefreshTokenRequest(session.refreshToken))
                }.getOrNull()
                if (refreshResp != null && refreshResp.isSuccessful && refreshResp.body() != null) {
                    return@withContext saveAndHydrateProfile(refreshResp.body()!!).getOrNull()
                }
            }
        }
        null
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { client.authApi.signOut() }
        preferences.clearSession()
    }

    // =========================================================================
    // FEED & RECOMMENDATION ENGINE
    // =========================================================================
    suspend fun fetchFeed(forYou: Boolean): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(emptyList())
        }
        try {
            val session = preferences.getSessionSnapshot()
            val blockedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getBlockedUsers("eq.${session.userId}").map { it.blockedId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val followingIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getFollowingIds("eq.${session.userId}").map { it.followingId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val likedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val savedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val rawVideos = if (!forYou) {
                if (followingIds.isEmpty()) emptyList()
                else {
                    try {
                        client.restApi.getVideosByFollowing("in.(${followingIds.joinToString(",")})", limit = 100)
                    } catch (e: HttpException) {
                        if (e.code() == 400 || e.code() == 404) {
                            runCatching {
                                client.restApi.getVideosByFollowing(
                                    userIdInFilter = "in.(${followingIds.joinToString(",")})",
                                    select = "*",
                                    limit = 100
                                )
                            }.getOrElse { throw e }
                        } else throw e
                    }
                }
            } else {
                try {
                    client.restApi.getVideos(limit = 100)
                } catch (e: HttpException) {
                    if (e.code() == 400 || e.code() == 404) {
                        runCatching { client.restApi.getVideos(select = "*", limit = 100) }
                            .getOrElse { throw e }
                    } else throw e
                }
            }

            val visible = rawVideos
                .filter { it.userId !in blockedIds }
                .map { video ->
                    video.copy(
                        isLiked = video.id in likedIds,
                        isSaved = video.id in savedIds,
                        isFollowingCreator = video.userId in followingIds
                    )
                }

            // For You ranking score: personal watch affinity + creator/category/hashtag signals + engagement
            val affinity = if (forYou) preferences.getWatchAffinity() else null
            val likedCategories = visible.filter { it.isLiked || it.isSaved }
                .map { it.category.trim().lowercase() }
                .filter { it.isNotBlank() && it != "all" }
                .groupingBy { it }
                .eachCount()
            val likedCreators = visible.filter { it.isLiked || it.isSaved }
                .map { it.userId }
                .groupingBy { it }
                .eachCount()

            val ranked = if (forYou && affinity != null) {
                visible.sortedByDescending { v ->
                    val catKey = v.category.trim().lowercase()
                    val catWatchScore = (affinity.categories[catKey] ?: 0) * 18.0
                    val creatorWatchScore = (affinity.creators[v.userId] ?: 0) * 24.0
                    val hashtagWatchScore = v.hashtags.sumOf { tag ->
                        affinity.hashtags[tag.trim().removePrefix("#").lowercase()] ?: 0
                    } * 12.0
                    val rewatchScore = (affinity.videos[v.id] ?: 0) * 14.0
                    val serverInterestBoost =
                        (likedCategories[catKey] ?: 0) * 16.0 + (likedCreators[v.userId] ?: 0) * 20.0
                    val followBoost = if (v.userId in followingIds) 25.0 else 0.0
                    val engagement =
                        v.likesCount * 2.0 + v.commentsCount * 3.0 + v.sharesCount * 4.0 + v.viewsCount * 0.5

                    catWatchScore + creatorWatchScore + hashtagWatchScore + rewatchScore +
                        serverInterestBoost + followBoost + engagement
                }
            } else visible

            Result.success(ranked)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun fetchFeedAds(): List<AdItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getActiveAds() }.getOrDefault(emptyList())
    }

    suspend fun recordVideoView(video: VideoItem, weight: Int = 3) = withContext(Dispatchers.IO) {
        preferences.recordWatchAffinity(
            videoId = video.id,
            creatorId = video.userId,
            category = video.category,
            hashtags = video.hashtags,
            weight = weight
        )
        if (!SupabaseConfig.isConfigured) return@withContext
        runCatching { client.restApi.incrementVideoViews(mapOf("p_video_id" to video.id)) }
    }

    suspend fun recordWatchSignal(video: VideoItem, weight: Int) = withContext(Dispatchers.IO) {
        preferences.recordWatchAffinity(
            videoId = video.id,
            creatorId = video.userId,
            category = video.category,
            hashtags = video.hashtags,
            weight = weight
        )
    }

    suspend fun recordVideoShare(videoId: String) = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext
        runCatching { client.restApi.incrementVideoShares(mapOf("p_video_id" to videoId)) }
    }

    suspend fun toggleLike(videoId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to like videos"))
        try {
            val liked = client.restApi.toggleVideoLike(mapOf("p_video_id" to videoId))
            Result.success(liked)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return@withContext runCatching {
                    val alreadyLiked = runCatching {
                        client.restApi.getLikesForUser("eq.${session.userId}").any { it.videoId == videoId }
                    }.getOrDefault(false)
                    if (alreadyLiked) {
                        val resp = client.restApi.deleteLike("eq.$videoId", "eq.${session.userId}")
                        if (!resp.isSuccessful) throw HttpException(resp)
                        false
                    } else {
                        val resp = client.restApi.createLike(LikeRecord(videoId = videoId, userId = session.userId))
                        if (!resp.isSuccessful) throw HttpException(resp)
                        true
                    }
                }.recoverCatching { throw friendlyException(it) }
            }
            Result.failure(friendlyException(e))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleSave(videoId: String, currentlySaved: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to save videos"))
        try {
            if (currentlySaved) {
                val resp = client.restApi.unsaveVideo("eq.$videoId", "eq.${session.userId}")
                if (!resp.isSuccessful) throw HttpException(resp)
                Result.success(false)
            } else {
                val resp = client.restApi.saveVideo(SavedRecord(videoId = videoId, userId = session.userId))
                if (!resp.isSuccessful) throw HttpException(resp)
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleFollow(targetUserId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to follow creators"))
        try {
            val following = client.restApi.toggleFollowUser(mapOf("p_target_id" to targetUserId))
            Result.success(following)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return@withContext runCatching {
                    val alreadyFollowing = runCatching {
                        client.restApi.getFollowingIds("eq.${session.userId}").any { it.followingId == targetUserId }
                    }.getOrDefault(false)
                    if (alreadyFollowing) {
                        val resp = client.restApi.deleteFollow("eq.${session.userId}", "eq.$targetUserId")
                        if (!resp.isSuccessful) throw HttpException(resp)
                        false
                    } else {
                        val resp = client.restApi.createFollow(FollowerRecord(followerId = session.userId, followingId = targetUserId))
                        if (!resp.isSuccessful) throw HttpException(resp)
                        true
                    }
                }.recoverCatching { throw friendlyException(it) }
            }
            Result.failure(friendlyException(e))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // COMMENTS
    // =========================================================================
    suspend fun getComments(videoId: String): Result<List<CommentItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val comments = try {
                client.restApi.getComments("eq.$videoId")
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching { client.restApi.getComments("eq.$videoId", select = "*") }
                        .getOrElse { throw e }
                } else throw e
            }
            Result.success(comments)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun postComment(
        videoId: String,
        content: String,
        parentId: String? = null,
        currentCount: Int
    ): Result<CommentItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to comment"))
        try {
            val created = client.restApi.postComment(
                CreateCommentRequest(
                    videoId = videoId,
                    userId = session.userId,
                    parentId = parentId,
                    content = content.trim()
                )
            )
            runCatching {
                client.restApi.updateVideoCounts("eq.$videoId", mapOf("comments_count" to currentCount + 1))
            }
            Result.success(created.first())
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // UPLOAD & PUBLISH VIDEO
    // =========================================================================
    suspend fun publishVideo(
        contentResolver: ContentResolver,
        videoUri: Uri,
        title: String,
        caption: String,
        category: String,
        location: String,
        privacy: String,
        sound: SoundItem?,
        filterName: String,
        trimStartMs: Long,
        trimEndMs: Long,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ): Result<VideoItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to upload videos"))

        val moderation = VideoContentModerator.moderateVideo(
            contentResolver = contentResolver,
            videoUri = videoUri,
            title = title,
            caption = caption,
            location = location,
            durationMs = durationMs
        )
        if (moderation is ModerationResult.Blocked) {
            return@withContext Result.failure(IllegalStateException(moderation.reason))
        }

        val fileName = "${session.userId}/${UUID.randomUUID()}.mp4"
        val uploadResult = client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = videoUri,
            bucket = "videos",
            objectPath = fileName,
            mimeType = "video/mp4",
            onProgress = onProgress
        )
        if (uploadResult.isFailure) {
            return@withContext Result.failure(uploadResult.exceptionOrNull() ?: IllegalStateException("Upload failed"))
        }
        val videoPublicUrl = uploadResult.getOrThrow()
        val thumbPublicUrl = extractAndUploadThumbnail(
            contentResolver = contentResolver,
            videoUri = videoUri,
            userId = session.userId
        )

        val cleanTitle = title.trim()
        val cleanCaption = caption.trim()
        val combinedTextForTags = "$cleanTitle $cleanCaption"
        val extractedHashtags = Regex("#([A-Za-z0-9_\\u0980-\\u09FF]+)")
            .findAll(combinedTextForTags)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

        try {
            val created = try {
                client.restApi.createVideo(
                    CreateVideoRequest(
                        userId = session.userId,
                        videoUrl = videoPublicUrl,
                        thumbnailUrl = thumbPublicUrl,
                        title = cleanTitle,
                        caption = cleanCaption,
                        hashtags = extractedHashtags,
                        category = category,
                        location = location.trim(),
                        privacy = privacy.lowercase(),
                        soundId = sound?.id,
                        soundTitle = sound?.let { "${it.title} - ${it.artist}" } ?: "Original Sound",
                        filterName = filterName,
                        overlayText = "",
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        durationMs = durationMs
                    )
                )
            } catch (e: HttpException) {
                // Fallback if the Supabase `videos` table does not yet have the `title` column
                if (e.code() == 400 || e.code() == 404) {
                    val mergedCaption = when {
                        cleanTitle.isNotBlank() && cleanCaption.isNotBlank() -> "$cleanTitle\n$cleanCaption"
                        cleanTitle.isNotBlank() -> cleanTitle
                        else -> cleanCaption
                    }
                    client.restApi.createVideoLegacy(
                        CreateVideoLegacyRequest(
                            userId = session.userId,
                            videoUrl = videoPublicUrl,
                            thumbnailUrl = thumbPublicUrl,
                            caption = mergedCaption,
                            hashtags = extractedHashtags,
                            category = category,
                            location = location.trim(),
                            privacy = privacy.lowercase(),
                            soundId = sound?.id,
                            soundTitle = sound?.let { "${it.title} - ${it.artist}" } ?: "Original Sound",
                            filterName = filterName,
                            overlayText = "",
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            durationMs = durationMs
                        )
                    )
                } else {
                    throw e
                }
            }
            extractedHashtags.forEach { tag ->
                runCatching {
                    client.restApi.upsertHashtag(
                        mapOf("tag" to tag, "videos_count" to 1, "views_count" to 1, "is_trending" to true)
                    )
                }
            }
            Result.success(created.first())
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // DISCOVER & SEARCH
    // =========================================================================
    suspend fun searchDiscover(
        query: String,
        category: String,
        hashtag: String?,
        soundId: String?
    ): Result<Triple<List<VideoItem>, List<UserProfile>, List<HashtagItem>>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(Triple(emptyList(), emptyList(), emptyList()))
        }
        try {
            val q = query.trim().removePrefix("#")
            val orVideoWithTitle = if (q.isNotBlank()) "(title.ilike.*$q*,caption.ilike.*$q*,location.ilike.*$q*)" else null
            val orVideoFallback = if (q.isNotBlank()) "(caption.ilike.*$q*,location.ilike.*$q*)" else null
            val catFilter = if (category != "All" && q.isBlank() && hashtag.isNullOrBlank()) "eq.$category" else null
            val tagFilter = hashtag?.takeIf { it.isNotBlank() }?.let { "cs.{${it.lowercase().removePrefix("#")}}" }
            val soundFilter = soundId?.takeIf { it.isNotBlank() }?.let { "eq.$it" }

            val videos = try {
                client.restApi.searchVideos(
                    orFilter = orVideoWithTitle,
                    categoryFilter = catFilter,
                    hashtagFilter = tagFilter,
                    soundIdFilter = soundFilter
                )
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching {
                        client.restApi.searchVideos(
                            orFilter = orVideoFallback,
                            categoryFilter = catFilter,
                            hashtagFilter = tagFilter,
                            soundIdFilter = soundFilter,
                            select = "*"
                        )
                    }.getOrElse { throw e }
                } else throw e
            }
            val users = runCatching {
                if (q.isNotBlank()) {
                    client.restApi.searchUsers("(username.ilike.*$q*,display_name.ilike.*$q*)")
                } else {
                    client.restApi.getUsers(limit = 20)
                }
            }.getOrDefault(emptyList())
            val hashtags = runCatching { client.restApi.getHashtags() }.getOrDefault(emptyList())
            Result.success(Triple(videos, users, hashtags))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getSounds(): List<SoundItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getSounds() }.getOrDefault(emptyList())
    }

    // =========================================================================
    // PROFILE, WALLET & MONETIZATION
    // =========================================================================
    suspend fun getUserProfile(userId: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.failure(IllegalStateException("Supabase not configured"))
        try {
            val users = client.restApi.getUserById("eq.$userId")
            users.firstOrNull()?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("User not found"))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    private suspend fun extractAndUploadThumbnail(
        contentResolver: ContentResolver,
        videoUri: Uri,
        userId: String
    ): String {
        var tempFile: File? = null
        val retriever = MediaMetadataRetriever()
        return try {
            val extractedFile = contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                    ?: return@use null
                File.createTempFile("blade_thumb_", ".jpg").also { file ->
                    FileOutputStream(file).use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    frame.recycle()
                }
            } ?: return ""
            tempFile = extractedFile
            val thumbUri = Uri.fromFile(extractedFile)
            val thumbPath = "$userId/thumb_${UUID.randomUUID()}.jpg"
            client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = thumbUri,
                bucket = "videos",
                objectPath = thumbPath,
                mimeType = "image/jpeg",
                onProgress = {}
            ).getOrDefault("")
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { retriever.release() }
            runCatching { tempFile?.delete() }
        }
    }

    suspend fun getUserVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val raw = try {
            client.restApi.getVideosByUser("eq.$userId")
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByUser("eq.$userId", select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val creator = raw.firstOrNull()?.creator
            ?: runCatching { client.restApi.getUserById("eq.$userId").firstOrNull() }.getOrNull()
        val session = preferences.getSessionSnapshot()
        val likedIds = if (session.isLoggedIn) {
            runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                .getOrDefault(emptySet())
        } else emptySet()
        val savedIds = if (session.isLoggedIn) {
            runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                .getOrDefault(emptySet())
        } else emptySet()
        raw.map { video ->
            video.copy(
                creator = video.creator ?: creator,
                isLiked = video.id in likedIds,
                isSaved = video.id in savedIds
            )
        }
    }

    suspend fun getUserLikedVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val likedIds = runCatching {
            client.restApi.getLikesForUser("eq.$userId").map { it.videoId }.distinct()
        }.getOrDefault(emptyList())
        if (likedIds.isEmpty()) return@withContext emptyList()
        val filter = "in.(${likedIds.joinToString(",")})"
        try {
            client.restApi.getVideosByIds(filter)
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByIds(filter, select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getUserSavedVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val savedIds = runCatching {
            client.restApi.getSavedForUser("eq.$userId").map { it.videoId }.distinct()
        }.getOrDefault(emptyList())
        if (savedIds.isEmpty()) return@withContext emptyList()
        val filter = "in.(${savedIds.joinToString(",")})"
        try {
            client.restApi.getVideosByIds(filter)
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByIds(filter, select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun isFollowingUser(targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured || session.userId == targetUserId) {
            return@withContext false
        }
        runCatching {
            client.restApi.getFollowingIds("eq.${session.userId}").any { it.followingId == targetUserId }
        }.getOrDefault(false)
    }

    suspend fun getFollowerProfiles(userId: String): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val ids = client.restApi.getFollowerIds("eq.$userId").map { it.followerId }.distinct()
            if (ids.isEmpty()) return@withContext Result.success(emptyList())
            val users = client.restApi.getUsersByIds("in.(${ids.joinToString(",")})")
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getFollowingProfiles(userId: String): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val ids = client.restApi.getFollowingIds("eq.$userId").map { it.followingId }.distinct()
            if (ids.isEmpty()) return@withContext Result.success(emptyList())
            val users = client.restApi.getUsersByIds("in.(${ids.joinToString(",")})")
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String,
        isPrivate: Boolean,
        newAvatarUri: Uri?,
        contentResolver: ContentResolver
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Not signed in"))

        val updates = mutableMapOf<String, Any>(
            "display_name" to displayName.trim(),
            "username" to username.trim().lowercase().replace(Regex("[^a-z0-9_.]"), ""),
            "bio" to bio.trim(),
            "is_private" to isPrivate
        )
        if (newAvatarUri != null) {
            val mimeType = contentResolver.getType(newAvatarUri) ?: "image/jpeg"
            val ext = when {
                mimeType.contains("png", ignoreCase = true) -> "png"
                mimeType.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val avatarPath = "${session.userId}/avatar_${System.currentTimeMillis()}.$ext"
            val uploadRes = client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = newAvatarUri,
                bucket = "avatars",
                objectPath = avatarPath,
                mimeType = mimeType,
                onProgress = {}
            )
            if (uploadRes.isFailure) {
                return@withContext Result.failure(
                    uploadRes.exceptionOrNull() ?: IllegalStateException("Profile photo upload failed")
                )
            }
            updates["avatar_url"] = uploadRes.getOrThrow()
        }
        try {
            val updated = client.restApi.updateUserProfile("eq.${session.userId}", updates)
            val profile = updated.firstOrNull()
                ?: client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun uploadProfilePhoto(
        photoUri: Uri,
        contentResolver: ContentResolver
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to upload a profile photo"))
        if (!SupabaseConfig.isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))

        val mimeType = contentResolver.getType(photoUri) ?: "image/jpeg"
        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val avatarPath = "${session.userId}/avatar_${System.currentTimeMillis()}.$ext"
        val uploadRes = client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = photoUri,
            bucket = "avatars",
            objectPath = avatarPath,
            mimeType = mimeType,
            onProgress = {}
        )
        if (uploadRes.isFailure) {
            return@withContext Result.failure(
                uploadRes.exceptionOrNull() ?: IllegalStateException("Profile photo upload failed")
            )
        }
        val publicUrl = uploadRes.getOrThrow()
        try {
            val updated = client.restApi.updateUserProfile(
                "eq.${session.userId}",
                mapOf("avatar_url" to publicUrl)
            )
            val profile = updated.firstOrNull()
                ?: client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getGifts(): List<GiftItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getGifts() }.getOrDefault(emptyList())
    }

    suspend fun sendGift(receiverId: String, videoId: String, gift: GiftItem): Result<Int> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send gifts"))
        try {
            val remaining = client.restApi.sendVirtualGift(
                mapOf(
                    "p_receiver_id" to receiverId,
                    "p_video_id" to videoId,
                    "p_coins" to gift.coinCost,
                    "p_gift_name" to gift.name
                )
            )
            Result.success(remaining)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun claimRewardCoins(amount: Int, source: String): Result<Int> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to claim coins"))
        try {
            val newBalance = client.restApi.claimRewardCoins(
                mapOf("p_coins" to amount, "p_source" to source)
            )
            Result.success(newBalance)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getTransactions(): List<TransactionItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching {
            client.restApi.getTransactions("(sender_id.eq.${session.userId},receiver_id.eq.${session.userId})")
        }.getOrDefault(emptyList())
    }

    // =========================================================================
    // INBOX: NOTIFICATIONS & DIRECT MESSAGING
    // =========================================================================
    suspend fun getNotifications(markAsRead: Boolean = true): Result<List<NotificationItem>> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val list = try {
                client.restApi.getNotifications("eq.${session.userId}")
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching {
                        client.restApi.getNotifications("eq.${session.userId}", select = "*")
                    }.getOrElse { throw e }
                } else throw e
            }
            if (markAsRead) {
                runCatching { client.restApi.markAllNotificationsRead("eq.${session.userId}") }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getUnreadNotificationsCount(): Int = withContext(Dispatchers.IO) {
        getNotifications(markAsRead = false).getOrNull()?.count { !it.isRead } ?: 0
    }

    suspend fun getConversations(): Result<List<ConversationItem>> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val raw = client.restApi.getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
            val hydrated = raw.map { conv ->
                val otherId = if (conv.participantOne == session.userId) conv.participantTwo else conv.participantOne
                val otherUser = runCatching { client.restApi.getUserById("eq.$otherId").firstOrNull() }.getOrNull()
                conv.copy(otherUser = otherUser)
            }
            Result.success(hydrated)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun startOrGetConversation(targetUserId: String): Result<ConversationItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to message creators"))
        try {
            val existing = client.restApi.getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
                .firstOrNull {
                    (it.participantOne == session.userId && it.participantTwo == targetUserId) ||
                        (it.participantTwo == session.userId && it.participantOne == targetUserId)
                }
            if (existing != null) {
                val other = runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull() }.getOrNull()
                return@withContext Result.success(existing.copy(otherUser = other))
            }
            val created = client.restApi.createConversation(
                CreateConversationRequest(
                    participantOne = session.userId,
                    participantTwo = targetUserId
                )
            ).first()
            val other = runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull() }.getOrNull()
            Result.success(created.copy(otherUser = other))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getMessages(conversationId: String): Result<List<MessageItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            Result.success(client.restApi.getMessages("eq.$conversationId"))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun sendMessage(conversationId: String, content: String): Result<MessageItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send messages"))
        try {
            val msg = client.restApi.sendMessage(
                CreateMessageRequest(
                    conversationId = conversationId,
                    senderId = session.userId,
                    content = content.trim()
                )
            ).first()
            runCatching {
                client.restApi.updateConversationLastMessage(
                    "eq.$conversationId",
                    mapOf("last_message" to content.trim())
                )
            }
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // SAFETY: REPORT & BLOCK
    // =========================================================================
    suspend fun reportVideo(videoId: String, reportedUserId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to submit a report"))
        try {
            client.restApi.createReport(
                CreateReportRequest(
                    reporterId = session.userId,
                    videoId = videoId,
                    reportedUserId = reportedUserId,
                    reason = reason
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun blockUser(targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to block users"))
        try {
            val resp = client.restApi.blockUser(BlockUserRequest(blockerId = session.userId, blockedId = targetUserId))
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // ADMIN DASHBOARD & MODERATION
    // =========================================================================
    suspend fun getAdminDashboardStats(): Result<AdminDashboardStats> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(AdminDashboardStats())
        try {
            val users = client.restApi.getUsers(limit = 100)
            val videos = client.restApi.getVideos(privacy = null, limit = 100)
            val reports = runCatching { client.restApi.getReports() }.getOrDefault(emptyList())
            val ads = runCatching { client.restApi.getActiveAds() }.getOrDefault(emptyList())
            val live = runCatching { client.restApi.getLiveStreams() }.getOrDefault(emptyList())

            Result.success(
                AdminDashboardStats(
                    totalUsers = users.size,
                    totalVideos = videos.size,
                    totalViews = videos.sumOf { it.viewsCount.toLong() },
                    totalLikes = videos.sumOf { it.likesCount.toLong() },
                    pendingReports = reports,
                    recentUsers = users.take(20),
                    recentVideos = videos.take(20),
                    activeAds = ads,
                    liveStreams = live
                )
            )
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun deleteVideo(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = client.restApi.deleteVideo("eq.$videoId")
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleUserVerified(userId: String, verify: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            client.restApi.updateUserProfile("eq.$userId", mapOf("is_verified" to verify))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun resolveReport(reportId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = client.restApi.updateReportStatus("eq.$reportId", mapOf("status" to status))
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getLiveStreams(): List<LiveStreamItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getLiveStreams() }.getOrDefault(emptyList())
    }
}
