package io.github.vrcxandroid.bridge.appapi.docs

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What [DocResolver] needs from the host (ARCHITECTURE.md §6.6). */
interface DocPlatform {
    /**
     * Maps a `/local/` URL or an absolute path inside the served roots to a file, or null (also for the WebView's own
     * cache folders).
     */
    fun fileFor(pathOrUrl: String): File?

    /** The `/local/` URL of a file inside the served roots ("" when it is not served). */
    fun localUrlFor(file: File): String

    /** Directories served under `/local/` (the local root and the cache directory). */
    fun servedRoots(): List<File>

    /**
     * A SAF document or MediaStore item, or null when the URI is not accessible or the app holds no grant for it (see
     * `ContentGrants`).
     */
    fun contentDoc(uri: String): Doc?
}

/**
 * Turns the path strings the frontend passes around into [Doc]s and back.
 *
 * The page can only display files served under `/local/`, and it reuses the displayed `filePath` as the argument of
 * every later call. Files already under a served root are shown through their own `/local/` URL. Anything else (SAF
 * documents, MediaStore items, the photos folder) is shown through a mirror copy `mirror/<hash>.png` in the cache
 * directory; the mirror URL maps back to the original, so edits (deleting metadata) apply to the original document.
 * Mirror copies are made only when a file is displayed and are trimmed to [maxFiles] / [maxBytes].
 *
 * The page's path strings are resolved only inside the served roots or to content URIs the platform accepts: an
 * absolute path elsewhere (the database, shared_prefs, /proc) resolves to nothing. Docs the app produced itself (photos
 * folder entries shown through a mirror) map back through the mirror's source record, which lives in the cache.
 */
class DocResolver(
    private val platform: DocPlatform,
    private val mirrorDir: File,
    private val maxFiles: Int = 24,
    private val maxBytes: Long = 256L * 1024 * 1024,
) {
    private val mirrorSources = ConcurrentHashMap<String, String>()

    /**
     * Resolves a path string (`/local/` URL, absolute path inside the served roots, mirror URL or `content://` URI);
     * null when unusable or outside what the page may reach. Windows paths from PC logs (`C:\...`) resolve to nothing.
     */
    fun resolve(path: String?): Doc? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("content://")) return platform.contentDoc(path)
        val file = platform.fileFor(path) ?: return null
        mirrorSourceOf(file)?.let { source -> sourceDoc(source)?.let { return it } }
        return LocalFileDoc(file)
    }

    /** The path the page should display for [doc]; with [materialize] the mirror copy is created or refreshed. */
    fun displayPath(doc: Doc, materialize: Boolean): String {
        doc.file?.let { f ->
            if (isServed(f)) {
                val url = platform.localUrlFor(f)
                if (url.isNotEmpty()) return url
            }
        }
        val key = mirrorKey(doc.key)
        mirrorSources[key] = doc.key
        val mirror = File(mirrorDir, "$key.png")
        if (materialize) materialize(doc, key, mirror)
        return platform.localUrlFor(mirror).ifEmpty { mirror.absolutePath }
    }

    /** True when [displayPath] of [doc] is a mirror copy. */
    fun needsMirror(doc: Doc): Boolean = doc.file?.let { !isServed(it) } ?: true

    /** Drops a stale mirror copy after the original changed. */
    fun invalidate(doc: Doc) {
        val key = mirrorKey(doc.key)
        File(mirrorDir, "$key.png").delete()
        File(mirrorDir, "$key.src").delete()
    }

    private fun sourceDoc(source: String): Doc? =
        if (source.startsWith("content://")) platform.contentDoc(source) else LocalFileDoc(File(source))

    private fun mirrorSourceOf(file: File): String? {
        val parent = file.absoluteFile.parentFile ?: return null
        if (parent.canonicalPathOrSelf() != mirrorDir.canonicalPathOrSelf() || !file.name.endsWith(".png")) return null
        val key = file.name.removeSuffix(".png")
        mirrorSources[key]?.let { return it }
        val sidecar = File(mirrorDir, "$key.src")
        return if (sidecar.isFile) sidecar.readLines().firstOrNull()?.takeIf { it.isNotEmpty() } else null
    }

    private fun materialize(doc: Doc, key: String, mirror: File) {
        val sidecar = File(mirrorDir, "$key.src")
        val stamp = "${doc.key}\n${doc.length()}\n${doc.lastModified()}"
        if (mirror.isFile && sidecar.isFile && sidecar.readText() == stamp) {
            mirror.setLastModified(System.currentTimeMillis())
            return
        }
        mirrorDir.mkdirs()
        val temp = File(mirrorDir, "$key.tmp")
        doc.openRead().use { input ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
        mirror.delete()
        if (!temp.renameTo(mirror)) {
            temp.delete()
            return
        }
        sidecar.writeText(stamp)
        trim(mirror)
    }

    private fun trim(keep: File) {
        val files = mirrorDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        var total = 0L
        var count = 0
        for (f in files.sortedByDescending { it.lastModified() }) {
            count++
            total += f.length()
            if (f != keep && (count > maxFiles || total > maxBytes)) {
                f.delete()
                File(mirrorDir, f.name.removeSuffix(".png") + ".src").delete()
            }
        }
    }

    private fun isServed(file: File): Boolean {
        val path = file.canonicalPathOrSelf()
        return platform.servedRoots().any { root ->
            val r = root.canonicalPathOrSelf()
            path.startsWith(r + File.separator)
        }
    }

    companion object {
        fun mirrorKey(source: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            val sb = StringBuilder(32)
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                sb.append(hex[b ushr 4]).append(hex[b and 15])
            }
            return sb.toString()
        }

        private fun File.canonicalPathOrSelf(): String = try {
            canonicalPath
        } catch (e: Exception) {
            absolutePath
        }
    }
}
