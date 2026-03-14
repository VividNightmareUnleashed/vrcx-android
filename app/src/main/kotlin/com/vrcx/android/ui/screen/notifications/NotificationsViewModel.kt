package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    val notifications: StateFlow<List<VrcNotification>> = notificationRepository.notifications

    init {
        viewModelScope.launch { notificationRepository.loadNotifications() }
    }

    fun accept(notificationId: String) {
        viewModelScope.launch { notificationRepository.acceptFriendRequest(notificationId) }
    }

    fun hide(notificationId: String) {
        viewModelScope.launch { notificationRepository.hideNotification(notificationId) }
    }

    fun refresh() {
        viewModelScope.launch { notificationRepository.loadNotifications() }
    }
}
