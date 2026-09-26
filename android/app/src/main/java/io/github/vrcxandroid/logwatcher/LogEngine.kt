package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.SafeLog
import java.io.RandomAccessFile

/**
 * What one `Update()` pass sees of one log file: the PC's metadata (`Name`, `CreationTimeUtc`, `LastWriteTimeUtc`)
 * and the bytes mirrored so far.
 */
internal class LogFileView(
    val name: String,
    val creationTicks: Long,
    val lastWriteTicks: Long,
    /** Bytes available in the mirror; plays the role of the file's real end of file. */
    val size: Long,
    /**
     * The file will not grow any more, so an unterminated last line is parsed as a line, the way
     * upstream's `ReadLine()` returns it at EOF.
     */
    val final: Boolean,
    val open: () -> RandomAccessFile,
)

/** Per-file `LogContext` (`LogWatcher.cs:1430-1440`), plus the port's held-tail marker. */
internal class LogContext {
    var audioDeviceChanged = false
    var lastAudioDevice: String? = null
    val videoPlaybackErrors = HashSet<String>(50)
    var length = 0L
    var locationDestination: String? = null
    var position = 0L
    var recentWorldName: String? = null
    var shaderKeywordsLimitReached = false

    /** The last clean pass stopped before an unterminated tail that it did not parse. */
    var pendingTail = false
}

internal fun interface LwLog {
    fun log(level: Int, message: String, error: Throwable?)

    companion object {
        const val INFO = 4
        const val WARN = 5
    }
}

/**
 * Kotlin port of the parsing half of upstream `Dotnet/LogWatcher.cs`:
 * `Update()`, `ParseLog()`, every parser and helper, the per-file contexts, the startup list and the pull queue.
 * It is not thread-safe; [LogWatcher] confines it to its own thread. It reads files through [LogFileView]s built from
 * the companion mirror instead of the file system.
 *
 * Differences from upstream:
 * - change detection uses the mirrored size (the real end of file) instead of the PC's directory-entry length;
 * - a pass consumes complete lines only and keeps an unterminated tail for the next pass unless the file is final;
 * - `m_FirstRun` is cleared at the end of a pass only when [update] is told so (the companion's initial sync or the
 *   first `Get()` has happened);
 * - once the frontend has drained the startup list, the list keeps only the newest [listCap] records,
 *   and the pull queue keeps at most [queueCap] lines.
 */
