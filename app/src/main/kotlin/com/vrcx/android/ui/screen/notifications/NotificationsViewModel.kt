package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.InviteMessageType
import com.vrcx.android.data.repository.NotificationCategoryCount
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.repository.matchesCategory
import com.vrcx.android.data.repository.notificationTypeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val inviteMessageRepository: InviteMessageRepository,
) : ViewModel() {

    data class InviteResponseDialogState(
        val notification: UnifiedNotification,
        val title: String,
        val messageType: InviteMessageType,
        val templates: List<InviteMessageTemplate> = emptyList(),
        val isLoading: Boolean = false,
    )

    private val _selectedCategory = MutableStateFlow(NotificationCategoryFilter.ALL)
    val selectedCategory: StateFlow<NotificationCategoryFilter> = _selectedCategory.asStateFlow()

    private val _selectedTypes = MutableStateFlow<Set<String>>(emptySet())
    val selectedTypes: StateFlow<Set<String>> = _selectedTypes.asStateFlow()

    val categoryCounts: StateFlow<List<NotificationCategoryCount>> = notificationRepository.unifiedNotifications
        .combine(MutableStateFlow(Unit)) { notifs, _ ->
            NotificationCategoryFilter.entries.map { filter ->
                NotificationCategoryCount(
                    filter = filter,
                    count = if (filter == NotificationCategoryFilter.ALL) {
                        notifs.size
                    } else {
                        notifs.count { it.matchesCategory(filter) }
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visibleTypes: StateFlow<List<String>> = combine(
        notificationRepository.unifiedNotifications,
        _selectedCategory,
    ) { notifs, category ->
        notifs
            .filter { it.matchesCategory(category) }
            .map { it.type }
            .distinct()
            .sortedBy(::notificationTypeLabel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<UnifiedNotification>> = combine(
        notificationRepository.unifiedNotifications,
        _selectedCategory,
        _selectedTypes,
    ) { notifs, category, types ->
        val categoryFiltered = notifs.filter { it.matchesCategory(category) }
        if (types.isEmpty()) {
            categoryFiltered
        } else {
            categoryFiltered.filter { it.type in types }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _inviteResponseDialog = MutableStateFlow<InviteResponseDialogState?>(null)
    val inviteResponseDialog: StateFlow<InviteResponseDialogState?> = _inviteResponseDialog.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                notificationRepository.restoreNotifications()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to restore notifications"
            } finally {
                _isLoading.value = false
            }
            refresh()
        }
    }

    fun selectCategory(category: NotificationCategoryFilter) {
        _selectedCategory.value = category
        _selectedTypes.value = emptySet()
    }

    fun toggleTypeFilter(type: String) {
        val current = _selectedTypes.value
        _selectedTypes.value = if (type in current) current - type else current + type
    }

    fun performPrimaryAction(notification: UnifiedNotification) {
        viewModelScope.launch {
            runCatching {
                notificationRepository.performPrimaryAction(notification)
            }.onFailure { error ->
                _error.value = error.message ?: "Failed to handle notification"
            }
        }
    }

    fun respond(notification: UnifiedNotification, responseType: String) {
        viewModelScope.launch {
            runCatching {
                notificationRepository.respondToNotification(notification, responseType)
            }.onFailure { error ->
                _error.value = error.message ?: "Failed to respond to notification"
            }
        }
    }

    fun openInviteResponseDialog(notification: UnifiedNotification) {
        val messageType = inviteMessageTypeFor(notification) ?: return
        loadInviteResponseDialog(
            InviteResponseDialogState(
                notification = notification,
                title = inviteDialogTitleFor(notification),
                messageType = messageType,
                isLoading = true,
            )
        )
    }

    fun refreshInviteResponseDialog() {
        val state = _inviteResponseDialog.value ?: return
        loadInviteResponseDialog(state.copy(isLoading = true))
    }

    fun dismissInviteResponseDialog() {
        _inviteResponseDialog.value = null
    }

    fun sendInviteResponse(template: InviteMessageTemplate) {
        val state = _inviteResponseDialog.value ?: return
        viewModelScope.launch {
            runCatching {
                notificationRepository.sendInviteResponse(
                    notificationId = state.notification.id,
                    responseSlot = template.slot,
                )
            }.onSuccess {
                _inviteResponseDialog.value = null
            }.onFailure { error ->
                _error.value = error.message ?: "Failed to send invite response"
            }
        }
    }

    fun hide(notification: UnifiedNotification) {
        viewModelScope.launch {
            runCatching {
                notificationRepository.hideUnified(notification.id, notification.isV2)
            }.onFailure { error ->
                _error.value = error.message ?: "Failed to dismiss notification"
            }
        }
    }

    private fun loadInviteResponseDialog(state: InviteResponseDialogState) {
        _inviteResponseDialog.value = state
        viewModelScope.launch {
            runCatching {
                inviteMessageRepository.getMessages(state.messageType)
            }.onSuccess { templates ->
                _inviteResponseDialog.value = state.copy(
                    templates = templates,
                    isLoading = false,
                )
            }.onFailure { error ->
                _inviteResponseDialog.value = state.copy(
                    templates = emptyList(),
                    isLoading = false,
                )
                _error.value = error.message ?: "Failed to load saved invite responses"
            }
        }
    }

    private fun inviteMessageTypeFor(notification: UnifiedNotification): InviteMessageType? = when (notification.type) {
        "invite" -> InviteMessageType.RESPONSE
        "requestInvite" -> InviteMessageType.REQUEST_RESPONSE
        else -> null
    }

    private fun inviteDialogTitleFor(notification: UnifiedNotification): String = when (notification.type) {
        "invite" -> "Respond to Invite"
        "requestInvite" -> "Respond to Invite Request"
        else -> "Send Response"
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                notificationRepository.loadNotifications()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load notifications"
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
