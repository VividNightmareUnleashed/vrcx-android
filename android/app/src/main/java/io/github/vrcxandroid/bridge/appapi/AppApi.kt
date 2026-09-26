package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.docs.DocResolver
import io.github.vrcxandroid.bridge.appapi.png.PngChunkType
import io.github.vrcxandroid.bridge.appapi.png.PngFile
import io.github.vrcxandroid.bridge.appapi.png.PngHelper
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotMetadata
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotParser
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotSearchIndex
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotTimes
import io.github.vrcxandroid.bridge.arr
import io.github.vrcxandroid.bridge.bool
import io.github.vrcxandroid.bridge.int
import io.github.vrcxandroid.bridge.jsonOf
import io.github.vrcxandroid.bridge.missingMethod
import io.github.vrcxandroid.bridge.requireArg
import io.github.vrcxandroid.bridge.str
import io.github.vrcxandroid.bridge.strOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Android `AppApiElectron` (JS `window.AppApi`): every method the frontend calls, with the return shapes the
 * frontend expects. Portable helpers are byte-exact ports of upstream Dotnet/AppApi; Android
 * equivalents go through [AppApiPlatform]; PC-only methods return the documented safe values.
 */
class AppApi(
    private val platform: AppApiPlatform,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val imageCache = ImageCache(File(platform.cacheDir, "ImageCache"), { platform.httpClient }, ::getVersion, platform::log)
    val docs = DocResolver(platform, File(platform.cacheDir, "screenshot-mirror"))
    private val searchIndex = ScreenshotSearchIndex(File(platform.cacheDir, "screenshot-search-index.json"))

    /** Paths returned by the last search; their first `GetExtraScreenshotData(path, false)` is the table enrichment. */
    private val pendingEnrichment = HashSet<String>()

    suspend fun call(method: String, a: JsonArray): JsonElement = when (method) {
        // ---- app, window and shell
        "Init", "ShowDevTools", "SetVR", "ExecuteVrOverlayFunction", "DoFunny", "SetStartup", "SetAppLauncherSettings",
        "SetUserAgent", "IPCAnnounceStart", "SendIpc", "XSNotification", "OVRTNotification", "SetZoom",
        "OpenShortcutFolder", "CancelUpdate", "WriteConfigFile", "DeleteVRChatRegistryFolder",
        "SetVRChatRegistryKeyAsync",
        -> JsonNull
        "GetZoom" -> jsonOf(0)
        "GetVersion" -> jsonOf(getVersion())
        "GetLaunchCommand" -> jsonOf(platform.takeLaunchCommand())
        "RestartApplication" -> {
            platform.restartApp()
            JsonNull
        }
        "FocusWindow", "FlashWindow" -> {
            if (!platform.isInForeground) platform.requestAttention()
            JsonNull
        }
        "SetTrayIconNotification" -> {
            platform.setTrayNotify(a.bool(0) ?: false)
            JsonNull
        }
        "ChangeTheme" -> {
            platform.applySystemTheme(a.int(0) ?: 0)
            JsonNull
        }
        "DesktopNotification" -> {
            desktopNotification(a.strOr(0, ""), a.strOr(1, ""), a.strOr(2, ""))
            JsonNull
        }
        "GetClipboard" -> jsonOf(platform.clipboardText())
        "CopyImageToClipboard" -> {
            copyImageToClipboard(a.str(0))
            JsonNull
        }
        "OpenLink" -> {
            openLink(a.str(0))
            JsonNull
        }
        "OpenDiscordProfile" -> {
            openDiscordProfile(a.str(0))
            JsonNull
        }
        "OpenCalendarFile" -> {
            openCalendarFile(a.str(0))
            JsonNull
        }
        "CustomCss" -> jsonOf(CustomFiles.read(platform.customDir, CustomFiles.CSS))
        "CustomScript" -> jsonOf(CustomFiles.read(platform.customDir, CustomFiles.SCRIPT))
        "CurrentCulture" -> jsonOf(platform.formatLocaleTag().ifEmpty { "en-US" })
        "CurrentLanguage" -> jsonOf(platform.uiLocaleTag())

        // ---- game state (companion-fed LogWatcher)
        "IsGameRunning" -> jsonOf(platform.gameState.isGameRunning)
        "IsSteamVRRunning" -> jsonOf(platform.gameState.isSteamVRRunning)
        "VrcClosedGracefully" -> jsonOf(platform.gameState.vrcClosedGracefully)
        "CheckGameRunning" -> {
            platform.emit("game-state", gameStatePayload())
            JsonNull
        }
        "QuitGame" -> jsonOf(0)
        "StartGame" -> jsonOf(startGame(a.str(0)))
        "StartGameFromPath" -> jsonOf(startGame(a.str(1)))
        "TryOpenInstanceInVrc" -> jsonOf(false)

        // ---- portable helpers
        "GetColourFromUserID" -> jsonOf(Hashing.colourFromUserId(requireArg(a.str(0), "userId")))
        "GetColourBulk" -> colourBulk(a)
        "MD5File" -> jsonOf(withContext(Dispatchers.Default) { Hashing.md5File(requireArg(a.str(0), "blob")) })
        "SignFile" -> jsonOf(withContext(Dispatchers.Default) { Hashing.signFile(requireArg(a.str(0), "blob")) })
        "FileLength" -> jsonOf(Hashing.fileLength(requireArg(a.str(0), "blob")))
        "ResizeImageToFitLimits" -> jsonOf(resizeImageToFitLimits(requireArg(a.str(0), "base64data")))

        // ---- image cache
        "PopulateImageHosts" -> {
            imageCache.populateImageHosts(a.str(0))
            JsonNull
        }
        "GetImage" -> jsonOf(imageCache.getImage(a.str(0), a.str(1), a.str(2)))

        // ---- prints, stickers, emoji
        "SavePrintToFile" -> jsonOf(saveUgc("Prints", a))
        "SaveStickerToFile" -> jsonOf(saveUgc("Stickers", a))
        "SaveEmojiToFile" -> jsonOf(saveUgc("Emoji", a))
        "CropPrintImage" -> jsonOf(cropPrintImage(a.str(0)))
        "CropAllPrints" -> {
            cropAllPrints(a.str(0))
            JsonNull
        }
        "OpenUGCPhotosFolder" -> jsonOf(platform.ugc.open(a.strOr(0, "")))
        "GetUGCPhotoLocation" -> jsonOf(a.str(0)?.takeIf { it.isNotEmpty() } ?: platform.photos.location())

        // ---- screenshots
        "GetVRChatPhotosLocation" -> jsonOf(platform.photos.location())
        "OpenVrcPhotosFolder" -> jsonOf(platform.photos.open())
        "GetScreenshotMetadata" -> getScreenshotMetadata(a.str(0))
        "GetExtraScreenshotData" -> getExtraScreenshotData(a.str(0), a.bool(1) ?: false)
        "FindScreenshotsBySearch" -> jsonOf(findScreenshotsBySearch(a.strOr(0, ""), a.int(1) ?: 0))
        "GetLastScreenshot" -> getLastScreenshot()
        "DeleteScreenshotMetadata" -> jsonOf(deleteScreenshotMetadata(a.str(0)))
        "DeleteAllScreenshotMetadata" -> {
            deleteAllScreenshotMetadata()
            JsonNull
        }
        "GetFileBase64" -> getFileBase64(a.str(0))
        "AddScreenshotMetadata" -> jsonOf("")
        "OpenFolderAndSelectItem" -> {
            openFolderAndSelectItem(a.str(0))
            JsonNull
        }

        // ---- folders and dialogs (PC only)
        "GetVRChatAppDataLocation", "GetVRChatCacheLocation", "GetVRChatScreenshotsLocation",
        "OpenFolderSelectorDialog", "OpenFileSelectorDialog", "ReadConfigFile", "ReadConfigFileSafe",
        "ReadVrcRegJsonFile",
        -> jsonOf("")
        "OpenVrcxAppDataFolder", "OpenVrcAppDataFolder", "OpenVrcScreenshotsFolder", "OpenCrashVrcCrashDumps",
        "CheckForUpdateExe", "SetVRChatRegistryKey", "SetVRChatUserModeration",
        -> jsonOf(false)
        "CheckUpdateProgress", "GetVRChatUserModeration" -> jsonOf(0)
        "DownloadUpdate" -> throw DotNetException("PlatformNotSupportedException", "Updater not supported on Android")

        // ---- VRChat registry (PC only, ARCHITECTURE.md §4.5)
        "GetVRChatRegistryKey", "GetVRChatRegistryKeyString", "GetVRChatRegistry", "GetVRChatModerations" -> JsonNull
        "HasVRChatRegistryFolder" -> jsonOf(true)
        "GetVRChatRegistryJson", "SetVRChatRegistry" ->
            throw DotNetException("PlatformNotSupportedException", "VRChat registry is not available on Android")

        else -> missingMethod(CLASS_NAME, method)
    }

    fun getVersion(): String = VersionInfo.versionString(platform.vrcxVersion)

    fun gameStatePayload(): JsonObject = buildJsonObject {
        put("isGameRunning", platform.gameState.isGameRunning)
        put("isSteamVRRunning", platform.gameState.isSteamVRRunning)
    }

    // ---------------------------------------------------------------- shell

    private fun desktopNotification(title: String, body: String, image: String) {
        val icon = if (image.isEmpty()) null else (docs.resolve(image)?.file?.takeIf { it.isFile }?.absolutePath)
        platform.postNotification(title, body, icon)
    }

    /** Upstream `OpenLink`: only absolute http(s) URLs are opened; anything else is ignored. */
    fun openLink(url: String?) {
        if (url.isNullOrBlank()) return
        val parsed = url.trim().toHttpUrlOrNull() ?: return
        try {
            platform.openExternalUrl(parsed.toString())
        } catch (e: Exception) {
            platform.log("Failed to open a link", e)
        }
    }

    /** Upstream `OpenDiscordProfile`: rejects ids that are not a long; Discord app first, then the web profile. */
    fun openDiscordProfile(discordId: String?) {
        val id = discordId?.trim()?.toLongOrNull() ?: throw DotNetException("Exception", "Invalid user ID")
        if (!platform.viewUri("discord://-/users/$id")) {
            platform.openExternalUrl("https://discord.com/users/$id")
        }
    }

    fun openCalendarFile(ics: String?) {
        if (!Ics.isValid(ics)) throw DotNetException("Exception", "Invalid calendar file")
        try {
            // cacheDir/share/ is one of the few folders the FileProvider hands to other apps
            val file = File(File(platform.cacheDir, SHARE_DIR).apply { mkdirs() }, "event.ics")
            file.writeText(ics!!)
            platform.openCalendar(file, Ics.parseFirstEvent(ics, zone()))
        } catch (e: Exception) {
            platform.log("Failed to open calendar file", e)
        }
    }

    private suspend fun copyImageToClipboard(path: String?) {
        val doc = docs.resolve(path) ?: return
        if (!doc.exists() || IMAGE_EXTENSIONS.none { doc.name.endsWith(it) }) return
        try {
            platform.copyImageToClipboard(doc)
        } catch (e: Exception) {
            platform.log("Failed to copy image to clipboard", e)
        }
    }

    /**
     * `StartGame(arguments)`: the first argument is the `vrchat://launch?...` URL (stores/launch.js). It is opened only
     * when an installed app handles it; otherwise false, which the frontend reports as "failed to find VRChat".
     */
    fun startGame(arguments: String?): Boolean {
        val url = arguments?.trim()?.split(' ')?.firstOrNull().orEmpty()
        if (!url.startsWith("vrchat://launch", ignoreCase = true)) return false
        return try {
            platform.viewUri(url, onlyIfResolvable = true)
        } catch (e: Exception) {
            platform.log("Failed to start VRChat", e)
            false
        }
    }

    // ---------------------------------------------------------------- portable helpers

    private fun colourBulk(a: JsonArray): JsonElement {
        val list = a.arr(0) ?: throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'userIds')")
        val ids = list.map { e ->
            if (e is JsonNull) throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'userId')")
            (e as? JsonPrimitive)?.content ?: throw DotNetException("InvalidCastException", "Unable to cast object to type 'System.String'.")
        }
        return JsonArray(Hashing.colourBulk(ids).map { (id, hue) -> JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(hue))) })
    }

    private suspend fun resizeImageToFitLimits(base64: String): String = withContext(Dispatchers.Default) {
        Hashing.encodeBase64(platform.images.resizeToFitLimits(Hashing.decodeBase64(base64), false))
    }

    // ---------------------------------------------------------------- prints, stickers, emoji

    /** `Save{Print,Sticker,Emoji}ToFile(url, ugcFolderPath, monthFolder, fileName)`: path, or null if it exists or fails. */
    suspend fun saveUgc(type: String, a: JsonArray): String? {
        val url = a.str(0)
        val folder = platform.ugc.folder(a.str(1), type, FileNames.makeValidFileName(a.strOr(2, "")))
        val name = FileNames.makeValidFileName(a.strOr(3, ""))
        if (name.isEmpty() || folder.exists(name)) return null
        return try {
            val bytes = imageCache.fetchBytes(url)
            withContext(Dispatchers.IO) { folder.create(name, bytes) }
        } catch (e: Exception) {
            platform.log("Failed to save $type image to file", e)
            null
        }
    }

    suspend fun cropPrintImage(path: String?): Boolean {
        val doc = docs.resolve(path)
        if (doc == null || !doc.exists()) throw DotNetException("FileNotFoundException", "Could not find file '$path'.")
        return cropPrint(doc)
    }

    private suspend fun cropPrint(doc: Doc): Boolean = withContext(Dispatchers.IO) {
        // Prints that were already cropped are the common case in CropAllPrints: skip them from the PNG header alone.
        if (!mayBePrintSized(doc)) return@withContext false
        val bytes = doc.readBytes()
        val cropped = platform.images.cropPrint(bytes) ?: return@withContext false
        val output = PngHelper.copyITxtChunks(bytes, cropped)
        try {
            doc.writeBytes(output)
            true
        } catch (e: Exception) {
            platform.log("Failed to replace cropped print image", e)
            false
        }
    }

    /** False only when the PNG header says the image is not 2048x1440; anything unreadable is left to the decoder. */
    private fun mayBePrintSized(doc: Doc): Boolean = try {
        doc.openRead().use { stream ->
            val ihdr = PngFile(stream).getChunk(PngChunkType.IHDR) ?: return@use true
            val (w, h) = ihdr.readIHDRChunkResolution()
            w == ImageGeometry.PRINT_WIDTH && h == ImageGeometry.PRINT_HEIGHT
        }
    } catch (e: Exception) {
        true
    }

    private suspend fun cropAllPrints(ugcFolderPath: String?) {
        for (doc in withContext(Dispatchers.IO) { platform.ugc.listPngs(ugcFolderPath, "Prints") }) {
            try {
                cropPrint(doc)
            } catch (e: Exception) {
                platform.log("Failed to crop print ${doc.key}", e)
            }
        }
    }

    // ---------------------------------------------------------------- screenshots

    suspend fun getScreenshotMetadata(path: String?): JsonElement = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext JsonNull
        val doc = docs.resolve(path)
        val metadata = if (doc == null) null else ScreenshotParser.getScreenshotMetadata(doc, path, zone())
        val tree = when {
            metadata == null -> errorTree(path, "Screenshot contains no metadata.")
            metadata.error != null -> errorTree(path, metadata.error!!)
            else -> metadata.toJsonTree()
        }
        JsonPrimitive(tree.toIndentedString())
    }

    private fun errorTree(path: String, error: String) = NJ.Obj().also {
        it.put("sourceFile", path)
        it.put("error", error)
    }

    suspend fun getExtraScreenshotData(path: String?, carouselCache: Boolean): JsonElement = withContext(Dispatchers.IO) {
        val doc = docs.resolve(path) ?: return@withContext JsonNull
        if (!doc.exists() || !doc.name.endsWith(".png")) return@withContext JsonNull
        val o = NJ.Obj()
        if (carouselCache) {
            val files = doc.siblings()
                ?.filter { it.name.endsWith(".png", ignoreCase = true) }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (files != null) {
                val index = files.indexOfFirst { it.key == doc.key }
                if (index > 0) o.put("previousFilePath", docs.displayPath(files[index - 1], materialize = false))
                if (index < files.size - 1) o.put("nextFilePath", docs.displayPath(files[index + 1], materialize = false))
            }
        }
        o.put("fileResolution", PngFile(doc.openRead()).use { PngHelper.readResolution(it) })
        o.put("creationDate", LOCAL_DATE_TIME.format(Instant.ofEpochMilli(ScreenshotTimes.creationTime(doc, zone())).atZone(zone())))
        val size = doc.length()
        o.put("fileSizeBytes", size.toString())
        o.put("fileName", nameWithoutExtension(doc.name))
        val enrichment = synchronized(pendingEnrichment) { path != null && pendingEnrichment.remove(path) }
        o.put("filePath", docs.displayPath(doc, materialize = carouselCache || !enrichment))
        o.put("fileSize", "${NJ.formatMegabytes(size)} MB")
        JsonPrimitive(o.toIndentedString())
    }

    suspend fun findScreenshotsBySearch(query: String, searchType: Int): String = withContext(Dispatchers.IO) {
        val entries = platform.photos.listPngs() ?: return@withContext "[]"
        val results = NJ.Arr()
        val tokens = mutableListOf<String>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val doc = entry.doc
            seen += doc.key
            val record = searchIndex.get(doc) ?: ScreenshotSearchIndex.Record.of(searchIndex.stampOf(doc), parseQuietly(doc)).also {
                searchIndex.put(doc, it)
            }
            if (record.matches(query, searchType)) {
                val token = docs.displayPath(doc, materialize = false)
                tokens += token
                results.items += NJ.Str(token)
            }
        }
        searchIndex.save(seen)
        synchronized(pendingEnrichment) {
            pendingEnrichment.clear()
            pendingEnrichment.addAll(tokens)
        }
        results.toIndentedString()
    }

    private fun parseQuietly(doc: Doc): ScreenshotMetadata? = try {
        ScreenshotParser.getScreenshotMetadata(doc, doc.key, zone())
    } catch (e: Exception) {
        null
    }

    suspend fun getLastScreenshot(): JsonElement = withContext(Dispatchers.IO) {
        val entries = platform.photos.listPngs() ?: return@withContext JsonNull
        val zone = zone()
        // upstream: OrderByDescending(Directory.GetCreationTime).FirstOrDefault(), stable on ties like maxByOrNull
        val newest = entries
            .filter { e -> e.relativeDir.split('/').none { it.lowercase() in UGC_FOLDERS } }
            .maxByOrNull { ScreenshotTimes.creationTime(it.doc, zone) }
            ?: return@withContext JsonNull
        JsonPrimitive(docs.displayPath(newest.doc, materialize = false))
    }

    suspend fun deleteScreenshotMetadata(path: String?): Boolean = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext false
        val doc = docs.resolve(path) ?: return@withContext false
        if (!doc.exists() || !doc.name.endsWith(".png")) return@withContext false
        try {
            deleteTextMetadata(doc)
            true
        } catch (e: Exception) {
            platform.log("Failed to delete screenshot metadata for $path", e)
            false
        }
    }

    private fun deleteTextMetadata(doc: Doc) {
        if (!ScreenshotParser.hasDeletableText(doc, true)) return
        val updated = ScreenshotParser.deleteTextMetadata(doc.readBytes(), true) ?: return
        doc.writeBytes(updated)
    }

    private suspend fun deleteAllScreenshotMetadata() = withContext(Dispatchers.IO) {
        for (entry in platform.photos.listPngs().orEmpty()) {
            try {
                deleteTextMetadata(entry.doc)
            } catch (e: Exception) {
                platform.log("Failed to delete screenshot metadata for ${entry.doc.key}", e)
            }
        }
    }

    suspend fun getFileBase64(path: String?): JsonElement = withContext(Dispatchers.IO) {
        val doc = docs.resolve(path)
        if (doc == null || !doc.exists()) JsonNull else JsonPrimitive(Hashing.encodeBase64(doc.readBytes()))
    }

    /** Opens an Android-local image in a viewer; PC paths (VRChat cache folders) are ignored. */
    private fun openFolderAndSelectItem(path: String?) {
        val doc = docs.resolve(path) ?: return
        if (!doc.exists()) return
        try {
            platform.viewDoc(doc, "image/*")
        } catch (e: Exception) {
            platform.log("Failed to open $path", e)
        }
    }

    companion object {
        const val CLASS_NAME = "AppApiElectron"
        const val SHARE_DIR = "share"
        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp")
        private val UGC_FOLDERS = setOf("prints", "stickers", "emoji")
        private val LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** `Path.GetFileNameWithoutExtension`. */
        fun nameWithoutExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot >= 0) name.substring(0, dot) else name
        }
    }
}
