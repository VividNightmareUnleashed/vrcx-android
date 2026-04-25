package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId = AtomicInteger(100)

    init {
        createNotificationChannels()
    }

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

    fun notifyFriendLocation(displayName: String, worldName: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = displayName,
            text = if (worldName.isNotEmpty()) "joined $worldName" else "changed location",
        )
    }

    fun notifyFriendStatusChange(displayName: String, newStatus: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = displayName,
            text = "changed status to $newStatus",
        )
    }

    fun notifyGeneral(title: String, text: String) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = title,
            text = text,
        )
    }

    fun notifyBootReconnectRequired() {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = "Open VRCX to reconnect",
            text = "Android 15 requires reopening the app after reboot before the background connection can resume.",
            notificationId = BOOT_RECONNECT_NOTIFICATION_ID,
        )
    }

    fun cancelBootReconnectRequired() {
        notificationManager.cancel(BOOT_RECONNECT_NOTIFICATION_ID)
    }

    private fun post(channel: String, title: String, text: String, notificationId: Int? = null) {
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

        notificationManager.notify(
            notificationId ?: this.notificationId.getAndUpdate { (it + 1 - 100) % 10000 + 100 },
            notification,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        listOf(
            NotificationChannel(WebSocketForegroundService.CHANNEL_SERVICE, "Background Service", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(WebSocketForegroundService.CHANNEL_FRIEND_ONLINE, "Friend Online", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(WebSocketForegroundService.CHANNEL_FRIEND_OFFLINE, "Friend Offline", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(WebSocketForegroundService.CHANNEL_INVITES, "Invites", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(WebSocketForegroundService.CHANNEL_FRIEND_REQUEST, "Friend Requests", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(WebSocketForegroundService.CHANNEL_GENERAL, "General", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { notificationManager.createNotificationChannel(it) }
    }

    companion object {
        const val BOOT_RECONNECT_NOTIFICATION_ID = 99
    }
}
