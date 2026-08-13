package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Invocation

/**
 * Caches 404 responses for GET requests at the HTTP layer so every repository
 * benefits without opting in. Mirrors the desktop client's request.js behavior
 * of remembering "this resource doesn't exist" for
 * [RequestDeduplicator.FAILURE_CACHE_DURATION_MS] before letting another
 * request through.
 *
 * This is the only place failures are cached, and it deliberately stops at 404.
 * A 403 says "not yours to read" — a statement about permissions the user can
 * change from inside the app (joining a group, being accepted as a friend), and
 * the cache has no invalidation path short of sign-out, so caching one would
 * leave the screen empty for fifteen minutes after the permission was granted.
 * Endpoints marked [NoFailureCache] skip the cache entirely.
 *
 * In-flight burst merging stays in [RequestDeduplicator.dedupGet] because
 * sharing a single OkHttp Response body across multiple coroutine callers is
 * not safe at the interceptor level — the body stream is one-shot.
 */
class DedupInterceptor(
    private val deduplicator: RequestDeduplicator,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || request.isFailureCacheExempt()) return chain.proceed(request)

        val cacheKey = request.url.toString()
        val requestGeneration = deduplicator.currentGeneration()
        deduplicator.getCachedFailure(cacheKey)?.let { code ->
            return syntheticFailureResponse(chain, code)
        }

        val response = chain.proceed(request)
        when (response.code) {
            404 -> deduplicator.cacheFailureIfCurrent(cacheKey, response.code, requestGeneration)
            in 200..299 -> {
                // A previously failing URL now returns success; clear the stale
                // cache entry so subsequent calls don't keep replaying the 404.
                deduplicator.invalidateFailure(cacheKey, requestGeneration)
            }
        }
        return response
    }

    /**
     * True when the request targets a Retrofit endpoint marked [NoFailureCache].
     * Raw OkHttp requests carry no [Invocation] tag and are never exempt.
     */
    private fun okhttp3.Request.isFailureCacheExempt(): Boolean {
        val method = tag(Invocation::class.java)?.method() ?: return false
        return method.isAnnotationPresent(NoFailureCache::class.java)
    }

    private fun syntheticFailureResponse(chain: Interceptor.Chain, code: Int): Response {
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Cached failure")
            .body("".toResponseBody("application/json".toMediaTypeOrNull()))
            .header("X-VRCX-Cached-Failure", "true")
            .build()
    }
}
