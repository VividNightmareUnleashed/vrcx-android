package com.vrcx.android.data.cache

import android.content.Context
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink

@Singleton
class ProfilePicCacheManager internal constructor(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    maxEntryBytes: Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Named("imageOkHttpClient") okHttpClient: OkHttpClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(context, okHttpClient, ioDispatcher, MAX_CACHE_ENTRY_BYTES)

    private val cacheDir = File(context.filesDir, "profile_pic_cache")
    private val writesSinceTrim = AtomicInteger(0)
    private val bodyWriter = BoundedBodyWriter(maxEntryBytes)

    init {
        // Don't scan/trim the cache dir here: this singleton is constructed on
        // the main thread at the first Coil request. trimCache() runs on IO
        // from the write path instead, keeping the directory bounded.
        cacheDir.mkdirs()
    }

    internal fun urlToFilename(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    fun getCachedFile(url: String): File? {
        val file = File(cacheDir, urlToFilename(url))
        if (!file.exists() || file.length() <= 0) return null
        // Only refresh the LRU stamp occasionally: this is the read path, and a
        // metadata write per image request costs far more than the eviction
        // ordering it buys.
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > LRU_TOUCH_INTERVAL_MS) file.setLastModified(now)
        return file
    }

    suspend fun cacheImage(url: String) = withContext(ioDispatcher) {
        cacheImage(url, trimAfterWrite = true)
    }

    private fun cacheImage(url: String, trimAfterWrite: Boolean) {
        val filename = urlToFilename(url)
        val file = File(cacheDir, filename)
        if (file.exists() && file.length() > 0) return

        // A temp path derived from the URL would be shared by two callers racing on
        // the same avatar, and the loser's partial file would win the rename.
        val tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, cacheDir)
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body ?: return
                if (!bodyWriter.write(body, tempFile)) return
            }
            if (tempFile.length() > 0 && tempFile.renameTo(file)) {
                file.setLastModified(System.currentTimeMillis())
                if (trimAfterWrite) maybeTrim()
            }
        } finally {
            tempFile.delete()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCacheSizeBytes(): Long = cacheDir.listFiles()
        ?.filterNot { it.name.endsWith(TEMP_FILE_SUFFIX) }
        ?.sumOf { it.length() }
        ?: 0L

    suspend fun cacheAllFriends(friends: Map<String, FriendContext>, onProgress: (Int, Int) -> Unit) =
        withContext(ioDispatcher) {
            val urls = friends.values.mapNotNull { friend ->
                val ref = friend.ref ?: return@mapNotNull null
                ref.displayAvatarUrl().takeIf { it.isNotEmpty() }
            }.distinct()
            val total = urls.size
            var completed = 0
            val progressLock = Any()
            // Bound bulk warmups so they cannot monopolize the image client's
            // dispatcher and connection pool.
            val semaphore = Semaphore(CACHE_WARMUP_CONCURRENCY)

            try {
                coroutineScope {
                    urls.map { url ->
                        async {
                            semaphore.withPermit {
                                try {
                                    cacheImage(url, trimAfterWrite = false)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                                synchronized(progressLock) {
                                    onProgress(++completed, total)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                trimCache()
            }
        }

    /**
     * Trim every [TRIM_INTERVAL] single-image writes rather than after each one.
     * A full trim lists and stats the whole directory, and one avatar cannot
     * meaningfully move a 64 MiB budget, so the bounds still hold within a small
     * overage. The bulk sweep trims once in its own `finally` instead.
     */
    private fun maybeTrim() {
        if (writesSinceTrim.incrementAndGet() < TRIM_INTERVAL) return
        trimCache()
    }

    private fun trimCache(nowMillis: Long = System.currentTimeMillis()) {
        writesSinceTrim.set(0)
        val (temps, cached) = cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.partition { it.name.endsWith(TEMP_FILE_SUFFIX) }
            ?: return
        // Death between createTempFile and the rename orphans a temp file, and
        // nothing on the write path can clean it up afterwards.
        temps.forEach { if (nowMillis - it.lastModified() > MAX_TEMP_AGE_MS) it.delete() }

        val entries = cached
            .map { CachedFile(it, it.lastModified(), it.length()) }
            .sortedBy { it.lastModified }
        var totalBytes = entries.sumOf { it.length }
        entries.forEach { entry ->
            if (nowMillis - entry.lastModified > MAX_CACHE_AGE_MS || totalBytes > MAX_CACHE_BYTES) {
                if (entry.file.delete()) totalBytes -= entry.length
            }
        }
    }

    private data class CachedFile(val file: File, val lastModified: Long, val length: Long)

    private companion object {
        const val MAX_CACHE_BYTES = 64L * 1024L * 1024L

        // An entry larger than the entire cache could not survive the next trim.
        const val MAX_CACHE_ENTRY_BYTES = MAX_CACHE_BYTES
        const val MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val MAX_TEMP_AGE_MS = 60L * 60L * 1000L
        const val LRU_TOUCH_INTERVAL_MS = 60L * 60L * 1000L
        const val TRIM_INTERVAL = 20
        const val TEMP_FILE_PREFIX = "pic"
        const val TEMP_FILE_SUFFIX = ".tmp"
        const val CACHE_WARMUP_CONCURRENCY = 4
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}

private class BoundedBodyWriter(private val maxBytes: Long) {
    init {
        require(maxBytes > 0)
    }

    fun write(body: ResponseBody, destination: File): Boolean {
        if (body.contentLength() > maxBytes) return false

        return body.source().use { source ->
            destination.sink().buffer().use { sink ->
                copy(source, sink)
            }
        }
    }

    private fun copy(source: BufferedSource, sink: BufferedSink): Boolean {
        val buffer = Buffer()
        var bytesWritten = 0L
        var reachedEnd = false
        var oversized = false
        while (!reachedEnd && !oversized) {
            val remaining = maxBytes - bytesWritten
            val readLimit = if (remaining >= STREAM_BUFFER_BYTES) {
                STREAM_BUFFER_BYTES
            } else {
                // Probe one byte past the boundary without ever writing that byte.
                remaining + 1L
            }
            val read = source.read(buffer, readLimit)
            when {
                read == -1L -> reachedEnd = true

                read > remaining -> oversized = true

                else -> {
                    sink.write(buffer, read)
                    bytesWritten += read
                }
            }
        }
        return reachedEnd
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 8L * 1024L
    }
}
