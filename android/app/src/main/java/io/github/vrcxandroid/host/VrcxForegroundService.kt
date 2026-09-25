package io.github.vrcxandroid.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.vrcxandroid.R
import io.github.vrcxandroid.bridge.BridgeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Keeps the process (and so the page, its VRChat websocket and the companion stream) alive while background mode is on
 * (docs/ARCHITECTURE.md §7). Type `specialUse`. Its notification is the Android equivalent of the tray icon: Open and
 * Quit actions, the companion connection state, and a "new activity" mark from `electron.setTrayIconNotification`.
 * No wake locks. Not sticky: without the page there is nothing for it to keep alive.
 */
class VrcxForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(HostNotifications.ID_SERVICE, buildNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(HostNotifications.ID_SERVICE, buildNotification(this))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        if (!HostNotifications.canPost(this)) return
        try {
            NotificationManagerCompat.from(this).notify(HostNotifications.ID_SERVICE, buildNotification(this))
        } catch (e: SecurityException) {
            Log.w(TAG, "notification refused", e)
        }
    }

    companion object {
        private const val TAG = "VRCXService"

        @Volatile
        private var instance: VrcxForegroundService? = null

        @Volatile
        var trayNotify = false
            private set

        @Volatile
        private var companionStatus: String? = null

        @Volatile
        private var companionMachine: String? = null

        val isRunning: Boolean get() = instance != null

        /** Must be called while the app is visible (background FGS starts are refused on Android 12+). */
        fun start(context: Context) {
            if (instance != null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, VrcxForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VrcxForegroundService::class.java))
        }

        /** electron.setTrayIconNotification. */
        fun setTrayNotify(notify: Boolean) {
            if (trayNotify == notify) return
            trayNotify = notify
            VrcxHost.main.post { instance?.refresh() }
        }

        /** `companion-state` event text (`{"ev":"companion-state","d":{...}}`). */
        fun onCompanionState(text: String) {
            val state = try {
                (BridgeJson.parseToJsonElement(text) as? JsonObject)?.get("d") as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return
            val status = (state["status"] as? JsonPrimitive)?.contentOrNull
            val machine = (state["machineName"] as? JsonPrimitive)?.contentOrNull
            if (status == companionStatus && machine == companionMachine) return
            companionStatus = status
            companionMachine = machine
            VrcxHost.main.post { instance?.refresh() }
        }

        fun statusText(context: Context): String = when (companionStatus) {
            "connected" -> companionMachine?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.service_companion_connected, it) }
                ?: context.getString(R.string.service_companion_connected_unnamed)
            "connecting" -> context.getString(R.string.service_companion_connecting)
            "searching" -> context.getString(R.string.service_companion_searching)
            "error" -> context.getString(R.string.service_companion_error)
            else -> context.getString(R.string.service_running)
        }

        fun buildNotification(context: Context): Notification {
            val status = statusText(context)
            val text = if (trayNotify) context.getString(R.string.service_new_activity_with_status, status) else status
            val quit = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, HostActionReceiver::class.java).setAction(HostActionReceiver.ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = HostNotifications.openAppIntent(context, HostNotifications.ID_SERVICE)
            return NotificationCompat.Builder(context, HostNotifications.CHANNEL_SERVICE)
                .setSmallIcon(if (trayNotify) R.drawable.ic_stat_vrcx_notify else R.drawable.ic_stat_vrcx)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.action_open), open)
                .addAction(0, context.getString(R.string.action_quit), quit)
                .build()
        }
    }
}

/** Notification actions that must work even when no Activity exists (Quit). */
class HostActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_QUIT) VrcxHost.quit()
    }

    companion object {
        const val ACTION_QUIT = "io.github.vrcxandroid.action.QUIT"
    }
}
