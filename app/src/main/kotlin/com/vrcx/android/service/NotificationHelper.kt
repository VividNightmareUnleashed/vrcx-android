package com.vrcx.android.service

import android.content.Context
import com.vrcx.android.ui.navigation.DeepLinkSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The screen a notification should open, or null to just bring the app up. */
data class NotificationTarget(val section: DeepLinkSection, val id: String)

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext context: Context) :
    NotificationLifecycleNotifier(context) {
    fun notifyFriendOnline(displayName: String, userId: String? = null) {
        post(
            channel = CHANNEL_FRIEND_ONLINE,
            title = displayName,
            text = "is online",
            target = userTarget(userId),
        )
    }

    fun notifyFriendOffline(displayName: String, userId: String? = null) {
        post(
            channel = CHANNEL_FRIEND_OFFLINE,
            title = displayName,
            text = "went offline",
            target = userTarget(userId),
        )
    }

    fun notifyInvite(senderName: String, senderUserId: String? = null) {
        post(
            channel = CHANNEL_INVITES,
            title = senderName,
            text = "sent you an invite",
            target = userTarget(senderUserId),
        )
    }

    fun notifyFriendRequest(senderName: String, senderUserId: String? = null) {
        post(
            channel = CHANNEL_FRIEND_REQUEST,
            title = senderName,
            text = "sent you a friend request",
            target = userTarget(senderUserId),
        )
    }

    fun notifyFriendLocation(displayName: String, worldName: String, userId: String? = null) {
        post(
            channel = CHANNEL_GENERAL,
            title = displayName,
            text = if (worldName.isNotEmpty()) "joined $worldName" else "changed location",
            target = userTarget(userId),
        )
    }

    fun notifyFriendStatusChange(displayName: String, newStatus: String, userId: String? = null) {
        post(
            channel = CHANNEL_GENERAL,
            title = displayName,
            text = "changed status to $newStatus",
            target = userTarget(userId),
        )
    }

    fun notifyGeneral(title: String, text: String, target: NotificationTarget? = null) {
        post(
            channel = CHANNEL_GENERAL,
            title = title,
            text = text,
            target = target,
        )
    }

    private fun userTarget(userId: String?): NotificationTarget? = userId
        ?.takeIf(String::isNotEmpty)
        ?.let { NotificationTarget(DeepLinkSection.USER, it) }

    companion object {
        const val CHANNEL_SERVICE = "vrcx_service"
        const val CHANNEL_FRIEND_ONLINE = "vrcx_friend_online"
        const val CHANNEL_FRIEND_OFFLINE = "vrcx_friend_offline"
        const val CHANNEL_INVITES = "vrcx_invites"
        const val CHANNEL_FRIEND_REQUEST = "vrcx_friend_request"
        const val CHANNEL_GENERAL = "vrcx_general"

        const val SERVICE_NOTIFICATION_ID = 1
        const val BOOT_WORKER_NOTIFICATION_ID = 1001
    }
}
