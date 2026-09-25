package io.github.vrcxandroid.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Mapping between app-private files the page may display and their URLs (docs/ARCHITECTURE.md §6.6):
 * - `filesDir/local/<rel>` ↔ `https://appassets.androidplatform.net/local/<rel>`
 * - `cacheDir/<rel>` ↔ `https://appassets.androidplatform.net/local/cache/<rel>`
 *
 * `filesDir/local/cache/` is therefore reserved (its URL would collide with the cache mapping). The absolute path of a
 * file under either root is also served at that same path on the app origin, so an absolute path string works both as
 * an AppApi argument and as an `<img src>`.
 *
 * Pure JVM code (no android.* imports) so it is unit-tested directly.
 */
class LocalPaths(localRoot: File, cacheRoot: File, private val origin: String = HostUrls.ORIGIN) {
    val localRoot: File = localRoot.absoluteFile
    val cacheRoot: File = cacheRoot.absoluteFile

    private val localCanonical: File by lazy { canonical(this.localRoot) }
    private val cacheCanonical: File by lazy { canonical(this.cacheRoot) }

    /** URL under `/local/` for [file]; throws [IllegalArgumentException] outside the two roots. */
    fun urlFor(file: File): String {
        val target = canonical(file)
        relativeTo(target, localCanonical)?.let { rel ->
            require(rel != RESERVED_CACHE && !rel.startsWith("$RESERVED_CACHE/")) {
                "filesDir/local/cache is reserved: ${file.path}"
            }
            return origin + HostUrls.LOCAL_PREFIX + encodePath(rel)
        }
        relativeTo(target, cacheCanonical)?.let { rel ->
            return origin + HostUrls.LOCAL_CACHE_PREFIX + encodePath(rel)
        }
        throw IllegalArgumentException("File is outside the local roots: ${file.path}")
    }

    /**
     * Resolves a `/local/` URL (absolute, with the app origin), a `file://` URI or an absolute path to a file inside
     * one of the roots. Returns null for anything else, including paths that escape the roots.
     */
    fun fileFor(pathOrUrl: String?): File? {
        val raw = pathOrUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidate: File = when {
            raw.startsWith(origin + HostUrls.LOCAL_CACHE_PREFIX, ignoreCase = true) ->
                File(cacheRoot, decodePath(stripQuery(raw.substring(origin.length + HostUrls.LOCAL_CACHE_PREFIX.length))) ?: return null)
            raw.startsWith(origin + HostUrls.LOCAL_PREFIX, ignoreCase = true) ->
                File(localRoot, decodePath(stripQuery(raw.substring(origin.length + HostUrls.LOCAL_PREFIX.length))) ?: return null)
            raw.startsWith(origin + "/", ignoreCase = true) ->
                File(decodePath(stripQuery(raw.substring(origin.length))) ?: return null)
            raw.startsWith("file://", ignoreCase = true) ->
                File(decodePath(stripQuery(raw.substring("file://".length))) ?: return null)
            File(raw).isAbsolute -> File(raw)
            else -> return null
        }
        return contained(candidate)
    }

    /**
     * File for a request path suffix below a served root ([root] is [localRoot] or [cacheRoot]); null when it escapes
     * the root or names a directory. [deniedTopLevel] lists first segments that must never be served.
     */
    fun resolveServed(root: File, suffix: String, deniedTopLevel: Set<String> = emptySet()): File? {
        val clean = suffix.trimStart('/')
        if (clean.isEmpty()) return null
        val first = clean.substringBefore('/')
        if (deniedTopLevel.any { first.equals(it, ignoreCase = true) || first.startsWith("org.chromium") }) return null
        val rootCanonical = canonical(root)
        val file = canonical(File(root, clean))
        if (relativeTo(file, rootCanonical) == null) return null
        if (root.absoluteFile == localRoot) {
            val rel = relativeTo(file, rootCanonical)
            if (rel == RESERVED_CACHE || rel?.startsWith("$RESERVED_CACHE/") == true) return null
        }
        return file
    }

    /** Returns [file] (canonical) when it lies inside one of the roots, else null. */
    fun contained(file: File): File? {
        val c = canonical(file)
        return if (relativeTo(c, localCanonical) != null || relativeTo(c, cacheCanonical) != null) c else null
    }

    companion object {
        private const val RESERVED_CACHE = "cache"

        /** Relative path of [file] below [root] ('/'-separated, non-empty), or null when not strictly inside. */
        internal fun relativeTo(file: File, root: File): String? {
            val f = file.path.replace('\\', '/')
            val r = root.path.replace('\\', '/').trimEnd('/')
            if (!f.startsWith("$r/")) return null
            val rel = f.substring(r.length + 1)
            return rel.ifEmpty { null }
        }

        internal fun canonical(file: File): File = try {
            file.canonicalFile
        } catch (_: IOException) {
            file.absoluteFile.normalize()
        }

        private fun stripQuery(s: String): String {
            val q = s.indexOfFirst { it == '?' || it == '#' }
            return if (q >= 0) s.substring(0, q) else s
        }

        /** Percent-encodes every path segment (RFC 3986 unreserved characters and '/' stay as they are). */
        fun encodePath(path: String): String {
            val out = StringBuilder()
            for (b in path.replace('\\', '/').toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == '/') {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return out.toString()
        }

        /** Percent-decodes a URL path ('+' stays a plus). Null when the escapes are malformed. */
        fun decodePath(path: String): String? {
            if ('%' !in path) return path
            val bytes = ByteArrayOutputStream(path.length)
            var i = 0
            while (i < path.length) {
                val ch = path[i]
                if (ch == '%') {
                    if (i + 2 >= path.length) return null
                    val hi = Character.digit(path[i + 1], 16)
                    val lo = Character.digit(path[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write((hi shl 4) or lo)
                    i += 3
                } else {
                    val cp = path.codePointAt(i)
                    val encoded = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    i += Character.charCount(cp)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
