package io.github.vrcxandroid.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.webkit.WebViewCompat
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.BuildConfig
import io.github.vrcxandroid.R
import io.github.vrcxandroid.host.AndroidHostServices
import io.github.vrcxandroid.host.DatabaseImportFlow
import io.github.vrcxandroid.host.HostFiles
import io.github.vrcxandroid.host.HostNotifications
import io.github.vrcxandroid.host.VrcxHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `AndroidHost` (docs/ARCHITECTURE.md §5.1): Android-only helpers reached through the normal bridge, plus the `Electron*`
 * methods the shim uses to implement `window.electron`.
 *
 * Not serialized: pickers wait for the user and must not hold up quick calls such as TtsCancel or CompanionGetState.
 * The shim chains the TTS calls itself, so their order is kept.
 */
class AndroidHostModule(private val context: Context) : BridgeModule {
    override val className = "AndroidHost"
    override val serialized: Boolean get() = false

    private val host get() = AppGraph.host

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = when (method) {
        // ---- files and clipboard ----
        "SaveFile" -> saveFile(args.str(0), args.str(1), args.str(2))
        "CopyText" -> copyText(args.str(0).orEmpty())
        "CopyImage" -> copyImage(requireArg(args.str(0), "base64Png"))
        "ReadClipboardText", "ElectronGetClipboardText" -> jsonOf(readClipboardText())

        // ---- text to speech ----
        "TtsGetVoices" -> AppGraph.tts.voices()
        "TtsSpeak" -> {
            AppGraph.tts.speak(args.obj(0) ?: throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'utterance')"))
            JsonNull
        }
        "TtsCancel" -> {
            AppGraph.tts.cancel()
            JsonNull
        }

        // ---- PC companion ----
        "CompanionGetState" -> AppGraph.companion.state()
        "CompanionDiscover" -> AppGraph.companion.discover(args.long(0) ?: DEFAULT_DISCOVERY_MS)
        "CompanionScanQr" -> {
            val payload = host.scanQrCode() ?: throw DotNetException("OperationCanceledException", "The operation was canceled.")
            AppGraph.companion.pairWithQr(payload)
        }
        // Manual address entry uses the same pairing call ({host, port[, fp]} + the code shown on the PC).
        "CompanionPair", "CompanionConnectManual" -> AppGraph.companion.pair(
            args.obj(0) ?: throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'target')"),
            args.str(1).orEmpty(),
        )
        "CompanionForget" -> {
            AppGraph.companion.forget(requireArg(args.str(0), "id"))
            AppGraph.companion.state()
        }
        "CompanionSetActive" -> {
            AppGraph.companion.setActive(requireArg(args.str(0), "id"))
            AppGraph.companion.state()
        }

        // ---- database ----
        "ImportDatabase" -> importDatabase()
        "ExportDatabase" -> exportDatabase()

        // ---- background mode, battery, notifications ----
        "GetBackgroundMode" -> jsonOf(host.backgroundMode)
        "SetBackgroundMode" -> {
            host.backgroundMode = args.bool(0) ?: true
            jsonOf(host.backgroundMode)
        }
        "IsIgnoringBatteryOptimizations" -> jsonOf((host as? AndroidHostServices)?.isIgnoringBatteryOptimizations() == true)
        "RequestIgnoreBatteryOptimizations" -> {
            host.requestIgnoreBatteryOptimizations()
            JsonNull
        }
        "OpenNotificationSettings" -> {
            host.openAppNotificationSettings()
            JsonNull
        }
        "GetNotificationPermission" -> jsonOf(HostNotifications.permissionState(context))
        "RequestNotificationPermission" -> jsonOf(HostNotifications.requestPermission(context))
        "SetKeepScreenOn" -> {
            VrcxHost.setKeepScreenOn(KEEP_ON_PAGE, args.bool(0) == true)
            JsonNull
        }
        "ShowKeyboard" -> {
            VrcxHost.showKeyboard()
            JsonNull
        }

        // ---- app ----
        "CanLaunchVRChat" -> jsonOf(host.canHandle(Intent(Intent.ACTION_VIEW, Uri.parse("vrchat://launch"))))
        "RestartApp", "ElectronRestartApp" -> {
            host.restartApp()
            awaitCancellation()
        }
        "GetDeviceInfo" -> deviceInfo()

        // ---- window.electron ----
        "ElectronOpenFileDialog" -> jsonOf(openPngFile())
        "ElectronOpenDirectoryDialog" -> jsonOf(host.pickDirectory()?.toString())
        "ElectronDesktopNotification" -> {
            host.postDesktopNotification(args.str(0).orEmpty(), args.str(1).orEmpty(), args.str(2))
            JsonNull
        }
        "ElectronSetTrayIconNotification" -> {
            host.setTrayNotify(args.bool(0) == true)
            JsonNull
        }

