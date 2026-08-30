package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import java.util.concurrent.atomic.AtomicInteger

/** Owns Android channel setup, rendering, and service-lifecycle notifications. */
open class NotificationLifecycleNotifier protected constructor(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId = AtomicInteger(ROLLING_ID_BASE)

    init {
        createNotificationChannels()
    }

    fun notifyBootReconnectRequired() {
        post(
            channel = NotificationHelper.CHANNEL_GENERAL,
            title = "Open VRCX to reconnect",
            text = "Android 15 requires reopening the app after reboot before the background connection can resume.",
            notificationId = BOOT_RECONNECT_NOTIFICATION_ID,
        )
    }

    fun cancelBootReconnectRequired() {
        notificationManager.cancel(BOOT_RECONNECT_NOTIFICATION_ID)
    }

    fun notifyServiceReconnectRequired() {
        post(
            channel = NotificationHelper.CHANNEL_GENERAL,
            title = "Open VRCX to reconnect",
            text = "Android paused the background connection after its service time limit.",
            notificationId = SERVICE_RECONNECT_NOTIFICATION_ID,
        )
    }

    fun cancelServiceReconnectRequired() {
        notificationManager.cancel(SERVICE_RECONNECT_NOTIFICATION_ID)
    }

    fun createWebSocketServiceNotification(): Notification = createOngoingNotification("Connected to VRChat")

    fun createBootWorkerNotification(): Notification = createOngoingNotification("Restoring background connection")

    protected fun post(
        channel: String,
        title: String,
        text: String,
        notificationId: Int? = null,
        target: NotificationTarget? = null,
    ) {
        val notification = Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent(target))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(
            notificationId ?: this.notificationId.getAndUpdate {
                (it + 1 - ROLLING_ID_BASE) % ROLLING_ID_COUNT + ROLLING_ID_BASE
            },
            notification,
        )
    }

    private fun createOngoingNotification(text: String): Notification = Notification.Builder(
        context,
        NotificationHelper.CHANNEL_SERVICE,
    )
        .setContentTitle("VRCX")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(contentIntent(target = null))
        .setOngoing(true)
        .build()

    private fun contentIntent(target: NotificationTarget?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (target != null) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(target.section.appUri(target.id))
            }
            // MainActivity is singleTop, so a running app consumes this intent
            // instead of stacking a second shell behind the first.
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // PendingIntents are matched without extras; a per-target request code
        // keeps destinations from replacing each other in the system cache.
        return PendingIntent.getActivity(
            context,
            target?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        listOf(
            NotificationChannel(
                NotificationHelper.CHANNEL_SERVICE,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW,
            ),
            NotificationChannel(
                NotificationHelper.CHANNEL_FRIEND_ONLINE,
                "Friend Online",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            NotificationChannel(
                NotificationHelper.CHANNEL_FRIEND_OFFLINE,
                "Friend Offline",
                NotificationManager.IMPORTANCE_LOW,
            ),
            NotificationChannel(
                NotificationHelper.CHANNEL_INVITES,
                "Invites",
                NotificationManager.IMPORTANCE_HIGH,
            ),
            NotificationChannel(
                NotificationHelper.CHANNEL_FRIEND_REQUEST,
                "Friend Requests",
                NotificationManager.IMPORTANCE_HIGH,
            ),
            NotificationChannel(
                NotificationHelper.CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        ).forEach(notificationManager::createNotificationChannel)
    }
}

private const val BOOT_RECONNECT_NOTIFICATION_ID = 99
private const val SERVICE_RECONNECT_NOTIFICATION_ID = 98

// Rolling ids stay above fixed service/worker ids so active notifications never replace each other.
private const val ROLLING_ID_BASE = 1100
private const val ROLLING_ID_COUNT = 10_000
