package com.vrcx.android.data.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Deduplicates identical GET requests within a time window and caches
 * 404/403 failures to avoid retrying known-missing resources.
 *
 * Mirrors desktop VRCX behavior from reference/src/services/request.js:
 * - Pending GET requests are merged (same URL returns same response)
 * - 404/403 responses are cached for 15 minutes
 */
class RequestDeduplicator {

    private data class FailureEntry(
        val statusCode: Int,
        val timestamp: Long,
    )

    private val failureCache = ConcurrentHashMap<String, FailureEntry>()
    private val pendingRequests = ConcurrentHashMap<String, Mutex>()

    /**
     * Check if a URL has a cached failure (404/403) within the cache window.
     * Returns the cached status code, or null if no cached failure.
     */
    fun getCachedFailure(url: String): Int? {
        val entry = failureCache[url] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > FAILURE_CACHE_DURATION_MS) {
            failureCache.remove(url)
            return null
        }
        return entry.statusCode
    }

    /**
     * Record a failure for caching.
     */
    fun cacheFailure(url: String, statusCode: Int) {
        if (statusCode == 404 || statusCode == 403) {
            failureCache[url] = FailureEntry(statusCode, System.currentTimeMillis())
        }
    }

    /**
     * Get or create a mutex for a URL to deduplicate concurrent requests.
     */
    fun getMutex(url: String): Mutex {
        return pendingRequests.getOrPut(url) { Mutex() }
    }

    /**
     * Remove the pending request mutex after completion.
     */
    fun removePending(url: String) {
        pendingRequests.remove(url)
    }

    /**
     * Deduplicate a GET request: checks failure cache, serializes concurrent
     * requests to the same key via mutex, and caches 404/403 failures.
     */
    suspend fun <T> dedupGet(key: String, block: suspend () -> T): T {
        getCachedFailure(key)?.let { code ->
            throw HttpException(
                Response.error<Any>(code, "".toResponseBody(null))
            )
        }
        val mutex = getMutex(key)
        return mutex.withLock {
            try {
                block()
            } catch (e: HttpException) {
                cacheFailure(key, e.code())
                throw e
            }
        }
    }

    fun clearCache() {
        failureCache.clear()
    }

    companion object {
        const val FAILURE_CACHE_DURATION_MS = 15 * 60 * 1000L // 15 minutes
    }
}
