package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.resolvePresenceLocation

/** Owns remote notification actions and their guarded local removal. */
internal class NotificationActionCoordinator(
    private val notificationApi: NotificationApi,
    private val authRepository: AuthRepository,
    private val account: AccountScope,
    private val state: NotificationInboxState,
    private val storage: NotificationStorageWorker,
) {
    suspend fun sendInviteToUser(userId: String, messageSlot: Int? = null) {
        notificationApi.sendInvite(userId, createInvitePayload(messageSlot))
    }

    suspend fun sendInviteResponse(notification: UnifiedNotification, responseSlot: Int) {
        require(notification.source == NotificationSource.V1) { "Saved invite responses require a V1 notification" }
        val token = account.notificationToken() ?: return
        notificationApi.sendInviteResponse(
            notificationId = notification.id,
            body = InviteResponseRequest(responseSlot = responseSlot),
        )
        if (!account.isCurrent(token)) return
        notificationApi.hideNotification(notification.id)
        state.remove(listOf(notification.id), token, storage)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        val token = account.notificationToken() ?: return
        when {
            notification.source == NotificationSource.V2 -> {
                val primaryResponse = notification.responses.firstOrNull() ?: return
                respondToNotification(notification, primaryResponse.type, token)
            }

            notification.kind == NotificationKind.FRIEND_REQUEST -> {
                notificationApi.acceptFriendRequest(notification.id)
                state.remove(listOf(notification.id), token, storage)
            }

            notification.kind == NotificationKind.REQUEST_INVITE -> {
                sendInviteToUser(notification.senderUserId)
                if (!account.isCurrent(token)) return
                notificationApi.hideNotification(notification.id)
                state.remove(listOf(notification.id), token, storage)
            }
        }
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        val token = account.notificationToken() ?: return
        respondToNotification(notification, responseType, token)
    }

    private suspend fun respondToNotification(
        notification: UnifiedNotification,
        responseType: String,
        token: AccountScope.Token,
    ) {
        if (notification.source != NotificationSource.V2) return
        val response = notification.responses.firstOrNull { it.type == responseType } ?: return
        if (!account.isCurrent(token)) return
        notificationApi.sendNotificationResponse(
            notification.id,
            NotificationResponse(
                responseType = response.type,
                responseData = response.data,
            ),
        )
        state.remove(listOf(notification.id), token, storage)
    }

    suspend fun hide(notification: UnifiedNotification) {
        val token = account.notificationToken() ?: return
        when (notification.source) {
            NotificationSource.V1 -> notificationApi.hideNotification(notification.id)
            NotificationSource.V2 -> notificationApi.hideNotificationV2(notification.id)
            NotificationSource.LOCAL -> Unit
        }
        state.remove(listOf(notification.id), token, storage)
    }

    private fun createInvitePayload(messageSlot: Int?): InviteRequest =
        InviteRequest(instanceId = resolveInviteLocation(), messageSlot = messageSlot)

    private fun resolveInviteLocation(): String {
        val currentUser = authRepository.currentUser ?: error("Current user is not available")
        val currentLocation = resolvePresenceLocation(currentUser)
        if (!isTrackableLocation(currentLocation)) error("You must be in a world to send invites")
        return currentLocation
    }
}
