package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.GameStateProvider
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.docs.DocPlatform
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.io.File

/**
 * Everything [AppApi] needs from Android. [AndroidAppApiPlatform] implements it on top of AppGraph (HostServices, the
 * WebApi OkHttp client, the LogWatcher game state); unit tests use a fake.
 */
interface AppApiPlatform : DocPlatform {
    /** Contents of web/Version (BuildConfig.VRCX_VERSION). */
    val vrcxVersion: String
    val gameState: GameStateProvider
    val httpClient: OkHttpClient
    val cacheDir: File

    /** `getExternalFilesDir(null)`, where users put custom.css / custom.js. */
    val externalFilesDir: File?

    val images: ImageCodec
    val ugc: UgcStorage
    val photos: PhotosLibrary

    fun emit(event: String, data: JsonElement?)
    fun takeLaunchCommand(): String
    fun restartApp()
    val isInForeground: Boolean
    fun requestAttention()
    fun setTrayNotify(notify: Boolean)
    fun applySystemTheme(mode: Int)
    fun postNotification(title: String, body: String, largeIconPath: String?)

    /** Opens an http(s) URL in a Custom Tab or browser. */
    fun openExternalUrl(url: String): Boolean

    /**
     * ACTION_VIEW on [uri]. Returns whether an activity was started (false when nothing handles it). With
     * [onlyIfResolvable] the intent is not even sent unless the package manager resolves it first (StartGame).
     */
    fun viewUri(uri: String, mimeType: String? = null, onlyIfResolvable: Boolean = false): Boolean

    /** ACTION_VIEW on a document (FileProvider URI for local files) with a read grant. */
    fun viewDoc(doc: Doc, mimeType: String): Boolean

    /** Opens an .ics file in a calendar app; falls back to an insert-event intent built from the VEVENT. */
    fun openCalendar(icsFile: File, event: IcsEvent?): Boolean

    suspend fun clipboardText(): String
    suspend fun copyImageToClipboard(doc: Doc): Boolean

    /** BCP-47 tag of the formatting locale (`CultureInfo.CurrentCulture`). */
    fun formatLocaleTag(): String

    /** BCP-47 tag of the first UI language (`CultureInfo.InstalledUICulture`). */
    fun uiLocaleTag(): String

    fun log(message: String, error: Throwable? = null)
}

/** One PNG below the photos root; [relativeDir] is the folder path below the root ("" for the root itself). */
class PhotoEntry(val doc: Doc, val relativeDir: String)

/** The folder the screenshot tools search (upstream `GetVRChatPhotosLocation`). */
interface PhotosLibrary {
    /** The photos root as a string (a SAF tree URI), or "" when none is configured. */
    fun location(): String

    /** Every `*.png` below the root, recursively; null when there is no root. */
    fun listPngs(): List<PhotoEntry>?

    /** Opens the root in a file manager, asking the user to pick one first when none is configured. */
    suspend fun open(): Boolean
}

/** Where prints, stickers and emoji are saved: MediaStore `Pictures/VRCX/...` or a SAF tree chosen by the user. */
interface UgcStorage {
    /** `<root>/<type>/<monthFolder>`, created when missing. Throws when it cannot be created. */
    fun folder(ugcFolderPath: String?, type: String, monthFolder: String): UgcFolder

    /** Every PNG below `<root>/<type>`, recursively. */
    fun listPngs(ugcFolderPath: String?, type: String): List<Doc>

    /** Opens the root in a file manager; false when it does not exist or nothing can show it. */
    fun open(ugcFolderPath: String?): Boolean
}

interface UgcFolder {
    fun exists(fileName: String): Boolean

    /** Writes a new file and returns its path string (a content URI or an absolute path). */
    fun create(fileName: String, bytes: ByteArray): String
}