internal class LogEngine(
    private val pcNowMs: () -> Long,
    private val log: LwLog,
    private val onQueued: () -> Unit,
    private val listCap: Int = 10_000,
    private val queueCap: Int = 100_000,
) {
    var zone: PcZone = PcZone(java.time.ZoneOffset.UTC)

    /** `tillDate` as UTC ticks; `DateTime.MinValue` until `SetDateTill`. */
    var tillDateTicks = 0L
    var firstRun = true
        private set
    var resetLog = false

    @Volatile
    var vrcClosedGracefully = false
        private set

    /** Enabled after the frontend's startup `Get()` loop has emptied the list. */
    var listCapActive = false

    private val contexts = HashMap<String, LogContext>()
    private val logList = ArrayDeque<Array<String?>>()
    private val logQueue = ArrayDeque<String>()

    val queuedCount: Int get() = logQueue.size
    val listCount: Int get() = logList.size

    fun hasContext(name: String): Boolean = contexts.containsKey(name)

    /** Drops a file's context, as `Update()` does when a pass does not see the file. */
    fun forget(name: String) {
        contexts.remove(name)
    }

    fun forgetAll() {
        contexts.clear()
    }

    /** `Update()` (`LogWatcher.cs:103-164`). */
    fun update(files: List<LogFileView>, endFirstRun: Boolean) {
        if (resetLog) {
            firstRun = true
            resetLog = false
            contexts.clear()
            logList.clear()
        }

        val deletedNameSet = HashSet(contexts.keys)
        // Array.Sort by CreationTimeUtc; equal creation times are ordered by name (upstream: unspecified).
        val sorted = files.sortedWith(compareBy<LogFileView>({ it.creationTicks }, { it.name }))
        for (file in sorted) {
            if (file.lastWriteTicks < tillDateTicks) continue

            var context = contexts[file.name]
            if (context != null) {
                deletedNameSet.remove(file.name)
            } else {
                context = LogContext()
                contexts[file.name] = context
            }

            if (context.length == file.size && !(file.final && context.pendingTail)) continue

            context.length = file.size
            parseLog(file, context)
        }

        for (name in deletedNameSet) contexts.remove(name)

        if (endFirstRun) firstRun = false
    }

    /** `Get()` after its `Update()` (`LogWatcher.cs:1378-1406`): up to 1000 rows, oldest first. */
    fun takeBatch(): List<Array<String?>> {
        if (resetLog || logList.isEmpty()) return emptyList()
        val n = minOf(1000, logList.size)
        val out = ArrayList<Array<String?>>(n)
        repeat(n) { out.add(logList.removeFirst()) }
        return out
    }

    /** `GetLogLines()`: drains the pull queue. */
    fun takeLogLines(): List<String> {
        val out = ArrayList<String>(logQueue)
        logQueue.clear()
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ParseLog (LogWatcher.cs:171-283)

    private fun parseLog(file: LogFileView, context: LogContext) {
        var current = ""
        try {
            val start = context.position
            var newPosition = start
            var tail = false
            if (start < file.size) {
                file.open().use { raf ->
                    val reader = MirrorLineReader(raf, start, file.size)
                    while (true) {
                        val line = reader.nextLine(file.final) ?: break
                        current = line
                        processLine(file, context, line)
                    }
                    newPosition = reader.resumePosition
                    tail = reader.heldTail
                }
            }
            context.position = newPosition
            context.pendingTail = tail
        } catch (e: Exception) {
            // Upstream aborts the pass and keeps Position (it is only written at EOF); Length is already updated.
            context.pendingTail = false
            log.log(LwLog.WARN, "Failed to parse log file: ${file.name} $current ${SafeLog.kind(e)}", SafeLog.redacted(e))
        }
    }

    private fun processLine(file: LogFileView, context: LogContext, line: String) {
        if (line.isEmpty()) return

        if (parseLogUdonException(file, line)) return

        if (line.length <= 36 || line[31] != '-') return

        val lineTicks = zone.lineStampToUtcTicks(line)
        if (lineTicks == null) {
            log.log(LwLog.WARN, "Failed to parse log date in ${file.name} (${line.length} chars)", null)
            return
        }
        if (lineTicks <= tillDateTicks) return
        if (Ticks.fromEpochMs(pcNowMs()) + 61 * 60 * Ticks.PER_SECOND < lineTicks) {
            log.log(LwLog.WARN, "Invalid log time, too new, in ${file.name}", null)
            return
        }

        val offset = 34
        if (line[offset] == '[') {
            @Suppress("ControlFlowWithEmptyBody")
            if (parseLogOnPlayerJoinedOrLeft(file, line) ||
                parseLogLocation(file, context, line) ||
                parseLogLocationDestination(file, context, line) ||
                parseLogPortalSpawn(file, line) ||
                parseLogNotification(file, line, offset) ||
                parseLogApiRequest(file, line, offset) ||
                parseLogAvatarChange(file, line, offset) ||
                parseLogJoinBlocked(file, line) ||
                parseLogAvatarPedestalChange(file, line, offset) ||
                parseLogVideoError(file, context, line, offset) ||
                parseLogVideoChange(file, line, offset) ||
                parseLogAvProVideoChange(file, line, offset) ||
                parseLogUsharpVideoPlay(file, line, offset) ||
                parseLogUsharpVideoSync(file, line, offset) ||
                parseLogWorldVrcx(file, line, offset) ||
                parseLogWorldDataVrcx(line, offset) ||
                parseLogOnAudioConfigurationChanged(file, context, line) ||
                parseLogScreenshot(file, line) ||
                parseLogStringDownload(file, line) ||
                parseLogImageDownload(file, line) ||
                parseVoteKick(file, line, offset) ||
                parseFailedToJoin(file, line, offset) ||
                parseModerationEvent(file, line, "[ModerationManager] This instance will be reset in ") ||
                parseModerationEvent(file, line, "[ModerationManager] A vote kick has been initiated against ") ||
                parseModerationEvent(file, line, "[ModerationManager] Vote to kick ") ||
                parseStickerSpawn(file, line)
            ) {
            }
        } else {
            @Suppress("ControlFlowWithEmptyBody")
            if (parseLogShaderKeywordsLimit(file, context, line) ||
                parseLogSdk2VideoPlay(file, line, offset) ||
                parseApplicationQuit(file, line, offset) ||
                parseOpenVrInit(file, line, offset) ||
                parseDesktopMode(file, line, offset) ||
                parseOscFailedToStart(file, line, offset) ||
                parseUntrustedUrl(file, context, line, offset)
            ) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AppendLog / time

    private fun appendLog(item: Array<String?>) {
        if (!firstRun) {
            if (logQueue.size >= queueCap) {
                logQueue.removeFirst()
                log.log(LwLog.WARN, "GetLogLines queue full; dropping the oldest line", null)
            }
            logQueue.addLast(DotNetJson.serialize(item))
            onQueued()
        }
        logList.addLast(item)
        if (listCapActive) while (logList.size > listCap) logList.removeFirst()
    }

    private fun append(file: LogFileView, line: String, type: String, vararg args: String?) {
        val item = arrayOfNulls<String>(3 + args.size)
        item[0] = file.name
        item[1] = convertLogTimeToIso8601(line)
        item[2] = type
        for (i in args.indices) item[3 + i] = args[i]
        appendLog(item)
    }

    /** `ConvertLogTimeToISO8601` (`LogWatcher.cs:319-340`); PC-equivalent UtcNow when the stamp does not parse. */
    private fun convertLogTimeToIso8601(line: String): String {
        val ticks = zone.lineStampToUtcTicks(line) ?: Ticks.fromEpochMs(pcNowMs())
        return Ticks.formatIso(ticks)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Parsers (LogWatcher.cs:342-1372), in source order of the dispatch chains

    private fun parseLogLocation(file: LogFileView, context: LogContext, line: String): Boolean {
        if (line.contains("[Behaviour] Entering Room: ")) {
            var lineOffset = line.lastIndexOf("] Entering Room: ")
            if (lineOffset < 0) return true
            lineOffset += 17
            if (lineOffset > line.length) return true
            context.recentWorldName = sub(line, lineOffset)
            return true
        }

        if (line.contains("[Behaviour] Joining ") && !line.contains("] Joining or Creating Room: ") &&
            !line.contains("] Joining friend: ")
        ) {
            var lineOffset = line.lastIndexOf("] Joining ")
            if (lineOffset < 0) return true
            lineOffset += 10
            if (lineOffset >= line.length) return true

            val location = DotNetStrings.cleanLocation(sub(line, lineOffset))
            append(file, line, "location", location, context.recentWorldName)

            context.lastAudioDevice = ""
            context.videoPlaybackErrors.clear()
            vrcClosedGracefully = false
            return true
        }

        return false
    }

    private fun parseLogScreenshot(file: LogFileView, line: String): Boolean {
        if (!line.contains("[VRC Camera] Took screenshot to: ")) return false
        val lineOffset = line.lastIndexOf("] Took screenshot to: ")
        if (lineOffset < 0) return true
        append(file, line, "screenshot", sub(line, lineOffset + 22))
        return true
    }

    private fun parseLogLocationDestination(file: LogFileView, context: LogContext, line: String): Boolean {
        if (line.contains("[Behaviour] OnLeftRoom")) {
            append(file, line, "location-destination", context.locationDestination)
            context.locationDestination = ""
            return true
        }

        if (line.contains("[Behaviour] Destination fetching: ")) {
            var lineOffset = line.lastIndexOf("] Destination fetching: ")
            if (lineOffset < 0) return true
            lineOffset += 24
            if (lineOffset >= line.length) return true
            context.locationDestination = DotNetStrings.cleanLocation(sub(line, lineOffset))
            return true
        }

        return false
    }

    private fun parseLogOnPlayerJoinedOrLeft(file: LogFileView, line: String): Boolean {
        if (line.contains("[Behaviour] OnPlayerJoined") && !line.contains("] OnPlayerJoined:")) {
            var lineOffset = line.lastIndexOf("] OnPlayerJoined")
            if (lineOffset < 0) return true
            lineOffset += 17
            if (lineOffset > line.length) return true

            val (displayName, userId) = parseUserInfo(sub(line, lineOffset))
            if (displayName.isEmpty() && userId.isEmpty()) {
                log.log(LwLog.WARN, "Failed to parse user info from a log line in ${file.name}", null)
                return true
            }
            append(file, line, "player-joined", displayName, userId)
            return true
        }

        if (line.contains("[Behaviour] OnPlayerLeft") && !line.contains("] OnPlayerLeftRoom") &&
            !line.contains("] OnPlayerLeft:")
        ) {
            var lineOffset = line.lastIndexOf("] OnPlayerLeft")
            if (lineOffset < 0) return true
            lineOffset += 15
            if (lineOffset > line.length) return true

            val (displayName, userId) = parseUserInfo(sub(line, lineOffset))
            if (displayName.isEmpty() && userId.isEmpty()) {
                log.log(LwLog.WARN, "Failed to parse user info from a log line in ${file.name}", null)
                return true
            }
            append(file, line, "player-left", displayName, userId)
            return true
        }

        return false
    }

    private fun parseLogPortalSpawn(file: LogFileView, line: String): Boolean {
        if (line.contains("[Behaviour] Instantiated a (Clone [") && line.contains("] Portals/PortalInternalDynamic)")) {
            append(file, line, "portal-spawn")
            return true
        }
        return false
    }

    private fun parseLogShaderKeywordsLimit(file: LogFileView, context: LogContext, line: String): Boolean {
        if (line.contains("Maximum number (384) of shader global keywords exceeded")) {
            if (context.shaderKeywordsLimitReached) return true
            append(file, line, "event", "Shader Keyword Limit has been reached")
            context.shaderKeywordsLimitReached = true
            return true
        }
        return false
    }

    private fun parseLogJoinBlocked(file: LogFileView, line: String): Boolean {
        if (!line.contains("] Master is not sending any events! Moving to a new instance.")) return false
        append(file, line, "event", "Joining instance blocked by master")
        return true
    }

    private fun parseLogAvatarPedestalChange(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Network Processing] RPC invoked SwitchAvatar on AvatarPedestal for ", 68)) return false
        val data = sub(line, offset + 68)
        append(file, line, "event", "$data changed avatar pedestal")
        return true
    }

    private fun parseLogVideoError(file: LogFileView, context: LogContext, line: String, offset: Int): Boolean {
        if (line.contains("[Video Playback] ERROR: ")) {
            var data = sub(line, offset + 24)
            if (!context.videoPlaybackErrors.add(data)) return true
            if (data.contains(YOUTUBE_BOT_ERROR)) data = "$YOUTUBE_BOT_ERROR_FIX_URL\n$data"
            append(file, line, "event", "VideoError: $data")
            return true
        }

        if (line.contains("[AVProVideo] Error: ")) {
            var data = sub(line, offset + 20)
            if (!context.videoPlaybackErrors.add(data)) return true
            if (data.contains(YOUTUBE_BOT_ERROR)) data = "$YOUTUBE_BOT_ERROR_FIX_URL\n$data"
            append(file, line, "event", "VideoError: $data")
            return true
        }

        return false
    }

    private fun parseUntrustedUrl(file: LogFileView, context: LogContext, line: String, offset: Int): Boolean {
        if (line.contains("Attempted to play an untrusted URL")) {
            val data = sub(line, offset)
            if (!context.videoPlaybackErrors.add(data)) return true
            append(file, line, "event", "VideoError: $data")
            return true
        }
        return false
    }

    private fun parseLogWorldVrcx(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[VRCX] ", 7)) return false
        append(file, line, "vrcx", sub(line, offset + 7))
        return true
    }

    private fun parseLogWorldDataVrcx(line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[VRCX-World] ", 13)) return false
        // PWI, deprecated upstream: logged only.
        log.log(LwLog.INFO, "VRCX-World data (${line.length - offset - 13} chars, not logged)", null)
        return true
    }

    private fun parseLogVideoChange(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Video Playback] Attempting to resolve URL '", 44)) return false
        if (line.lastIndexOf('\'') < 0) return false
        val data = removeLast(sub(line, offset + 44))
        append(file, line, "video-play", data)
        return true
    }

    private fun parseLogAvProVideoChange(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Video Playback] Resolving URL '", 32)) return false
        if (line.lastIndexOf('\'') < 0) return false
        val data = removeLast(sub(line, offset + 32))
        append(file, line, "video-play", data)
        return true
    }

    private fun parseLogSdk2VideoPlay(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "User ", 5)) return false
        val pos = line.lastIndexOf(" added URL ")
        if (pos < 0) return false
        val displayName = sub(line, offset + 5, pos - (offset + 5))
        val data = sub(line, pos + 11)
        append(file, line, "video-play", data, displayName)
        return true
    }

    private fun parseLogUsharpVideoPlay(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[USharpVideo] Started video load for URL: ", 42)) return false
        val pos = line.lastIndexOf(", requested by ")
        if (pos < 0) return false
        val data = sub(line, offset + 42, pos - (offset + 42))
        val displayName = sub(line, pos + 15)
        append(file, line, "video-play", data, displayName)
        return true
    }

    private fun parseLogUsharpVideoSync(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[USharpVideo] Syncing video to ", 31)) return false
        append(file, line, "video-sync", sub(line, offset + 31))
        return true
    }

    private fun parseLogNotification(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[API] Received Notification: <", 30)) return false
        val pos = line.lastIndexOf("> received at ")
        if (pos < 0) return false
        append(file, line, "notification", sub(line, offset + 30, pos - (offset + 30)))
        return true
    }

    private fun parseLogApiRequest(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[API] [", 7)) return false
        val pos = line.lastIndexOf("] Sending Get request to ")
        if (pos < 0) return false
        append(file, line, "api-request", sub(line, pos + 25))
        return true
    }

    private fun parseLogAvatarChange(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Behaviour] Switching ", 22)) return false
        val pos = line.lastIndexOf(" to avatar ")
        if (pos < 0) return false
        val displayName = sub(line, offset + 22, pos - (offset + 22))
        val avatarName = sub(line, pos + 11)
        append(file, line, "avatar-change", displayName, avatarName)
        return true
    }

    private fun parseLogOnAudioConfigurationChanged(file: LogFileView, context: LogContext, line: String): Boolean {
        if (line.contains("[Always] uSpeak: OnAudioConfigurationChanged")) {
            context.audioDeviceChanged = true
            return true
        }

        if (line.contains("[Always] uSpeak: SetInputDevice 0")) {
            var lineOffset = line.lastIndexOf(") '")
            if (lineOffset < 0) return true
            lineOffset += 3
            val endPos = line.length - 1
            val length = minOf(endPos - lineOffset + 1, line.length - lineOffset)
            if (length <= 0) return true

            val audioDevice = sub(line, lineOffset, length)
            if (context.lastAudioDevice.isNullOrEmpty()) {
                context.audioDeviceChanged = false
                context.lastAudioDevice = audioDevice
                return true
            }

            if (!context.audioDeviceChanged || context.lastAudioDevice == audioDevice) return true

            append(file, line, "event", "Audio device changed, mic set to '$audioDevice'")
            context.lastAudioDevice = audioDevice
            context.audioDeviceChanged = false
            return true
        }

        return false
    }

    /** Pre-filter P0 (`LogWatcher.cs:1084-1116`), run before the length, date and tillDate checks. */
    private fun parseLogUdonException(file: LogFileView, line: String): Boolean {
        if (line.contains("[PyPyDance]")) {
            append(file, line, "udon-exception", line)
            return true
        }
        val lineOffset = line.indexOf(" ---> VRC.Udon.VM.UdonVMException: ")
        if (lineOffset < 0) return false
        append(file, line, "udon-exception", sub(line, lineOffset))
        return true
    }

    private fun parseApplicationQuit(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "VRCApplication: OnApplicationQuit at ", 37) &&
            !compareAt(line, offset, "VRCApplication: HandleApplicationQuit at ", 41)
        ) return false
        append(file, line, "vrc-quit")
        vrcClosedGracefully = true
        return true
    }

    private fun parseOpenVrInit(file: LogFileView, line: String, offset: Int): Boolean {
        // The second literal is 19 characters but compared with length 20 (upstream quirk).
        if (!compareAt(line, offset, "Initializing VRSDK.", 19) && !compareAt(line, offset, "STEAMVR HMD Model: ", 20)) {
            return false
        }
        append(file, line, "openvr-init")
        return true
    }

    private fun parseDesktopMode(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "VR Disabled", 11)) return false
        append(file, line, "desktop-mode")
        return true
    }

    private fun parseLogStringDownload(file: LogFileView, line: String): Boolean {
        val check = "] Attempting to load String from URL '"
        if (!line.contains(check)) return false
        val lineOffset = line.lastIndexOf(check)
        if (lineOffset < 0) return true
        val stringData = removeLast(sub(line, lineOffset + check.length))
        if (isOwnRequest(stringData)) return true
        append(file, line, "resource-load-string", stringData)
        return true
    }

    private fun parseLogImageDownload(file: LogFileView, line: String): Boolean {
        val check = "] Attempting to load image from URL '"
        if (!line.contains(check)) return false
        val lineOffset = line.lastIndexOf(check)
        if (lineOffset < 0) return true
        val imageData = removeLast(sub(line, lineOffset + check.length))
        if (isOwnRequest(imageData)) return true
        append(file, line, "resource-load-image", imageData)
        return true
    }

    private fun isOwnRequest(url: String): Boolean =
        DotNetStrings.cultureStartsWith(url, "http://127.0.0.1:22500") ||
            DotNetStrings.cultureStartsWith(url, "http://localhost:22500")

    private fun parseVoteKick(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Behaviour] Received executive message: ", 40)) return false
        append(file, line, "event", sub(line, offset + 40))
        return true
    }

    private fun parseFailedToJoin(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "[Behaviour] Failed to join instance ", 36)) return false
        append(file, line, "event", sub(line, offset + 12))
        return true
    }

    private fun parseOscFailedToStart(file: LogFileView, line: String, offset: Int): Boolean {
        if (!compareAt(line, offset, "Could not Start OSC: ", 21)) return false
        append(file, line, "event", "VRChat couldn't start OSC server, \"${sub(line, offset)}\"")
        return true
    }

    /** ParseInstanceResetWarning, ParseVoteKickInitiation and ParseVoteKickSuccess (`LogWatcher.cs:1284-1339`). */
    private fun parseModerationEvent(file: LogFileView, line: String, marker: String): Boolean {
        if (!line.contains(marker)) return false
        val index = line.indexOf(marker) + 20
        append(file, line, "event", sub(line, index))
        return true
    }

    private fun parseStickerSpawn(file: LogFileView, line: String): Boolean {
        val index = line.indexOf("[StickersManager] User ")
        if (index == -1 || !line.contains("inv_") || !line.contains("spawned sticker")) return false

        val info = sub(line, index + 23)
        // Flipped on purpose, as upstream: the part before " (" is the user id, the part inside is the name.
        val (userId, displayName) = parseUserInfo(info)
        if (displayName.isEmpty() && userId.isEmpty()) {
            log.log(LwLog.WARN, "Failed to parse user info from a log line in ${file.name}", null)
            return true
        }

        val inventoryId = DotNetStrings.cleanId(sub(info, info.indexOf("inv_")))
        append(file, line, "sticker-spawn", userId, displayName, inventoryId)
        return true
    }

    companion object {
        private const val YOUTUBE_BOT_ERROR = "Sign in to confirm"
        private const val YOUTUBE_BOT_ERROR_FIX_URL =
            "[VRCX] Fix error with this: https://github.com/EllyVR/VRCVideoCacher"

        /** `ParseUserInfo` (`LogWatcher.cs:1409-1428`). */
        fun parseUserInfo(userInfo: String): Pair<String, String> {
            val pos = userInfo.lastIndexOf(" (")
            return if (pos >= 0) {
                val displayName = userInfo.substring(0, pos)
                val userId = DotNetStrings.cleanId(sub(userInfo, pos + 2, userInfo.lastIndexOf(')') - (pos + 2)))
                displayName to userId
            } else {
                userInfo to ""
            }
        }

        /** `string.Compare(line, offset, x, 0, n, StringComparison.Ordinal) == 0`. */
        fun compareAt(line: String, offset: Int, x: String, n: Int): Boolean {
            if (offset > line.length) throw IndexOutOfBoundsException("indexA")
            val lengthA = minOf(n, line.length - offset)
            val lengthB = minOf(n, x.length)
            return lengthA == lengthB && line.regionMatches(offset, x, 0, lengthA)
        }

        /** C# `Substring(start)`. */
        fun sub(s: String, start: Int): String {
            if (start < 0 || start > s.length) throw IndexOutOfBoundsException("startIndex $start, length ${s.length}")
            return s.substring(start)
        }

        /** C# `Substring(start, length)`. */
        fun sub(s: String, start: Int, length: Int): String {
            if (start < 0 || length < 0 || start > s.length - length) {
                throw IndexOutOfBoundsException("startIndex $start, length $length, string length ${s.length}")
            }
            return s.substring(start, start + length)
        }

        /** C# `s.Remove(s.Length - 1)`, which throws on an empty string. */
        fun removeLast(s: String): String {
            if (s.isEmpty()) throw IndexOutOfBoundsException("startIndex -1")
            return s.substring(0, s.length - 1)
        }
    }
}

