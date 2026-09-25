package io.github.vrcxandroid.host

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * [HostServices] for the bridge modules (docs/ARCHITECTURE.md §6-7). A thin adapter over the host singletons
 * ([VrcxHost], [WebViewHolder], [ActivityPickers], [HostNotifications], ...). Constructed by AppGraph.init.
 */
class AndroidHostServices(private val app: Application) : HostServices {
    init {
        VrcxHost.init(app)
    }

    override val context: Context get() = app

    // ---- Pickers ----
    override suspend fun pickDocument(mimeTypes: List<String>): Uri? = ActivityPickers.openDocument(mimeTypes)
    override suspend fun pickDirectory(): Uri? = ActivityPickers.openDocumentTree()
    override suspend fun createDocument(suggestedName: String, mimeType: String): Uri? =
        ActivityPickers.createDocument(suggestedName, mimeType)
    override suspend fun scanQrCode(): String? = QrScanner.scan()

    // ---- Intents ----
    override fun openExternalUrl(url: String): Boolean = ExternalLinks.open(url)
    override fun startIntent(intent: Intent): Boolean = VrcxHost.startIntent(intent)
    override fun canHandle(intent: Intent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())) != null
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    } catch (e: Exception) {
        false
    }

    // ---- Notifications and window ----
    override fun postDesktopNotification(title: String, body: String, largeIconPath: String?) =
        HostNotifications.postDesktop(app, title, body, largeIconPath)
    override fun requestAttention() = HostNotifications.postAttention(app)
    override fun setTrayNotify(notify: Boolean) = VrcxForegroundService.setTrayNotify(notify)
    override fun applySystemTheme(mode: Int) = HostTheme.apply(mode)
    override val isInForeground: Boolean get() = VrcxHost.started

    // ---- Page ----
    override fun emit(event: String, data: JsonElement?) = VrcxHost.emit(event, data)
    override fun takeLaunchCommand(): String = VrcxHost.takeLaunchCommand()
    override fun restartApp() = VrcxHost.restartApp()

    // ---- Background mode ----
    override var backgroundMode: Boolean
        get() = VrcxHost.backgroundMode
        set(value) {
            VrcxHost.backgroundMode = value
        }

    override fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) return
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
        if (!VrcxHost.startIntent(request)) VrcxHost.startIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    override fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        if (!VrcxHost.startIntent(intent)) {
            VrcxHost.startIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        }
    }

    // ---- Local files ----
    override val localRoot: File get() = VrcxHost.paths.localRoot
    override fun localUrlFor(file: File): String = VrcxHost.paths.urlFor(file)
    override fun fileFor(pathOrUrl: String): File? = VrcxHost.paths.fileFor(pathOrUrl)
}
