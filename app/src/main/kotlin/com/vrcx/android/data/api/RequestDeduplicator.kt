package com.vrcx.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges identical in-flight GET requests so concurrent callers share one
 * network round trip, and holds the failure cache that [DedupInterceptor]
 * reads and writes.
 *
 * Mirrors desktop VRCX behavior from reference/src/services/request.js:
 * - Pending GET requests are merged (same URL returns same response)
 * - Missing resources are remembered for 15 minutes
 *
 * The failure cache is keyed by request URL and is written only from the HTTP
 * layer, so a resource has exactly one entry however it was fetched.
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
     * Check if a URL has a cached failure within the cache window.
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

    /**
     * Records a failure for [url] unless the session moved on while the request
     * was in flight, in which case the previous account's result must not reach
     * the new session's cache. Which status codes qualify is [DedupInterceptor]'s
     * policy, not this class's.
     */
    fun cacheFailureIfCurrent(url: String, statusCode: Int, expectedGeneration: Long): Boolean =
        synchronized(stateLock) {
            if (expectedGeneration != generation) {
                return@synchronized false
            }
            failureCache[url] = FailureEntry(statusCode, System.currentTimeMillis())
            true
        }

    /**
     * Serializes concurrent requests for the same key via a shared in-flight
     * result. Failure caching happens at the HTTP layer, so a request that gets
     * this far always reaches the network.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> dedupGet(key: String, block: suspend () -> T): T {
        val pending: PendingRequest
        val isOwner: Boolean

        synchronized(stateLock) {
            val requestGeneration = generation
            val candidate = PendingRequest(
                generation = requestGeneration,
                deferred = requestScope.async(start = CoroutineStart.LAZY) {
                    val result = block()
                    if (currentGeneration() != requestGeneration) {
                        throw CancellationException("Request session changed")
                    }
                    result
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
