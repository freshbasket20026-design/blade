package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdminDashboardStats
import com.example.data.model.TransactionItem
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.VideoItem
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FollowListType { FOLLOWERS, FOLLOWING }

data class ProfileUiState(
    val profileState: UiState<UserProfile> = UiState.Loading,
    val uploadedVideos: List<VideoItem> = emptyList(),
    val likedVideos: List<VideoItem> = emptyList(),
    val savedVideos: List<VideoItem> = emptyList(),
    val transactions: List<TransactionItem> = emptyList(),
    val isFollowing: Boolean = false,
    val followListType: FollowListType? = null,
    val followListState: UiState<List<UserProfile>> = UiState.Loading,
    val isSavingProfile: Boolean = false,
    val isUploadingPhoto: Boolean = false,
    val adminState: UiState<AdminDashboardStats> = UiState.Loading,
    val feedbackMessage: String? = null
)

class ProfileViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile(userId: String) {
        viewModelScope.launch {
            val loadedId = (_uiState.value.profileState as? UiState.Success)?.data?.id
            if (loadedId != userId) {
                _uiState.update { it.copy(profileState = UiState.Loading) }
            }
            repository.getUserProfile(userId).fold(
                onSuccess = { profile ->
                    val uploaded = repository.getUserVideos(userId)
                    val liked = repository.getUserLikedVideos(userId)
                    val saved = repository.getUserSavedVideos(userId)
                    val tx = repository.getTransactions()
                    val following = repository.isFollowingUser(userId)
                    _uiState.update {
                        it.copy(
                            profileState = UiState.Success(profile),
                            uploadedVideos = uploaded,
                            likedVideos = liked,
                            savedVideos = saved,
                            transactions = tx,
                            isFollowing = following
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(profileState = UiState.Error(err.message ?: "Unable to load profile"))
                    }
                }
            )
        }
    }

    fun openFollowList(userId: String, type: FollowListType) {
        _uiState.update {
            it.copy(followListType = type, followListState = UiState.Loading)
        }
        viewModelScope.launch {
            val result = when (type) {
                FollowListType.FOLLOWERS -> repository.getFollowerProfiles(userId)
                FollowListType.FOLLOWING -> repository.getFollowingProfiles(userId)
            }
            result.fold(
                onSuccess = { users ->
                    _uiState.update { it.copy(followListState = UiState.Success(users)) }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(followListState = UiState.Error(err.message ?: "Unable to load users"))
                    }
                }
            )
        }
    }

    fun closeFollowList() {
        _uiState.update { it.copy(followListType = null) }
    }

    fun toggleFollow(targetUserId: String) {
        viewModelScope.launch {
            repository.toggleFollow(targetUserId).fold(
                onSuccess = { nowFollowing ->
                    _uiState.update { state ->
                        val updatedProfile = (state.profileState as? UiState.Success)?.data?.let { p ->
                            val delta = if (nowFollowing) 1 else -1
                            p.copy(followersCount = (p.followersCount + delta).coerceAtLeast(0))
                        }
                        state.copy(
                            isFollowing = nowFollowing,
                            profileState = updatedProfile?.let { UiState.Success(it) } ?: state.profileState
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(feedbackMessage = err.message) }
                }
            )
        }
    }

    fun saveProfileEdits(
        displayName: String,
        username: String,
        bio: String,
        isPrivate: Boolean,
        newAvatarUri: Uri?,
        contentResolver: ContentResolver,
        onSaved: (UserProfile) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingProfile = true) }
            repository.updateProfile(displayName, username, bio, isPrivate, newAvatarUri, contentResolver).fold(
                onSuccess = { updated ->
                    _uiState.update {
                        it.copy(
                            isSavingProfile = false,
                            profileState = UiState.Success(updated),
                            feedbackMessage = "Profile updated"
                        )
                    }
                    onSaved(updated)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isSavingProfile = false, feedbackMessage = err.message ?: "Update failed")
                    }
                }
            )
        }
    }

    fun uploadProfilePhoto(
        photoUri: Uri,
        contentResolver: ContentResolver,
        onUploaded: (UserProfile) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingPhoto = true) }
            repository.uploadProfilePhoto(photoUri, contentResolver).fold(
                onSuccess = { updated ->
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            profileState = UiState.Success(updated),
                            feedbackMessage = "Profile photo updated!"
                        )
                    }
                    onUploaded(updated)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            feedbackMessage = err.message ?: "Photo upload failed"
                        )
                    }
                }
            )
        }
    }

    fun claimRewardedAdCoins(onUserUpdated: (Int) -> Unit) {
        viewModelScope.launch {
            repository.claimRewardCoins(25, "Rewarded Video Ad").fold(
                onSuccess = { newBalance ->
                    onUserUpdated(newBalance)
                    val tx = repository.getTransactions()
                    _uiState.update { state ->
                        val p = (state.profileState as? UiState.Success)?.data?.copy(coinsBalance = newBalance)
                        state.copy(
                            profileState = p?.let { UiState.Success(it) } ?: state.profileState,
                            transactions = tx,
                            feedbackMessage = "+25 Blade Coins credited!"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(feedbackMessage = err.message ?: "Unable to claim reward") }
                }
            )
        }
    }

    fun loadAdminDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(adminState = UiState.Loading) }
            repository.getAdminDashboardStats().fold(
                onSuccess = { stats -> _uiState.update { it.copy(adminState = UiState.Success(stats)) } },
                onFailure = { err -> _uiState.update { it.copy(adminState = UiState.Error(err.message ?: "Failed to load admin stats")) } }
            )
        }
    }

    fun adminDeleteVideo(videoId: String) {
        viewModelScope.launch {
            repository.deleteVideo(videoId).fold(
                onSuccess = {
                    _uiState.update { it.copy(feedbackMessage = "Video removed") }
                    loadAdminDashboard()
                },
                onFailure = { err -> _uiState.update { it.copy(feedbackMessage = err.message) } }
            )
        }
    }

    fun adminToggleVerified(userId: String, verify: Boolean) {
        viewModelScope.launch {
            repository.toggleUserVerified(userId, verify).fold(
                onSuccess = {
                    _uiState.update { it.copy(feedbackMessage = if (verify) "Creator verified" else "Verification removed") }
                    loadAdminDashboard()
                },
                onFailure = { err -> _uiState.update { it.copy(feedbackMessage = err.message) } }
            )
        }
    }

    fun adminResolveReport(reportId: String, status: String) {
        viewModelScope.launch {
            repository.resolveReport(reportId, status).fold(
                onSuccess = {
                    _uiState.update { it.copy(feedbackMessage = "Report marked $status") }
                    loadAdminDashboard()
                },
                onFailure = { err -> _uiState.update { it.copy(feedbackMessage = err.message) } }
            )
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }
}
