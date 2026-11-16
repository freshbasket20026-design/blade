package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdItem
import com.example.data.model.CommentItem
import com.example.data.model.GiftItem
import com.example.data.model.UiState
import com.example.data.model.VideoItem
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FeedTab { FOR_YOU, FOLLOWING }

data class FeedUiState(
    val selectedTab: FeedTab = FeedTab.FOR_YOU,
    val videosState: UiState<List<VideoItem>> = UiState.Loading,
    val feedVersion: Int = 0,
    val activeAds: List<AdItem> = emptyList(),
    val commentsVideoId: String? = null,
    val commentsState: UiState<List<CommentItem>> = UiState.Loading,
    val giftTargetVideo: VideoItem? = null,
    val gifts: List<GiftItem> = emptyList(),
    val feedbackMessage: String? = null,
    val requireAuthPrompt: Boolean = false
)

class FeedViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadFeed(FeedTab.FOR_YOU)
    }

    fun selectTab(tab: FeedTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        loadFeed(tab)
    }

    fun loadFeed(tab: FeedTab = _uiState.value.selectedTab) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedTab = tab, videosState = UiState.Loading) }
            val result = repository.fetchFeed(forYou = tab == FeedTab.FOR_YOU)
            val ads = repository.fetchFeedAds()
            result.fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            videosState = UiState.Success(list),
                            feedVersion = it.feedVersion + 1,
                            activeAds = ads
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(videosState = UiState.Error(err.message ?: "Failed to load feed"))
                    }
                }
            )
        }
    }

    fun onVideoViewed(video: VideoItem) {
        viewModelScope.launch { repository.recordVideoView(video) }
    }

    fun onVideoShared(video: VideoItem) {
        viewModelScope.launch {
            repository.recordWatchSignal(video, weight = 5)
            repository.recordVideoShare(video.id)
            updateVideoInList(video.id) { it.copy(sharesCount = it.sharesCount + 1) }
        }
    }

    fun toggleLike(video: VideoItem, forceLike: Boolean = false) {
        if (forceLike && video.isLiked) return
        viewModelScope.launch {
            val optimisticLiked = !video.isLiked
            val delta = if (optimisticLiked) 1 else -1
            if (optimisticLiked) {
                repository.recordWatchSignal(video, weight = 4)
            }
            updateVideoInList(video.id) {
                it.copy(isLiked = optimisticLiked, likesCount = (it.likesCount + delta).coerceAtLeast(0))
            }
            val res = repository.toggleLike(video.id)
            if (res.isFailure) {
                // Revert
                updateVideoInList(video.id) {
                    it.copy(isLiked = video.isLiked, likesCount = video.likesCount)
                }
                val msg = res.exceptionOrNull()?.message ?: "Sign in required"
                _uiState.update {
                    it.copy(
                        feedbackMessage = msg,
                        requireAuthPrompt = msg.contains("Sign in", ignoreCase = true)
                    )
                }
            }
        }
    }

    fun toggleSave(video: VideoItem) {
        viewModelScope.launch {
            val optimisticSaved = !video.isSaved
            val delta = if (optimisticSaved) 1 else -1
            if (optimisticSaved) {
                repository.recordWatchSignal(video, weight = 5)
            }
            updateVideoInList(video.id) {
                it.copy(isSaved = optimisticSaved, savesCount = (it.savesCount + delta).coerceAtLeast(0))
            }
            val res = repository.toggleSave(video.id, video.isSaved)
            if (res.isFailure) {
                updateVideoInList(video.id) { it.copy(isSaved = video.isSaved, savesCount = video.savesCount) }
                val msg = res.exceptionOrNull()?.message ?: "Sign in required"
                _uiState.update {
                    it.copy(feedbackMessage = msg, requireAuthPrompt = msg.contains("Sign in", ignoreCase = true))
                }
            }
        }
    }

    fun toggleFollowCreator(creatorId: String) {
        viewModelScope.launch {
            val res = repository.toggleFollow(creatorId)
            res.fold(
                onSuccess = { nowFollowing ->
                    val current = (_uiState.value.videosState as? UiState.Success)?.data ?: return@fold
                    _uiState.update {
                        it.copy(
                            videosState = UiState.Success(
                                current.map { v ->
                                    if (v.userId == creatorId) v.copy(isFollowingCreator = nowFollowing) else v
                                }
                            )
                        )
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: "Sign in required"
                    _uiState.update {
                        it.copy(feedbackMessage = msg, requireAuthPrompt = msg.contains("Sign in", ignoreCase = true))
                    }
                }
            )
        }
    }

    fun openComments(videoId: String) {
        _uiState.update { it.copy(commentsVideoId = videoId, commentsState = UiState.Loading) }
        viewModelScope.launch {
            repository.getComments(videoId).fold(
                onSuccess = { list -> _uiState.update { it.copy(commentsState = UiState.Success(list)) } },
                onFailure = { err ->
                    _uiState.update { it.copy(commentsState = UiState.Error(err.message ?: "Failed to load comments")) }
                }
            )
        }
    }

    fun closeComments() {
        _uiState.update { it.copy(commentsVideoId = null) }
    }

    fun submitComment(videoId: String, text: String, parentId: String? = null) {
        if (text.isBlank()) return
        val currentVideo = (_uiState.value.videosState as? UiState.Success)?.data?.firstOrNull { it.id == videoId }
        val currentCount = currentVideo?.commentsCount ?: 0
        viewModelScope.launch {
            repository.postComment(videoId, text, parentId, currentCount).fold(
                onSuccess = {
                    currentVideo?.let { repository.recordWatchSignal(it, weight = 4) }
                    updateVideoInList(videoId) { v -> v.copy(commentsCount = v.commentsCount + 1) }
                    openComments(videoId)
                },
                onFailure = { err ->
                    val msg = err.message ?: "Sign in to comment"
                    _uiState.update {
                        it.copy(feedbackMessage = msg, requireAuthPrompt = msg.contains("Sign in", ignoreCase = true))
                    }
                }
            )
        }
    }

    fun openGiftSheet(video: VideoItem) {
        _uiState.update { it.copy(giftTargetVideo = video) }
        viewModelScope.launch {
            val catalog = repository.getGifts()
            _uiState.update { it.copy(gifts = catalog) }
        }
    }

    fun closeGiftSheet() {
        _uiState.update { it.copy(giftTargetVideo = null) }
    }

    fun sendGift(video: VideoItem, gift: GiftItem, onCoinsUpdated: (Int) -> Unit) {
        viewModelScope.launch {
            repository.sendGift(video.userId, video.id, gift).fold(
                onSuccess = { remaining ->
                    onCoinsUpdated(remaining)
                    _uiState.update {
                        it.copy(
                            giftTargetVideo = null,
                            feedbackMessage = "Sent ${gift.name}!"
                        )
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: "Unable to send gift"
                    _uiState.update {
                        it.copy(feedbackMessage = msg, requireAuthPrompt = msg.contains("Sign in", ignoreCase = true))
                    }
                }
            )
        }
    }

    fun reportVideo(video: VideoItem, reason: String) {
        viewModelScope.launch {
            repository.reportVideo(video.id, video.userId, reason).fold(
                onSuccess = { _uiState.update { it.copy(feedbackMessage = "Report submitted. Thank you.") } },
                onFailure = { err -> _uiState.update { it.copy(feedbackMessage = err.message ?: "Sign in to report") } }
            )
        }
    }

    fun blockCreator(userId: String) {
        viewModelScope.launch {
            repository.blockUser(userId).fold(
                onSuccess = {
                    val current = (_uiState.value.videosState as? UiState.Success)?.data ?: emptyList()
                    _uiState.update {
                        it.copy(
                            videosState = UiState.Success(current.filterNot { v -> v.userId == userId }),
                            feedbackMessage = "Creator blocked"
                        )
                    }
                },
                onFailure = { err -> _uiState.update { it.copy(feedbackMessage = err.message ?: "Sign in to block") } }
            )
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null, requireAuthPrompt = false) }
    }

    private fun updateVideoInList(videoId: String, transform: (VideoItem) -> VideoItem) {
        val current = (_uiState.value.videosState as? UiState.Success)?.data ?: return
        _uiState.update {
            it.copy(videosState = UiState.Success(current.map { v -> if (v.id == videoId) transform(v) else v }))
        }
    }
}
