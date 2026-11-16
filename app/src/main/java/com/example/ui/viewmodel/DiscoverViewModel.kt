package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.HashtagItem
import com.example.data.model.SoundItem
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.VideoItem
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val query: String = "",
    val selectedCategory: String = "All",
    val activeHashtag: String? = null,
    val activeSoundId: String? = null,
    val videosState: UiState<List<VideoItem>> = UiState.Loading,
    val creators: List<UserProfile> = emptyList(),
    val hashtags: List<HashtagItem> = emptyList(),
    val sounds: List<SoundItem> = emptyList(),
    val categories: List<String> = listOf("All", "Comedy", "Music", "Dance", "Cricket", "Tech", "Food", "Travel", "Gaming")
)

class DiscoverViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun onQueryChanged(q: String) {
        _uiState.update { it.copy(query = q, activeHashtag = null, activeSoundId = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(280)
            refresh()
        }
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category, activeHashtag = null, activeSoundId = null) }
        refresh()
    }

    fun filterByHashtag(tag: String) {
        val clean = tag.removePrefix("#")
        _uiState.update { it.copy(activeHashtag = clean, query = "#$clean", activeSoundId = null) }
        refresh()
    }

    fun filterBySound(soundId: String) {
        _uiState.update { it.copy(activeSoundId = soundId, activeHashtag = null) }
        refresh()
    }

    fun clearFilters() {
        _uiState.update { it.copy(query = "", selectedCategory = "All", activeHashtag = null, activeSoundId = null) }
        refresh()
    }

    fun refresh() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(videosState = UiState.Loading) }
            val result = repository.searchDiscover(
                query = state.query,
                category = state.selectedCategory,
                hashtag = state.activeHashtag,
                soundId = state.activeSoundId
            )
            val sounds = repository.getSounds()
            result.fold(
                onSuccess = { (videos, users, hashtags) ->
                    _uiState.update {
                        it.copy(
                            videosState = UiState.Success(videos),
                            creators = users,
                            hashtags = hashtags,
                            sounds = sounds
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(videosState = UiState.Error(err.message ?: "Failed to search"), sounds = sounds)
                    }
                }
            )
        }
    }
}
