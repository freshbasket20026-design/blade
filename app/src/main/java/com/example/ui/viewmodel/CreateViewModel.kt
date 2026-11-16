package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.moderation.ModerationResult
import com.example.core.moderation.VideoContentModerator
import com.example.data.model.SoundItem
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CreateStep { CAPTURE, EDIT_AND_POST }

data class CreateUiState(
    val step: CreateStep = CreateStep.CAPTURE,
    val selectedVideoUri: Uri? = null,
    val title: String = "",
    val caption: String = "",
    val location: String = "",
    val category: String = "All",
    val privacy: String = "Public",
    val selectedFilter: String = "Normal",
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 60_000L,
    val videoDurationMs: Long = 60_000L,
    val selectedSound: SoundItem? = null,
    val availableSounds: List<SoundItem> = emptyList(),
    val isCheckingSafety: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadError: String? = null,
    val uploadSuccess: Boolean = false
)

class CreateViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateUiState())
    val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

    val filters = listOf("Normal", "Cyber", "Noir", "Warm", "Blade Prism")
    val categories = listOf("All", "Comedy", "Music", "Dance", "Cricket", "Tech", "Food", "Travel", "Gaming")
    val privacies = listOf("Public", "Friends", "Private")

    init {
        loadSounds()
    }

    fun loadSounds() {
        viewModelScope.launch {
            val sounds = repository.getSounds()
            _uiState.update { it.copy(availableSounds = sounds) }
        }
    }

    fun onVideoSelected(uri: Uri, durationMs: Long = 30_000L) {
        val safeDur = durationMs.coerceAtLeast(1000L)
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                step = CreateStep.EDIT_AND_POST,
                videoDurationMs = safeDur,
                trimStartMs = 0L,
                trimEndMs = safeDur,
                uploadError = null,
                uploadSuccess = false
            )
        }
    }

    fun preselectSound(sound: SoundItem) {
        _uiState.update { it.copy(selectedSound = sound) }
    }

    fun backToCapture() {
        _uiState.update { it.copy(step = CreateStep.CAPTURE, selectedVideoUri = null, uploadError = null) }
    }

    fun updateTitle(v: String) = _uiState.update { it.copy(title = v) }
    fun updateCaption(v: String) = _uiState.update { it.copy(caption = v) }
    fun updateLocation(v: String) = _uiState.update { it.copy(location = v) }
    fun updateCategory(v: String) = _uiState.update { it.copy(category = v) }
    fun updatePrivacy(v: String) = _uiState.update { it.copy(privacy = v) }
    fun updateFilter(v: String) = _uiState.update { it.copy(selectedFilter = v) }
    fun selectSound(sound: SoundItem?) = _uiState.update { it.copy(selectedSound = sound) }
    fun updateTrimRange(startMs: Long, endMs: Long) {
        _uiState.update { it.copy(trimStartMs = startMs.coerceAtLeast(0L), trimEndMs = endMs.coerceAtLeast(startMs + 500L)) }
    }

    fun publish(contentResolver: ContentResolver, onCompleted: () -> Unit) {
        val state = _uiState.value
        val uri = state.selectedVideoUri ?: return
        if (state.isUploading || state.isCheckingSafety) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingSafety = true,
                    isUploading = false,
                    uploadProgress = 0f,
                    uploadError = null
                )
            }
            val moderation = VideoContentModerator.moderateVideo(
                contentResolver = contentResolver,
                videoUri = uri,
                title = state.title,
                caption = state.caption,
                location = state.location,
                durationMs = state.videoDurationMs
            )
            if (moderation is ModerationResult.Blocked) {
                _uiState.update {
                    it.copy(
                        isCheckingSafety = false,
                        isUploading = false,
                        uploadError = moderation.reason
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isCheckingSafety = false,
                    isUploading = true,
                    uploadProgress = 0.05f,
                    uploadError = null
                )
            }
            val result = repository.publishVideo(
                contentResolver = contentResolver,
                videoUri = uri,
                title = state.title,
                caption = state.caption,
                category = state.category,
                location = state.location,
                privacy = state.privacy,
                sound = state.selectedSound,
                filterName = state.selectedFilter,
                trimStartMs = state.trimStartMs,
                trimEndMs = state.trimEndMs,
                durationMs = state.videoDurationMs,
                onProgress = { pct -> _uiState.update { it.copy(uploadProgress = pct) } }
            )
            result.fold(
                onSuccess = {
                    _uiState.update {
                        CreateUiState(availableSounds = it.availableSounds, uploadSuccess = true)
                    }
                    onCompleted()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isUploading = false, uploadError = err.message ?: "Upload failed")
                    }
                }
            )
        }
    }

    fun resetSuccess() {
        _uiState.update { it.copy(uploadSuccess = false, uploadError = null) }
    }
}