        else -> missingMethod(className, method)
    }

    /** `<a download>` and exports: ACTION_CREATE_DOCUMENT, true when written, false when cancelled. */
    private suspend fun saveFile(fileName: String?, mimeType: String?, base64: String?): JsonElement {
        val data = requireArg(base64, "base64")
        val bytes = withContext(Dispatchers.Default) { decodeBase64(data) }
        val name = fileName?.takeIf { it.isNotBlank() } ?: "download"
        val uri = host.createDocument(name, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            ?: return jsonOf(false)
        withContext(Dispatchers.IO) { HostFiles.writeBytes(context, uri, bytes) }
        return jsonOf(true)
    }

    private suspend fun copyText(text: String): JsonElement {
        withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VRCX", text))
        }
        return jsonOf(true)
    }

    private suspend fun copyImage(base64: String): JsonElement {
        val bytes = withContext(Dispatchers.Default) { decodeBase64(base64) }
        withContext(Dispatchers.IO) { HostFiles.copyImageToClipboard(context, bytes) }
        return jsonOf(true)
    }

    /** Clipboard text or '' (Android only lets the focused foreground app read it). */
    private suspend fun readClipboardText(): String = withContext(Dispatchers.Main) {
        try {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * electron.openFileDialog: SAF pick of a PNG, copied under filesDir/local/picked/ (docs/ARCHITECTURE.md §6.6).
     * Resolves the absolute path, which AppApi file methods accept and the page can also use as `<img src>`; '' on
     * cancel (ScreenshotMetadata.vue only treats '' as cancel).
     */
    private suspend fun openPngFile(): String {
        val uri = host.pickDocument(listOf("image/png")) ?: return ""
        return withContext(Dispatchers.IO) {
            HostFiles.copyToLocal(context, uri, PICKED_DIR, "image.png").absolutePath
        }
    }

    /**
     * SAF pick of VRCX.sqlite3 and optionally VRCX.json, then import. The page's other calls are held
     * during the import and VRCXStorage and the cookie jar are saved before it; on success the app restarts without
     * saving them again, so the imported VRCX.json and cookies survive (see [DatabaseImportFlow]).
     */
    private suspend fun importDatabase(): JsonElement {
        VrcxHost.setKeepScreenOn(KEEP_ON_IMPORT, true)
        try {
            val flow = DatabaseImportFlow(
                pickDatabase = {
                    toast(R.string.import_pick_database)
                    host.pickDocument(listOf("*/*"))
                },
                pickJson = {
                    toast(R.string.import_pick_json)
                    host.pickDocument(listOf("*/*"))
                },
                prepare = VrcxHost::prepareDatabaseImport,
                import = { database, json -> AppGraph.database.importFrom(database, json) },
                succeeded = { (it["ok"] as? JsonPrimitive)?.content == "true" },
                resume = VrcxHost::resumeAfterFailedImport,
                restartAfterImport = VrcxHost::restartAfterImport,
            )
            return when (val r = flow.run()) {
                DatabaseImportFlow.Result.Cancelled -> result(false, "Cancelled")
                DatabaseImportFlow.Result.Busy -> result(false, "The app is restarting")
                is DatabaseImportFlow.Result.Done -> r.value
            }
        } finally {
            VrcxHost.setKeepScreenOn(KEEP_ON_IMPORT, false)
        }
    }

    private suspend fun exportDatabase(): JsonElement {
        val uri = host.createDocument("VRCX.sqlite3", "application/octet-stream") ?: return jsonOf(false)
        AppGraph.database.exportTo(uri)
        return jsonOf(true)
    }

    private fun deviceInfo(): JsonObject {
        val webView = try {
            WebViewCompat.getCurrentWebViewPackage(context)?.versionName
        } catch (t: Throwable) {
            null
        }
        return buildJsonObject {
            put("model", listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct().joinToString(" "))
            put("sdkInt", Build.VERSION.SDK_INT)
            put("webViewVersion", webView)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("vrcxVersion", BuildConfig.VRCX_VERSION)
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private suspend fun toast(res: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
    }

    private fun decodeBase64(data: String): ByteArray {
        val payload = data.substringAfter("base64,", data)
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw DotNetException("FormatException", "The input is not a valid Base-64 string.")
        }
    }

    companion object {
        private const val DEFAULT_DISCOVERY_MS = 3_000L
        private const val PICKED_DIR = "picked"
        private const val KEEP_ON_IMPORT = "import"
        private const val KEEP_ON_PAGE = "page"

    }
}
