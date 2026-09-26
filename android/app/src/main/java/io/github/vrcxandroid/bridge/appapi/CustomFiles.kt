package io.github.vrcxandroid.bridge.appapi

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The user's `custom.css` / `custom.js` (ARCHITECTURE.md §5.1, §9). Both live in `filesDir/custom/`, which no other app
 * and no USB connection can write: `custom.js` runs inside the page with full bridge access (SQLite with the saved
 * logins, the WebApi cookies, every AppApi file method), and injected CSS can leak page content. The user puts them
 * there with `AndroidHost.ImportCustomFile` (a document picker), and removes them with `RemoveCustomFile`.
 *
 * Earlier versions read `custom.css` from `getExternalFilesDir(null)` (and `custom.js` from there on Android 11+, from
 * the internal files directory before). [migrate] copies the CSS once, and the script only from the internal location,
 * which no other app could write; a `custom.js` in the external folder is never picked up.
 *
 * Pure JVM code.
 */
object CustomFiles {
    const val CSS = "custom.css"
    const val SCRIPT = "custom.js"
    const val DIR = "custom"

    /** Largest file [install] accepts. */
    const val MAX_BYTES = 4L * 1024 * 1024

    private const val MIGRATED_MARKER = ".migrated"
    private val BOM: String = Char(0xFEFF).toString()

    /** `filesDir/custom`. */
    fun dir(filesDir: File): File = File(filesDir, DIR)

    /** File name for `ImportCustomFile`/`RemoveCustomFile`'s kind (`'css'` or `'js'`), or null for anything else. */
    fun nameFor(kind: String?): String? = when (kind?.trim()?.lowercase()) {
        "css" -> CSS
        "js" -> SCRIPT
        else -> null
    }

    /** Content of [name] in [dir] without a leading BOM, or "" when there is none. */
    fun read(dir: File?, name: String): String {
        val file = File(dir ?: return "", name)
        if (!file.isFile) return ""
        return try {
            file.readText(Charsets.UTF_8).removePrefix(BOM)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * One-time move of the files earlier versions read: `custom.css` from [externalFilesDir], and `custom.js` from
     * [legacyScriptDir] (the internal files directory, where Android 8-10 read it; pass null on Android 11+, whose
     * script folder other apps and USB could write). Existing files in [dir] win. Returns true when it ran.
     */
    fun migrate(dir: File, externalFilesDir: File?, legacyScriptDir: File?): Boolean {
        val marker = File(dir, MIGRATED_MARKER)
        if (marker.exists()) return false
        dir.mkdirs()
        copyIfAbsent(externalFilesDir?.let { File(it, CSS) }, File(dir, CSS))
        copyIfAbsent(legacyScriptDir?.let { File(it, SCRIPT) }, File(dir, SCRIPT))
        try {
            marker.writeText("1")
        } catch (e: IOException) {
            // tried again next start; existing files are never overwritten
        }
        return true
    }

    private fun copyIfAbsent(source: File?, target: File) {
        if (source == null || !source.isFile || target.exists() || source.length() > MAX_BYTES) return
        try {
            source.inputStream().use { install(target.parentFile!!, target.name, it) }
        } catch (e: IOException) {
            // an unreadable old file is left where it is
        }
    }

    /**
     * Writes [input] to [dir]/[name] through a temporary file, so a failed copy never leaves half a file. Throws
     * [IOException] when it is larger than [MAX_BYTES] or is not text (a NUL byte).
     */
    fun install(dir: File, name: String, input: InputStream) {
        dir.mkdirs()
        val temp = File(dir, ".$name.part")
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("The file is larger than ${MAX_BYTES / (1024 * 1024)} MB.")
                    for (i in 0 until n) {
                        if (buffer[i] == 0.toByte()) throw IOException("The file is not a text file.")
                    }
                    out.write(buffer, 0, n)
                }
            }
            val target = File(dir, name)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not replace '$name'.")
            }
        } finally {
            temp.delete()
        }
    }

    /** Deletes [dir]/[name]; true when no such file remains. */
    fun remove(dir: File, name: String): Boolean {
        val file = File(dir, name)
        return !file.exists() || file.delete()
    }
}
