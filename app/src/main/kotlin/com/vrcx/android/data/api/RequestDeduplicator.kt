package com.vrcx.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deduplicates identical GET requests within a time window and caches
 * 404/403 failures to avoid retrying known-missing resources.
 *
 * Mirrors desktop VRCX behavior from reference/src/services/request.js:
 * - Pending GET requests are merged (same URL returns same response)
 * - 404/403 responses are cached for 15 minutes
 */
@Singleton
class RequestDeduplicator @Inject constructor() {

    private data class FailureEntry(
        val statusCode: Int,
        val timestamp: Long,
    )

    private data class PendingRequest(
        val generation: Long,
        val deferred: Deferred<Any?>,
    )

    private val stateLock = Any()
    private val failureCache = ConcurrentHashMap<String, FailureEntry>()
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation = 0L

    /**
     * Check if a URL has a cached failure (404/403) within the cache window.
     * Returns the cached status code, or null if no cached failure.
     */
    fun getCachedFailure(url: String): Int? = synchronized(stateLock) {
        val entry = failureCache[url] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.timestamp > FAILURE_CACHE_DURATION_MS) {
            failureCache.remove(url, entry)
            return@synchronized null
        }
        entry.statusCode
    }

    fun currentGeneration(): Long = synchronized(stateLock) { generation }

    fun cacheFailureIfCurrent(url: String, statusCode: Int, expectedGeneration: Long): Boolean =
        synchronized(stateLock) {
            if (expectedGeneration != generation || (statusCode != 404 && statusCode != 403)) {
                return@synchronized false
            }
            failureCache[url] = FailureEntry(statusCode, System.currentTimeMillis())
            true
        }

    /**
     * Deduplicate a GET request: checks failure cache, serializes concurrent
     * requests to the same key via a shared in-flight result, and caches
     * 404/403 failures.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> dedupGet(key: String, block: suspend () -> T): T {
        val pending: PendingRequest
        val isOwner: Boolean

        synchronized(stateLock) {
            val requestGeneration = generation
            getCachedFailure(key)?.let { code ->
                throw HttpException(
                    Response.error<Any>(code, "".toResponseBody(null))
                )
            }

            val candidate = PendingRequest(
                generation = requestGeneration,
                deferred = requestScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val result = block()
                        if (currentGeneration() != requestGeneration) {
                            throw CancellationException("Request session changed")
                        }
                        result
                    } catch (t: Throwable) {
                        if (t is HttpException) {
                            cacheFailureIfCurrent(key, t.code(), requestGeneration)
                        }
                        throw t
                    }
                },
            )
            val existing = pendingRequests.putIfAbsent(key, candidate)
            if (existing == null) {
                pending = candidate
                isOwner = true
            } else {
                candidate.deferred.cancel()
                pending = existing
                isOwner = false
            }
        }

        if (isOwner) {
            pending.deferred.invokeOnCompletion { pendingRequests.remove(key, pending) }
            pending.deferred.start()
        }

        val result = pending.deferred.await()
        if (currentGeneration() != pending.generation) {
            throw CancellationException("Request session changed")
        }
        return result as T
    }

    fun clearCache() {
        val requestsToCancel = synchronized(stateLock) {
            generation++
            failureCache.clear()
            pendingRequests.values.toList().also { pendingRequests.clear() }
        }
        requestsToCancel.forEach { pendingRequest ->
            pendingRequest.deferred.cancel(CancellationException("Request cache cleared"))
        }
    }

    fun invalidateFailure(key: String, expectedGeneration: Long): Boolean = synchronized(stateLock) {
        if (expectedGeneration != generation) return@synchronized false
        failureCache.remove(key)
        true
    }

    companion object {
        const val FAILURE_CACHE_DURATION_MS = 15 * 60 * 1000L // 15 minutes
    }
}
