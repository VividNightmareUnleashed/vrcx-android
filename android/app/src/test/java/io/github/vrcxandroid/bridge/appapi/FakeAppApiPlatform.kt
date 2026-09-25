package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.GameStateProvider
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.io.File

/**
 * In-memory [AppApiPlatform] for JVM tests. Files under [servedDir] are "served" as `{dir}/<name>`, mirroring how
 * the host exposes `/local/` URLs.
 */
class FakeAppApiPlatform(
    val root: File,
    val servedDir: File = File(root, "served").apply { mkdirs() },
    override val cacheDir: File = File(root, "cache").apply { mkdirs() },
) : AppApiPlatform {
    override var vrcxVersion: String = "2026.09.16"

    class State : GameStateProvider {
        override var isGameRunning = false
        override var isSteamVRRunning = false
        override var vrcClosedGracefully = true
    }

    override val gameState = State()
    override var httpClient: OkHttpClient = OkHttpClient()
    override var externalFilesDir: File? = File(root, "external").apply { mkdirs() }

    /** Like Android 11+: custom.js is read from the external folder too. */
    override var customScriptDir: File? = externalFilesDir
    override var images: ImageCodec = object : ImageCodec {
        override fun resizeToFitLimits(bytes: ByteArray, matchingDimensions: Boolean, maxWidth: Int, maxHeight: Int, maxSize: Long) = bytes
        override fun resizePrint(bytes: ByteArray) = bytes
        override fun cropPrint(bytes: ByteArray): ByteArray? = null
    }
    override var ugc: UgcStorage = FakeUgcStorage(File(root, "ugc"))
    override var photos: PhotosLibrary = FakePhotosLibrary(null)

    val events = mutableListOf<Pair<String, JsonElement?>>()
    var launchCommand = ""
    var restarts = 0
    override var isInForeground = true
    var attentionRequests = 0
    var trayNotify: Boolean? = null
    var theme: Int? = null
    val notifications = mutableListOf<Triple<String, String, String?>>()
    val openedUrls = mutableListOf<String>()
    val viewedUris = mutableListOf<Pair<String, String?>>()

    /** URIs (by prefix) an installed app would handle. */
    val handledUriPrefixes = mutableListOf<String>()
    val viewedDocs = mutableListOf<String>()
    var calendarOpened: Pair<File, IcsEvent?>? = null
    var clipboard = ""
    val clipboardImages = mutableListOf<String>()
    var formatTag = "de-DE"
    var uiTag = "de-DE"
    val logs = mutableListOf<String>()

    override fun emit(event: String, data: JsonElement?) {
        events += event to data
    }

    override fun takeLaunchCommand(): String = launchCommand.also { launchCommand = "" }
    override fun restartApp() {
        restarts++
    }

    override fun requestAttention() {
        attentionRequests++
    }

    override fun setTrayNotify(notify: Boolean) {
        trayNotify = notify
    }

    override fun applySystemTheme(mode: Int) {
        theme = mode
    }

    override fun postNotification(title: String, body: String, largeIconPath: String?) {
        notifications += Triple(title, body, largeIconPath)
    }

    override fun openExternalUrl(url: String): Boolean {
        openedUrls += url
        return true
    }

    /** The last onlyIfResolvable flag passed to [viewUri]. */
    var lastViewRequiredResolvable: Boolean? = null

    override fun viewUri(uri: String, mimeType: String?, onlyIfResolvable: Boolean): Boolean {
        lastViewRequiredResolvable = onlyIfResolvable
        if (handledUriPrefixes.none { uri.startsWith(it) }) return false
        viewedUris += uri to mimeType
        return true
    }

    override fun viewDoc(doc: Doc, mimeType: String): Boolean {
        viewedDocs += doc.key
        return true
    }

    override fun openCalendar(icsFile: File, event: IcsEvent?): Boolean {
        calendarOpened = icsFile to event
        return true
    }

    override suspend fun clipboardText(): String = clipboard
    override suspend fun copyImageToClipboard(doc: Doc): Boolean {
        clipboardImages += doc.key
        return true
    }

    override fun formatLocaleTag(): String = formatTag
    override fun uiLocaleTag(): String = uiTag
    override fun log(message: String, error: Throwable?) {
        logs += message
    }

    // ---- DocPlatform: {dir}/name <-> servedDir/name, and the cache directory as {cache}/...
    override fun fileFor(pathOrUrl: String): File? = when {
        pathOrUrl.startsWith("{dir}/") -> File(servedDir, pathOrUrl.removePrefix("{dir}/"))
        pathOrUrl.startsWith("{cache}/") -> File(cacheDir, pathOrUrl.removePrefix("{cache}/"))
        else -> null
    }

    override fun localUrlFor(file: File): String {
        val path = file.canonicalPath
        return when {
            path.startsWith(servedDir.canonicalPath + File.separator) ->
                "{dir}/" + path.removePrefix(servedDir.canonicalPath + File.separator).replace(File.separatorChar, '/')
            path.startsWith(cacheDir.canonicalPath + File.separator) ->
                "{cache}/" + path.removePrefix(cacheDir.canonicalPath + File.separator).replace(File.separatorChar, '/')
            else -> ""
        }
    }

    override fun servedRoots(): List<File> = listOf(servedDir, cacheDir)

    /** Content documents are simulated by files under root/content, addressed as content://test/<name>. */
    val contentDir = File(root, "content").apply { mkdirs() }

    override fun contentDoc(uri: String): Doc? {
        if (!uri.startsWith("content://test/")) return null
        return FakeContentDoc(uri, File(contentDir, uri.removePrefix("content://test/")))
    }

    /** A "content" document: same data as a file, but no local file for the resolver to serve. */
    class FakeContentDoc(override val key: String, private val backing: File) : Doc by LocalFileDoc(backing) {
        override val file: File? get() = null
        override fun siblings(): List<Doc>? = backing.parentFile?.listFiles()?.filter { it.isFile }?.map {
            FakeContentDoc("content://test/" + it.name, it)
        }
    }

    /**
     * Default root `ugc/default`. A path `tree:<name>` stands for a SAF tree: it is "granted" when the folder
     * `ugc/<name>` exists and then goes through [TreeUgc]; otherwise it falls back to the default like a revoked tree.
     */
    class FakeUgcStorage(private val root: File) : UgcStorage {
        val calls = mutableListOf<String?>()
        var failFolder: Exception? = null

        private fun treeFor(ugcFolderPath: String?): DocumentTree? {
            if (ugcFolderPath == null || !ugcFolderPath.startsWith("tree:")) return null
            val dir = File(root, ugcFolderPath.removePrefix("tree:"))
            return if (dir.isDirectory) FileDocumentTree(dir) else null
        }

        private fun defaultRoot() = File(root, "default")

        override fun folder(ugcFolderPath: String?, type: String, monthFolder: String): UgcFolder {
            calls += ugcFolderPath
            failFolder?.let { throw it }
            treeFor(ugcFolderPath)?.let { return TreeUgc.folder(it, type, monthFolder) }
            val dir = File(File(defaultRoot(), type), monthFolder).apply { mkdirs() }
            return object : UgcFolder {
                override fun exists(fileName: String) = File(dir, fileName).exists()
                override fun create(fileName: String, bytes: ByteArray): String {
                    val f = File(dir, fileName)
                    f.writeBytes(bytes)
                    return f.absolutePath
                }
            }
        }

        override fun listPngs(ugcFolderPath: String?, type: String): List<Doc> {
            treeFor(ugcFolderPath)?.let { return TreeUgc.listPngs(it, type) }
            return File(defaultRoot(), type).walkTopDown().filter { it.isFile && it.name.endsWith(".png", true) }.map { LocalFileDoc(it) }.toList()
        }

        override fun open(ugcFolderPath: String?): Boolean = (treeFor(ugcFolderPath)?.let { File(it.folderUri(it.rootId)) } ?: defaultRoot()).isDirectory
    }

    /** A photos root that is a local folder walked by the production [TreeWalk]. */
    class FakePhotosLibrary(var dir: File?) : PhotosLibrary {
        var opened = 0
        override fun location(): String = dir?.absolutePath.orEmpty()

        override fun listPngs(): List<PhotoEntry>? {
            val tree = FileDocumentTree(dir ?: return null)
            return TreeWalk.listPngs(tree, tree.rootId)
        }

        override fun open(): Boolean {
            opened++
            return dir != null
        }
    }
}

