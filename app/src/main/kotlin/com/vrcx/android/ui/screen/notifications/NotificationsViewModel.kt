package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    val notifications: StateFlow<List<VrcNotification>> = notificationRepository.notifications

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

    fun accept(notificationId: String) {
        viewModelScope.launch { notificationRepository.acceptFriendRequest(notificationId) }
    }

    fun hide(notificationId: String) {
        viewModelScope.launch { notificationRepository.hideNotification(notificationId) }
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
