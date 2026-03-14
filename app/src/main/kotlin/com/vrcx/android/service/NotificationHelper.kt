package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId = AtomicInteger(100)

    fun notifyFriendOnline(displayName: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_ONLINE,
            title = displayName,
            text = "is online",
        )
    }

    fun notifyFriendOffline(displayName: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_OFFLINE,
            title = displayName,
            text = "went offline",
        )
    }

    fun notifyInvite(senderName: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_INVITES,
            title = senderName,
            text = "sent you an invite",
        )
    }

    fun notifyFriendRequest(senderName: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_REQUEST,
            title = senderName,
            text = "sent you a friend request",
        )
    }

    private fun post(channel: String, title: String, text: String) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId.getAndUpdate { (it + 1 - 100) % 10000 + 100 }, notification)
    }
}
