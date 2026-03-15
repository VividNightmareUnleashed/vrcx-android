package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
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
) : ViewModel() {

    private val _selectedTypes = MutableStateFlow<Set<String>>(emptySet())
    val selectedTypes: StateFlow<Set<String>> = _selectedTypes.asStateFlow()

    val allTypes: StateFlow<List<String>> = notificationRepository.unifiedNotifications
        .combine(MutableStateFlow(Unit)) { notifs, _ ->
            notifs.map { it.type }.distinct().sorted()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<UnifiedNotification>> = combine(
        notificationRepository.unifiedNotifications,
        _selectedTypes,
    ) { notifs, types ->
        if (types.isEmpty()) notifs else notifs.filter { it.type in types }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                notificationRepository.loadNotifications()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load notifications"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleTypeFilter(type: String) {
        val current = _selectedTypes.value
        _selectedTypes.value = if (type in current) current - type else current + type
    }

    fun accept(notificationId: String, isV2: Boolean) {
        viewModelScope.launch { notificationRepository.acceptInvite(notificationId, isV2) }
    }

    fun hide(notificationId: String, isV2: Boolean) {
        viewModelScope.launch { notificationRepository.hideUnified(notificationId, isV2) }
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
