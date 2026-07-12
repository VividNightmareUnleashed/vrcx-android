package com.vrcx.android.data.cache

import android.content.Context
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ProfilePicCacheManager @Inject constructor(
    @ApplicationContext context: Context,
    @Named("imageOkHttpClient")
    private val okHttpClient: OkHttpClient,
) {
    private val cacheDir = File(context.filesDir, "profile_pic_cache")

    init {
        // Don't scan/trim the cache dir here: this singleton is constructed on
        // the main thread at the first Coil request. trimCache() runs on IO
        // after each cacheImage instead, keeping the directory bounded.
        cacheDir.mkdirs()
    }

    private fun urlToFilename(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getCachedFile(url: String): File? {
        val file = File(cacheDir, urlToFilename(url))
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            null
        }
    }

    suspend fun cacheImage(url: String) = withContext(Dispatchers.IO) {
        val file = File(cacheDir, urlToFilename(url))
        if (file.exists() && file.length() > 0) return@withContext

        val tempFile = File(cacheDir, "${urlToFilename(url)}.tmp")
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext
                resp.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (tempFile.length() > 0) {
                if (tempFile.renameTo(file)) {
                    file.setLastModified(System.currentTimeMillis())
                    trimCache()
                }
            } else {
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.filterNot { it.name.endsWith(".tmp") }?.sumOf { it.length() } ?: 0L
    }

    suspend fun cacheAllFriends(
        friends: Map<String, FriendContext>,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val urls = friends.values.mapNotNull { friend ->
            val ref = friend.ref ?: return@mapNotNull null
            ref.displayAvatarUrl().takeIf { it.isNotEmpty() }
        }
        val total = urls.size
        var completed = 0
        val semaphore = Semaphore(4)

        coroutineScope {
            urls.map { url ->
                async {
                    semaphore.withPermit {
                        try {
                            cacheImage(url)
                        } catch (_: Exception) {
                        }
                        synchronized(this@ProfilePicCacheManager) {
                            completed++
                            onProgress(completed, total)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun trimCache(nowMillis: Long = System.currentTimeMillis()) {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var totalBytes = files.sumOf { it.length() }
        files.forEach { file ->
            if (nowMillis - file.lastModified() > MAX_CACHE_AGE_MS || totalBytes > MAX_CACHE_BYTES) {
                val bytes = file.length()
                if (file.delete()) totalBytes -= bytes
            }
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
        const val MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
