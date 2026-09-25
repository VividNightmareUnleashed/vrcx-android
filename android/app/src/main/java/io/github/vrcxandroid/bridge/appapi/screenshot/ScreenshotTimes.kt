package io.github.vrcxandroid.bridge.appapi.screenshot

import io.github.vrcxandroid.bridge.appapi.docs.Doc
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The creation time upstream sorts (`GetLastScreenshot`) and labels (`creationDate`) screenshots by.
 *
 * Upstream reads `File.GetCreationTime`, which its in-place metadata edits leave alone. Android storage has no
 * creation time that survives a rewrite: SAF documents and local files only expose the modification time, which
 * deleting metadata or cropping a print moves to "now". VRChat writes the capture time into its file names, so that
 * comes first; other files use the storage's own creation time ([Doc.creationTime], MediaStore `DATE_ADDED`).
 */
object ScreenshotTimes {
    /**
     * `VRChat_2023-02-08_12-31-35.104_1920x1080.png` (current builds) and `VRChat_1920x1080_2021-06-01_20-15-30.123.png`
     * (older builds). The time is the local time of the machine that took the shot.
     */
    private val TIMESTAMP = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})(?:\.(\d{1,3}))?(?!\d)""")

    /** Capture time from a VRChat screenshot file name in epoch milliseconds, or null for any other name. */
    fun captureTime(name: String, zone: ZoneId): Long? {
        if (!name.startsWith("VRChat_")) return null
        val m = TIMESTAMP.find(name) ?: return null
        val g = m.groupValues
        return try {
            val millis = g[7].padEnd(3, '0').toInt()
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), millis * 1_000_000)
                .atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }

    /** Creation time of [doc] in epoch milliseconds (see the class comment). */
    fun creationTime(doc: Doc, zone: ZoneId): Long = captureTime(doc.name, zone) ?: doc.creationTime()
}
