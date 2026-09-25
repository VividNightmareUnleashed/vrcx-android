package io.github.vrcxandroid.host

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Placeholder: host. */
class AndroidHostServices(private val app: Application) : HostServices {
    override val context: Context get() = app
    override suspend fun pickDocument(mimeTypes: List<String>): Uri? = null
    override suspend fun pickDirectory(): Uri? = null
    override suspend fun createDocument(suggestedName: String, mimeType: String): Uri? = null
    override suspend fun scanQrCode(): String? = null
    override fun openExternalUrl(url: String): Boolean = false
    override fun startIntent(intent: Intent): Boolean = false
    override fun canHandle(intent: Intent): Boolean = false
    override fun postDesktopNotification(title: String, body: String, largeIconPath: String?) {}
    override fun requestAttention() {}
    override fun setTrayNotify(notify: Boolean) {}
    override fun applySystemTheme(mode: Int) {}
    override val isInForeground: Boolean get() = false
    override fun emit(event: String, data: JsonElement?) {}
    override fun takeLaunchCommand(): String = ""
    override fun restartApp() {}
    override var backgroundMode: Boolean = true
    override fun requestIgnoreBatteryOptimizations() {}
    override fun openAppNotificationSettings() {}
    override val localRoot: File get() = File(app.filesDir, "local")
    override fun localUrlFor(file: File): String = ""
    override fun fileFor(pathOrUrl: String): File? = null
}
