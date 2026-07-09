package com.vrcx.android.data.cache

import android.content.Context
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
import javax.inject.Singleton

@Singleton
class ProfilePicCacheManager @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val cacheDir = File(context.filesDir, "profile_pic_cache")

    init {
        cacheDir.mkdirs()
    }

    private fun urlToFilename(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getCachedFile(url: String): File? {
        val file = File(cacheDir, urlToFilename(url))
        return if (file.exists() && file.length() > 0) file else null
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
                tempFile.renameTo(file)
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
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun cacheAllFriends(
        friends: Map<String, FriendContext>,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val urls = friends.values.mapNotNull { friend ->
            val ref = friend.ref ?: return@mapNotNull null
            ref.profilePicOverride.takeIf { it.isNotEmpty() }
                ?: ref.currentAvatarThumbnailImageUrl.takeIf { it.isNotEmpty() }
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
}
