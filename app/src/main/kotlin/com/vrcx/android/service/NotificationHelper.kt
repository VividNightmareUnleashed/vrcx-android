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
import com.vrcx.android.ui.navigation.DeepLinkSection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The screen a notification should open, or null to just bring the app up. */
data class NotificationTarget(val section: DeepLinkSection, val id: String)

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId = AtomicInteger(ROLLING_ID_BASE)

    init {
        if (channelsCreated.compareAndSet(false, true)) {
            createNotificationChannels()
        }
    }

    fun notifyFriendOnline(displayName: String, userId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_ONLINE,
            title = displayName,
            text = "is online",
            target = userTarget(userId),
        )
    }

    fun notifyFriendOffline(displayName: String, userId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_OFFLINE,
            title = displayName,
            text = "went offline",
            target = userTarget(userId),
        )
    }

    fun notifyInvite(senderName: String, senderUserId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_INVITES,
            title = senderName,
            text = "sent you an invite",
            target = userTarget(senderUserId),
        )
    }

    fun notifyFriendRequest(senderName: String, senderUserId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_FRIEND_REQUEST,
            title = senderName,
            text = "sent you a friend request",
            target = userTarget(senderUserId),
        )
    }

    fun notifyFriendLocation(displayName: String, worldName: String, userId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = displayName,
            text = if (worldName.isNotEmpty()) "joined $worldName" else "changed location",
            target = userTarget(userId),
        )
    }

    fun notifyFriendStatusChange(displayName: String, newStatus: String, userId: String? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = displayName,
            text = "changed status to $newStatus",
            target = userTarget(userId),
        )
    }

    fun notifyGeneral(title: String, text: String, target: NotificationTarget? = null) {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = title,
            text = text,
            target = target,
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

    fun notifyServiceReconnectRequired() {
        post(
            channel = WebSocketForegroundService.CHANNEL_GENERAL,
            title = "Open VRCX to reconnect",
            text = "Android paused the background connection after its service time limit.",
            notificationId = SERVICE_RECONNECT_NOTIFICATION_ID,
        )
    }

    fun cancelServiceReconnectRequired() {
        notificationManager.cancel(SERVICE_RECONNECT_NOTIFICATION_ID)
    }

    private fun userTarget(userId: String?): NotificationTarget? =
        userId?.takeIf { it.isNotEmpty() }?.let { NotificationTarget(DeepLinkSection.USER, it) }

    private fun post(
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

    /**
     * A notification whose text names a person or a place should open that
     * screen. Without a target it lands wherever the app happens to start, and
     * the user is left to find the row it was telling them about.
     */
    private fun contentIntent(target: NotificationTarget?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (target != null) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(target.section.appUri(target.id))
            }
            // MainActivity is singleTop, so a running app takes this as a new
            // intent rather than stacking a second shell behind the first.
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // PendingIntents are matched ignoring extras, so give each target its own
        // request code rather than letting them share slot 0.
        return PendingIntent.getActivity(
            context,
            target?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
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
        private const val BOOT_RECONNECT_NOTIFICATION_ID = 99
        private const val SERVICE_RECONNECT_NOTIFICATION_ID = 98

        /**
         * Where the rolling ids start. Above every fixed id in the app — the
         * foreground service's 1 and BootReconnectWorker's 1001, which
         * WorkManager owns for as long as the worker runs — so a busy session
         * can't roll onto one of them.
         */
        private const val ROLLING_ID_BASE = 1100
        private const val ROLLING_ID_COUNT = 10_000

        // Four components construct a helper, each construction re-issuing six
        // binder calls to create channels that already exist. Once per process
        // is enough; the system keeps them beyond that.
        private val channelsCreated = AtomicBoolean(false)
    }
}
