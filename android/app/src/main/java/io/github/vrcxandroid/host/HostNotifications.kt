package io.github.vrcxandroid.host

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.vrcxandroid.R
import java.io.File

/**
 * Notification channels and the VRCX "desktop" notifications (docs/ARCHITECTURE.md §6.11): channel
 * `vrcx_notifications` for `electron.desktopNotification` and attention prompts, channel `vrcx_service` for the
 * foreground service.
 */
object HostNotifications {
    private const val TAG = "VRCXNotify"

    const val CHANNEL_NOTIFICATIONS = "vrcx_notifications"
    const val CHANNEL_SERVICE = "vrcx_service"

    const val ID_SERVICE = 1
    /** One fixed id, so a new desktop notification replaces the previous one (Electron parity). */
    const val ID_DESKTOP = 1001
    const val ID_ATTENTION = 1002

    private const val KEY_PERMISSION_BLOCKED = "notification_permission_blocked"
    private const val KEY_PERMISSION_SILENT_DENIALS = "notification_permission_silent_denials"
    private const val LARGE_ICON_MAX_PX = 256

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notifications = NotificationChannel(
            CHANNEL_NOTIFICATIONS, context.getString(R.string.channel_notifications), NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_notifications_description) }
        val service = NotificationChannel(
            CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_service_description)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(notifications, service))
    }

    /** PendingIntent that brings MainActivity to the front. */
    fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 'granted' | 'denied' | 'default' (AndroidHost.GetNotificationPermission). 'default' while the prompt can still
     * be shown, also after one denial or a dismissed prompt ([NotificationPermissionPolicy]).
     */
    fun permissionState(context: Context): String {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return if (enabled) "granted" else "denied"
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // A grant (also one made in system settings) starts over, so a later revocation is judged afresh.
        if (granted && permissionRecord() != NotificationPermissionPolicy.Record()) savePermissionRecord(NotificationPermissionPolicy.Record())
        return NotificationPermissionPolicy.state(granted, enabled, permissionRecord())
    }

    /**
     * Shows the runtime permission prompt on Android 13+, then returns [permissionState]. Only while the app is
     * visible: in the background the prompt is not shown and nothing is recorded.
     */
    suspend fun requestPermission(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return permissionState(context)
        if (permissionState(context) == "granted") return "granted"
        val answer = ActivityPickers.requestPermission(Manifest.permission.POST_NOTIFICATIONS) ?: return permissionState(context)
        savePermissionRecord(
            NotificationPermissionPolicy.afterRequest(permissionRecord(), answer.granted, answer.rationaleBefore, answer.rationaleAfter),
        )
        // The service notification posted before the grant stays hidden until it is posted again.
        if (answer.granted) VrcxForegroundService.refreshIfRunning()
        return permissionState(context)
    }

    private fun permissionRecord() = NotificationPermissionPolicy.Record(
        blocked = VrcxHost.prefs.getBoolean(KEY_PERMISSION_BLOCKED, false),
        silentDenials = VrcxHost.prefs.getInt(KEY_PERMISSION_SILENT_DENIALS, 0),
    )

    private fun savePermissionRecord(record: NotificationPermissionPolicy.Record) {
        VrcxHost.prefs.edit()
            .putBoolean(KEY_PERMISSION_BLOCKED, record.blocked)
            .putInt(KEY_PERMISSION_SILENT_DENIALS, record.silentDenials)
            .apply()
    }

    /** electron.desktopNotification(title, body, icon). [iconPath] is a local path or /local/ URL, or empty. */
    @SuppressLint("MissingPermission")
    fun postDesktop(context: Context, title: String, body: String, iconPath: String?) {
        if (!canPost(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_stat_vrcx)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context, ID_DESKTOP))
        loadLargeIcon(iconPath)?.let { builder.setLargeIcon(it) }
        try {
            NotificationManagerCompat.from(context).notify(ID_DESKTOP, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification refused", e)
        }
    }

    /** AppApi.FlashWindow/FocusWindow: only when the app is not visible. */
    @SuppressLint("MissingPermission")
    fun postAttention(context: Context) {
        if (VrcxHost.started || !canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_stat_vrcx)
            .setContentTitle(context.getString(R.string.attention_title))
            .setContentText(context.getString(R.string.attention_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, ID_ATTENTION))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_ATTENTION, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification refused", e)
        }
    }

    fun cancelAttention(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ATTENTION)
    }

    private fun loadLargeIcon(iconPath: String?): Bitmap? {
        if (iconPath.isNullOrBlank()) return null
        val file: File = VrcxHost.paths.fileFor(iconPath) ?: return null
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= LARGE_ICON_MAX_PX && bounds.outHeight / (sample * 2) >= LARGE_ICON_MAX_PX) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (t: Throwable) {
            null
        }
    }
}
