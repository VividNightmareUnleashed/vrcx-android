package io.github.vrcxandroid.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Everything bridge modules need from the Android host (Activity, notifications, pickers, intents). Implemented by
 * [AndroidHostServices]. Methods that need an Activity return null/false when none is attached.
 */
interface HostServices {
    /** Application context. */
    val context: Context

    // ---- Pickers (suspend until the user answers; null when cancelled or no Activity is attached) ----
    suspend fun pickDocument(mimeTypes: List<String>): Uri?
    /** ACTION_OPEN_DOCUMENT_TREE; the returned tree URI has a persisted read/write permission. */
    suspend fun pickDirectory(): Uri?
    /** ACTION_CREATE_DOCUMENT with a suggested file name. */
    suspend fun createDocument(suggestedName: String, mimeType: String): Uri?
    /** Scans a QR code (Google code scanner when available). Returns the raw text, or null. */
    suspend fun scanQrCode(): String?

    // ---- Intents ----
    /** Opens an http(s) URL in a Custom Tab or browser. Returns false for other schemes or when nothing handles it. */
    fun openExternalUrl(url: String): Boolean
    /** Starts [intent] if something resolves it (adds NEW_TASK when there is no Activity). */
    fun startIntent(intent: Intent): Boolean
    fun canHandle(intent: Intent): Boolean

    // ---- Notifications and window ----
    /** VRCX "desktop" notification: channel vrcx_notifications, large icon from a local file path, opens the app. */
    fun postDesktopNotification(title: String, body: String, largeIconPath: String?)
    /** FlashWindow/FocusWindow: a high-priority attention notification when the app is in the background. */
    fun requestAttention()
    /** electron.setTrayIconNotification: marks the foreground-service notification as having new activity. */
    fun setTrayNotify(notify: Boolean)
    /** AppApi.ChangeTheme(0 light, 1 dark, 2 midnight): system bar icons and window background. */
    fun applySystemTheme(mode: Int)
    /** True while an Activity with the WebView is started (visible). */
    val isInForeground: Boolean

    // ---- Page ----
    /** Sends a native → JS event {ev, d} (see ARCHITECTURE.md §4.2 for event names). */
    fun emit(event: String, data: JsonElement? = null)
    /** Returns the pending launch command once (vrcx:// deep link or crash/...), then "". */
    fun takeLaunchCommand(): String
    /** Drains the bridge, flushes VRCXStorage and cookies, then restarts the process. */
    fun restartApp()

    // ---- Background mode (ARCHITECTURE.md §7) ----
    var backgroundMode: Boolean
    fun requestIgnoreBatteryOptimizations()
    fun openAppNotificationSettings()

    // ---- Local files the page can display (ARCHITECTURE.md §6.6) ----
    /** Root served at https://appassets.androidplatform.net/local/ (filesDir/local). */
    val localRoot: File
    /** URL under /local/ for a file inside [localRoot] or cacheDir (served under /local/cache/). */
    fun localUrlFor(file: File): String
    /** Resolves either a /local/ URL or an absolute path to a File; null when outside the allowed roots. */
    fun fileFor(pathOrUrl: String): File?
}
