package io.github.vrcxandroid.bridge.appapi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.FileProvider
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.BuildConfig
import io.github.vrcxandroid.GameStateProvider
import io.github.vrcxandroid.bridge.appapi.docs.ContentDoc
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.host.HostServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

/** [AppApiPlatform] on Android, through the collaborators wired in [AppGraph]. */
class AndroidAppApiPlatform(private val context: Context) : AppApiPlatform {
    private val host: HostServices get() = AppGraph.host
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val vrcxVersion: String = BuildConfig.VRCX_VERSION
    override val gameState: GameStateProvider get() = AppGraph.gameState
    override val httpClient: OkHttpClient get() = AppGraph.http.client
    override val cacheDir: File get() = context.cacheDir
    override val externalFilesDir: File? get() = context.getExternalFilesDir(null)
    override val customScriptDir: File?
        get() = CustomFiles.scriptDir(Build.VERSION.SDK_INT, externalFilesDir, context.filesDir)

    override val images: ImageCodec = AndroidImageCodec()
    override val ugc: UgcStorage = AndroidUgcStorage(context, prefs) { uri, type -> viewUri(uri.toString(), type) }
    override val photos: PhotosLibrary = AndroidPhotosLibrary(
        context,
        prefs,
        view = { uri, type -> viewUri(uri.toString(), type) },
        picker = BackgroundPicker(AppGraph.scope, { host.pickDirectory() }, ::log),
    )

    override fun emit(event: String, data: JsonElement?) = host.emit(event, data)
    override fun takeLaunchCommand(): String = host.takeLaunchCommand()
    override fun restartApp() = host.restartApp()
    override val isInForeground: Boolean get() = host.isInForeground
    override fun requestAttention() = host.requestAttention()
    override fun setTrayNotify(notify: Boolean) = host.setTrayNotify(notify)
    override fun applySystemTheme(mode: Int) = host.applySystemTheme(mode)

    override fun postNotification(title: String, body: String, largeIconPath: String?) =
        host.postDesktopNotification(title, body, largeIconPath)

    override fun openExternalUrl(url: String): Boolean = host.openExternalUrl(url)

    /**
     * Only StartGame asks the package manager first: other targets (folders in the Files app, calendar inserts) are
     * not in the manifest's `<queries>`, so resolving them would fail on Android 11+ although starting them works;
     * [HostServices.startIntent] returns false when nothing handles the intent.
     */
    override fun viewUri(uri: String, mimeType: String?, onlyIfResolvable: Boolean): Boolean {
        val intent = viewIntent(Uri.parse(uri), mimeType, grantRead = false)
        if (onlyIfResolvable && !host.canHandle(intent)) return false
        return host.startIntent(intent)
    }

    /** The document itself (SAF/MediaStore) or a FileProvider URI, with a read grant for the viewer. */
    override fun viewDoc(doc: Doc, mimeType: String): Boolean {
        val uri = contentUriFor(doc) ?: return false
        return host.startIntent(viewIntent(uri, mimeType, grantRead = true))
    }

    /**
     * A read grant is only added for documents this app can read: granting a URI it holds no permission for (such as
     * a Files-app folder) makes startActivity throw.
     */
    private fun viewIntent(uri: Uri, mimeType: String?, grantRead: Boolean) = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType != null) setDataAndType(uri, mimeType) else data = uri
        if (grantRead) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun openCalendar(icsFile: File, event: IcsEvent?): Boolean {
        fileProviderUri(icsFile)?.let { uri ->
            if (host.startIntent(viewIntent(uri, "text/calendar", grantRead = true))) return true
        }
        if (event == null) return false
        val insert = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            event.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
            event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            event.beginMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (event.allDay) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        return host.startIntent(insert)
    }

    override suspend fun clipboardText(): String = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun copyImageToClipboard(doc: Doc): Boolean {
        val uri = contentUriFor(doc) ?: return false
        return withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@withContext false
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "image", uri))
            true
        }
    }

    override fun formatLocaleTag(): String = VersionInfo.cultureTag(Locale.getDefault(Locale.Category.FORMAT))

    override fun uiLocaleTag(): String = VersionInfo.cultureTag(LocaleList.getDefault().get(0))

    override fun log(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    // ---- DocPlatform

    override fun fileFor(pathOrUrl: String): File? = try {
        host.fileFor(pathOrUrl)
    } catch (e: Exception) {
        null
    }

    override fun localUrlFor(file: File): String = try {
        host.localUrlFor(file)
    } catch (e: Exception) {
        ""
    }

    override fun servedRoots(): List<File> = listOf(host.localRoot, context.cacheDir)

    override fun contentDoc(uri: String): Doc? = try {
        ContentDoc(context, Uri.parse(uri))
    } catch (e: Exception) {
        null
    }

    /** A content URI other apps can read: the document itself, or a FileProvider URI for a local file. */
    private fun contentUriFor(doc: Doc): Uri? = when {
        doc is ContentDoc -> doc.uri
        doc.file != null -> fileProviderUri(doc.file!!)
        else -> null
    }

    private fun fileProviderUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    } catch (e: Exception) {
        // No FileProvider declared for this path (see the manifest note in the package's open issues).
        Log.w(TAG, "No FileProvider for ${file.name}", e)
        null
    }

    companion object {
        private const val TAG = "VRCXAppApi"
        private const val PREFS = "vrcx_appapi"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
