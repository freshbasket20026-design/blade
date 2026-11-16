package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ConversationItem
import com.example.data.model.MessageItem
import com.example.data.model.NotificationItem
import com.example.data.model.UiState
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InboxUiState(
    val notificationsState: UiState<List<NotificationItem>> = UiState.Loading,
    val conversationsState: UiState<List<ConversationItem>> = UiState.Loading,
    val selectedFilter: String = "All",
    val activeConversation: ConversationItem? = null,
    val messagesState: UiState<List<MessageItem>> = UiState.Loading,
    val unreadCount: Int = 0,
    val error: String? = null
)

class InboxViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    private var chatPollingJob: Job? = null

    fun refreshUnreadCount() {
        viewModelScope.launch {
            val count = repository.getUnreadNotificationsCount()
            _uiState.update { it.copy(unreadCount = count) }
        }
    }

    fun clearUnreadCount() {
        _uiState.update { it.copy(unreadCount = 0) }
    }

    fun loadInbox() {
        viewModelScope.launch {
            _uiState.update { it.copy(notificationsState = UiState.Loading, conversationsState = UiState.Loading) }
            repository.getNotifications(markAsRead = true).fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            notificationsState = UiState.Success(list),
                            unreadCount = 0
                        )
                    }
                },
                onFailure = { err -> _uiState.update { it.copy(notificationsState = UiState.Error(err.message ?: "Error")) } }
            )
            repository.getConversations().fold(
                onSuccess = { list -> _uiState.update { it.copy(conversationsState = UiState.Success(list)) } },
                onFailure = { err -> _uiState.update { it.copy(conversationsState = UiState.Error(err.message ?: "Error")) } }
            )
        }
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun openConversationWithUser(targetUserId: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            repository.startOrGetConversation(targetUserId).fold(
                onSuccess = { conv ->
                    _uiState.update { it.copy(activeConversation = conv) }
                    onOpened(conv.id)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(error = err.message) }
                }
            )
        }
    }

    fun startChatPolling(conversationId: String) {
        chatPollingJob?.cancel()
        chatPollingJob = viewModelScope.launch {
            while (isActive) {
                repository.getMessages(conversationId).fold(
                    onSuccess = { msgs -> _uiState.update { it.copy(messagesState = UiState.Success(msgs)) } },
                    onFailure = { err ->
                        if (_uiState.value.messagesState is UiState.Loading) {
                            _uiState.update { it.copy(messagesState = UiState.Error(err.message ?: "Error")) }
                        }
                    }
                )
                delay(3500)
            }
        }
    }

    fun stopChatPolling() {
        chatPollingJob?.cancel()
        chatPollingJob = null
    }

    fun sendMessage(conversationId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(conversationId, text).fold(
                onSuccess = { newMsg ->
                    val existing = (_uiState.value.messagesState as? UiState.Success)?.data ?: emptyList()
                    _uiState.update { it.copy(messagesState = UiState.Success(existing + newMsg)) }
                },
                onFailure = { err -> _uiState.update { it.copy(error = err.message) } }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopChatPolling()
    }
}
