package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Caches 404 and 403 responses for GET requests at the HTTP layer so every
 * repository benefits without opting in. Mirrors the desktop client's
 * request.js behavior of remembering "this resource doesn't exist (or is not
 * mine to read)" for [RequestDeduplicator.FAILURE_CACHE_DURATION_MS] before
 * letting another request through.
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
        if (request.method != "GET") return chain.proceed(request)

        val cacheKey = request.url.toString()
        deduplicator.getCachedFailure(cacheKey)?.let { code ->
            return syntheticFailureResponse(chain, code)
        }

        val response = chain.proceed(request)
        when (response.code) {
            404, 403 -> deduplicator.cacheFailure(cacheKey, response.code)
            in 200..299 -> {
                // A previously failing URL now returns success; clear the stale
                // cache entry so subsequent calls don't keep replaying the 404.
                deduplicator.invalidateFailure(cacheKey)
            }
        }
        return response
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
