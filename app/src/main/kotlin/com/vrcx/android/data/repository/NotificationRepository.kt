package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.api.model.NotificationV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val notificationApi: NotificationApi,
) {
    private val _notifications = MutableStateFlow<List<VrcNotification>>(emptyList())
    val notifications: StateFlow<List<VrcNotification>> = _notifications.asStateFlow()

    private val _notificationsV2 = MutableStateFlow<List<NotificationV2>>(emptyList())
    val notificationsV2: StateFlow<List<NotificationV2>> = _notificationsV2.asStateFlow()

    val unseenCount = MutableStateFlow(0)

    suspend fun loadNotifications() {
        try {
            val v1 = notificationApi.getNotifications()
            _notifications.value = v1
            val v2 = notificationApi.getNotificationsV2()
            _notificationsV2.value = v2
            unseenCount.value = v1.count { !it.seen } + v2.count { !it.seen }
        } catch (_: Exception) {}
    }

    suspend fun acceptFriendRequest(notificationId: String) {
        notificationApi.acceptFriendRequest(notificationId)
        _notifications.value = _notifications.value.filter { it.id != notificationId }
    }

    suspend fun hideNotification(notificationId: String) {
        notificationApi.hideNotification(notificationId)
        _notifications.value = _notifications.value.filter { it.id != notificationId }
    }

    suspend fun seeNotification(notificationId: String) {
        notificationApi.seeNotification(notificationId)
        _notifications.value = _notifications.value.map {
            if (it.id == notificationId) it.copy(seen = true) else it
        }
    }
}
