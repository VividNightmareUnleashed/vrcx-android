package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class UnifiedNotification(
    val id: String,
    val type: String,
    val senderUserId: String,
    val senderUsername: String,
    val message: String,
    val title: String,
    val createdAt: String,
    val seen: Boolean,
    val isV2: Boolean,
    val responses: List<NotificationAction>,
)

private data class InviteContext(
    val location: String,
    val worldName: String,
)

@Singleton
class NotificationRepository @Inject constructor(
    private val notificationApi: NotificationApi,
    private val authRepository: AuthRepository,
    private val worldApi: WorldApi,
    private val json: Json,
) {
    private val _notifications = MutableStateFlow<List<VrcNotification>>(emptyList())
    val notifications: StateFlow<List<VrcNotification>> = _notifications.asStateFlow()

    private val _notificationsV2 = MutableStateFlow<List<NotificationV2>>(emptyList())
    val notificationsV2: StateFlow<List<NotificationV2>> = _notificationsV2.asStateFlow()

    val unseenCount = MutableStateFlow(0)

    val unifiedNotifications = combine(_notifications, _notificationsV2) { v1, v2 ->
        val fromV1 = v1.map { n ->
            UnifiedNotification(
                id = n.id,
                type = n.type,
                senderUserId = n.senderUserId,
                senderUsername = n.senderUsername,
                message = n.message,
                title = "",
                createdAt = n.createdAt,
                seen = n.seen,
                isV2 = false,
                responses = emptyList(),
            )
        }
        val fromV2 = v2.map { n ->
            UnifiedNotification(
                id = n.id,
                type = n.type,
                senderUserId = n.senderUserId,
                senderUsername = n.senderUsername,
                message = n.message,
                title = n.title,
                createdAt = n.createdAt,
                seen = n.seen,
                isV2 = true,
                responses = n.responses,
            )
        }
        (fromV1 + fromV2).sortedByDescending { it.createdAt }
    }

    suspend fun loadNotifications() {
        val v1 = notificationApi.getNotifications()
        _notifications.value = v1
        val v2 = notificationApi.getNotificationsV2()
        _notificationsV2.value = v2
        unseenCount.value = v1.count { !it.seen } + v2.count { !it.seen }
    }

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Notification -> handleV1Notification(event)
            is PipelineEvent.NotificationV2 -> handleV2Notification(event)
            is PipelineEvent.NotificationV2Delete -> handleV2Delete(event)
            is PipelineEvent.NotificationV2Update -> handleV2Update(event)
            is PipelineEvent.SeeNotification -> handleSee(event)
            is PipelineEvent.HideNotification -> handleHide(event)
            is PipelineEvent.ResponseNotification -> handleResponse(event)
            else -> {}
        }
    }

    private fun handleV1Notification(event: PipelineEvent.Notification) {
        val notif = try {
            event.content?.let { json.decodeFromJsonElement(VrcNotification.serializer(), it) }
        } catch (_: Exception) { null } ?: return
        _notifications.value = _notifications.value.filter { it.id != notif.id } + notif
        recalculateUnseenCount()
    }

    private fun handleV2Notification(event: PipelineEvent.NotificationV2) {
        val notif = try {
            event.content?.let { json.decodeFromJsonElement(NotificationV2.serializer(), it) }
        } catch (_: Exception) { null } ?: return
        val existing = _notificationsV2.value.indexOfFirst { it.id == notif.id }
        _notificationsV2.value = if (existing >= 0) {
            _notificationsV2.value.toMutableList().also { it[existing] = notif }
        } else {
            _notificationsV2.value + notif
        }
        recalculateUnseenCount()
    }

    private fun handleV2Delete(event: PipelineEvent.NotificationV2Delete) {
        val ids = try {
            event.content?.jsonObject?.get("ids")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
        _notificationsV2.value = _notificationsV2.value.filter { it.id !in ids }
        _notifications.value = _notifications.value.filter { it.id !in ids }
        recalculateUnseenCount()
    }

    private fun handleV2Update(event: PipelineEvent.NotificationV2Update) {
        val obj = event.content?.jsonObject ?: return
        val id = obj["id"]?.jsonPrimitive?.content ?: return
        val updatesJson = obj["updates"]?.jsonObject ?: return
        _notificationsV2.value = _notificationsV2.value.map { existing ->
            if (existing.id == id) {
                // Merge only fields present in the update, preserving existing values
                existing.copy(
                    seen = updatesJson["seen"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: existing.seen,
                    message = updatesJson["message"]?.jsonPrimitive?.content ?: existing.message,
                    title = updatesJson["title"]?.jsonPrimitive?.content ?: existing.title,
                    type = updatesJson["type"]?.jsonPrimitive?.content ?: existing.type,
                )
            } else existing
        }
        recalculateUnseenCount()
    }

    private fun handleSee(event: PipelineEvent.SeeNotification) {
        val id = event.content?.jsonPrimitive?.content ?: return
        markSeen(id)
    }

    private fun handleHide(event: PipelineEvent.HideNotification) {
        val id = event.content?.jsonPrimitive?.content ?: return
        _notifications.value = _notifications.value.filter { it.id != id }
        _notificationsV2.value = _notificationsV2.value.filter { it.id != id }
        recalculateUnseenCount()
    }

    private fun handleResponse(event: PipelineEvent.ResponseNotification) {
        val id = event.content?.jsonObject?.get("notificationId")?.jsonPrimitive?.content ?: return
        _notifications.value = _notifications.value.filter { it.id != id }
        _notificationsV2.value = _notificationsV2.value.filter { it.id != id }
        recalculateUnseenCount()
    }

    private fun markSeen(id: String) {
        _notifications.value = _notifications.value.map {
            if (it.id == id) it.copy(seen = true) else it
        }
        _notificationsV2.value = _notificationsV2.value.map {
            if (it.id == id) it.copy(seen = true) else it
        }
        recalculateUnseenCount()
    }

    private fun recalculateUnseenCount() {
        unseenCount.value = _notifications.value.count { !it.seen } +
            _notificationsV2.value.count { !it.seen }
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
        markSeen(notificationId)
    }

    suspend fun sendInviteToUser(userId: String) {
        notificationApi.sendInvite(userId, createInvitePayload())
    }

    suspend fun acceptInvite(notificationId: String, isV2: Boolean) {
        val notification = if (isV2) {
            _notificationsV2.value.firstOrNull { it.id == notificationId }?.let { n ->
                UnifiedNotification(
                    id = n.id,
                    type = n.type,
                    senderUserId = n.senderUserId,
                    senderUsername = n.senderUsername,
                    message = n.message,
                    title = n.title,
                    createdAt = n.createdAt,
                    seen = n.seen,
                    isV2 = true,
                    responses = n.responses,
                )
            }
        } else {
            _notifications.value.firstOrNull { it.id == notificationId }?.let { n ->
                UnifiedNotification(
                    id = n.id,
                    type = n.type,
                    senderUserId = n.senderUserId,
                    senderUsername = n.senderUsername,
                    message = n.message,
                    title = "",
                    createdAt = n.createdAt,
                    seen = n.seen,
                    isV2 = false,
                    responses = emptyList(),
                )
            }
        } ?: return
        performPrimaryAction(notification)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        when {
            notification.isV2 -> {
                val primaryResponse = notification.responses.firstOrNull() ?: return
                respondToNotification(notification, primaryResponse.type)
            }
            notification.type == "friendRequest" -> {
                acceptFriendRequest(notification.id)
                removeFromLists(notification.id)
            }
            notification.type == "requestInvite" -> {
                acceptRequestInvite(notification)
                removeFromLists(notification.id)
            }
        }
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        if (!notification.isV2) return
        val response = notification.responses.firstOrNull { it.type == responseType } ?: return
        notificationApi.sendNotificationResponse(
            notification.id,
            NotificationResponse(
                responseType = response.type,
                responseData = response.data,
            ),
        )
        removeFromLists(notification.id)
    }

    suspend fun declineInvite(notificationId: String, isV2: Boolean) {
        if (isV2) {
            notificationApi.hideNotificationV2(notificationId)
        } else {
            notificationApi.hideNotification(notificationId)
        }
        removeFromLists(notificationId)
    }

    suspend fun hideUnified(notificationId: String, isV2: Boolean) {
        if (isV2) {
            notificationApi.hideNotificationV2(notificationId)
        } else {
            notificationApi.hideNotification(notificationId)
        }
        removeFromLists(notificationId)
    }

    private suspend fun acceptRequestInvite(notification: UnifiedNotification) {
        sendInviteToUser(notification.senderUserId)
        notificationApi.hideNotification(notification.id)
    }

    private suspend fun createInvitePayload(): Map<String, @JvmSuppressWildcards Any> {
        val context = resolveInviteContext()
        return mapOf(
            "instanceId" to context.location,
            "worldId" to context.location,
            "worldName" to context.worldName,
            "rsvp" to true,
        )
    }

    private suspend fun resolveInviteContext(): InviteContext {
        val currentUser = authRepository.currentUser
            ?: error("Current user is not available")
        val currentLocation = when (currentUser.location) {
            "traveling" -> currentUser.travelingToLocation
            else -> currentUser.location
        } ?: error("You must be in a world to send invites")

        if (
            currentLocation.isBlank() ||
            currentLocation == "offline" ||
            currentLocation == "private" ||
            currentLocation == "traveling"
        ) {
            error("You must be in a world to send invites")
        }

        val worldId = currentLocation.substringBefore(":")
        val worldName = runCatching { worldApi.getWorld(worldId).name }.getOrDefault(worldId)
        return InviteContext(
            location = currentLocation,
            worldName = worldName,
        )
    }

    private fun removeFromLists(notificationId: String) {
        _notifications.value = _notifications.value.filter { it.id != notificationId }
        _notificationsV2.value = _notificationsV2.value.filter { it.id != notificationId }
        recalculateUnseenCount()
    }
}