/** A local folder as a [DocumentTree]: ids are paths relative to [root] ("" for the root itself). */
class FileDocumentTree(private val root: File) : DocumentTree {
    /** Number of folder listings, to check that walks do not list folders more than once. */
    var listings = 0

    override val location: String get() = root.absolutePath
    override val rootId: String = ""

    private fun fileOf(id: String) = if (id.isEmpty()) root else File(root, id)
    private fun idOf(parentId: String, name: String) = if (parentId.isEmpty()) name else "$parentId/$name"

    override fun children(parentId: String): List<TreeEntry> {
        listings++
        return fileOf(parentId).listFiles().orEmpty().map { TreeEntry(idOf(parentId, it.name), LocalFileDoc(it), it.isDirectory) }
    }

    override fun createDirectory(parentId: String, name: String): String {
        val dir = File(fileOf(parentId), name)
        check(!dir.exists()) { "createDirectory called for an existing folder: $dir" }
        check(dir.mkdir())
        return idOf(parentId, name)
    }

    override fun createFile(parentId: String, name: String, mimeType: String, bytes: ByteArray): String {
        val file = File(fileOf(parentId), name)
        check(!file.exists()) { "createFile called for an existing file: $file" }
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun folderUri(id: String): String = fileOf(id).absolutePath
}