/**
 * Splits mirrored bytes `[start, end)` into lines the way `StreamReader.ReadLine()` does (`\r`, `\n` and `\r\n`
 * end a line; the terminator is not part of it), decoding each line with [DotNetUtf8]. A UTF-8 BOM at `start` is
 * skipped (a new `StreamReader` detects it at its first read). Bytes after the last terminator are returned as a
 * line only when [nextLine] is called with `final = true`; otherwise they are held back and [resumePosition] points
 * at their first byte.
 */
internal class MirrorLineReader(private val raf: RandomAccessFile, private val start: Long, private val end: Long) {
    // Live passes read a few hundred bytes; only the initial sync needs the full 64 KiB.
    private val buffer = ByteArray((end - start).coerceIn(16, 65536).toInt())
    private var bufferStart = start
    private var bufferLength = 0
    private var bufferIndex = 0
    private var line = ByteArray(256)
    private var lineLength = 0
    private var pendingCr = false
    private var started = false

    /** Where the next pass starts: after the last consumed terminator (or `end` after a final flush). */
    var resumePosition = start
        private set

    /** The pass stopped in front of an unterminated tail. */
    var heldTail = false
        private set

    fun nextLine(final: Boolean): String? {
        if (!started) {
            started = true
            raf.seek(start)
            if (end - start >= 3) {
                fill()
                if (bufferLength >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() &&
                    buffer[2] == 0xBF.toByte()
                ) {
                    bufferIndex = 3
                }
            }
        }
        while (true) {
            if (bufferIndex >= bufferLength) {
                if (bufferStart + bufferLength >= end) break
                fill()
                if (bufferLength == 0) break
            }
            val b = buffer[bufferIndex]
            if (pendingCr) {
                pendingCr = false
                if (b == LF) {
                    bufferIndex++
                    resumePosition = bufferStart + bufferIndex
                    continue
                }
            }
            if (b == LF || b == CR) {
                bufferIndex++
                if (b == CR) pendingCr = true
                resumePosition = bufferStart + bufferIndex
                val text = DotNetUtf8.decode(line, 0, lineLength)
                lineLength = 0
                return text
            }
            if (lineLength == line.size) line = line.copyOf(line.size * 2)
            line[lineLength++] = b
            bufferIndex++
        }
        // End of the available bytes.
        if (lineLength > 0) {
            if (final) {
                val text = DotNetUtf8.decode(line, 0, lineLength)
                lineLength = 0
                resumePosition = end
                return text
            }
            // Held back; resumePosition stays at the tail's first byte (or at `start`, so a BOM is detected again).
            heldTail = true
        } else if (final) {
            resumePosition = end
        }
        return null
    }

    private fun fill() {
        bufferStart += bufferLength
        bufferIndex = 0
        val want = minOf(buffer.size.toLong(), end - bufferStart).toInt()
        var read = 0
        while (read < want) {
            val n = raf.read(buffer, read, want - read)
            if (n < 0) break
            read += n
        }
        bufferLength = read
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
    }
}
