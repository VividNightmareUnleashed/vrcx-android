package io.github.vrcxandroid.host

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Removes what the 1.x app (the native Compose app this one replaces under the same application id) left in the
 * app's storage when it is updated in place: its Room database, settings, cookies, encrypted secrets, WorkManager
 * state and notification channels. None of it is read by this app, and the stored session must not outlive it.
 * Runs once per install; a fresh install finds nothing to delete.
 */
object LegacyAppCleanup {
    private const val TAG = "VRCXLegacyCleanup"
    private const val PREFS = "vrcx_host"
    private const val KEY_DONE = "legacy_app_cleanup_done"

    private val DATABASES = listOf("vrcx.db", "androidx.work.workdb")
    private val SHARED_PREFS = listOf("vrcx_cookies", "androidx.work.util.preferences")
    private val FILES = listOf("files/vrcx_secure_secrets.json", "files/datastore/vrcx_settings.preferences_pb")
    private val CHANNELS =
        listOf("vrcx_friend_online", "vrcx_friend_offline", "vrcx_invites", "vrcx_friend_request", "vrcx_general")

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        try {
            DATABASES.forEach { context.deleteDatabase(it) }
            SHARED_PREFS.forEach { context.deleteSharedPreferences(it) }
            val dataDir = context.filesDir.parentFile
            if (dataDir != null) FILES.forEach { File(dataDir, it).delete() }
            context.getSystemService(NotificationManager::class.java)?.let { nm ->
                CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Removing the 1.x app data failed", e)
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
