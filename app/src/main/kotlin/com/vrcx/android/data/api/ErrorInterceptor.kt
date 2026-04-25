package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Handles common HTTP failure modes for VRChat API calls.
 *
 * - **401 Unauthorized**: emits AuthEvent.Unauthorized so AuthRepository can flip
 *   state to NotLoggedIn. The response itself is passed through unchanged so the
 *   caller still sees the original failure.
 * - **429 Too Many Requests**: retries once with a bounded delay. The delay is
 *   capped at [MAX_RETRY_DELAY_MS] (2s) because this interceptor runs on OkHttp
 *   dispatcher threads — blocking one of them for longer starves other in-flight
 *   requests. For sustained rate-limiting, callers should implement their own
 *   coroutine-based backoff.
 */
class ErrorInterceptor(
    private val authEventBus: AuthEventBus,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())

        if (emitUnauthorizedIfNeeded(response)) {
            return response
        }

        if (response.code == 429) {
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            val delayMs = if (retryAfterHeader != null && retryAfterHeader > 0) {
                minOf(retryAfterHeader * 1000, MAX_RETRY_DELAY_MS)
            } else {
                DEFAULT_RETRY_DELAY_MS
            }
            Thread.sleep(delayMs)
            response.close()
            response = chain.proceed(chain.request())
        }

        emitUnauthorizedIfNeeded(response)
        return response
    }

    private fun emitUnauthorizedIfNeeded(response: Response): Boolean {
        if (response.code != 401) {
            return false
        }
        authEventBus.tryEmit(AuthEvent.Unauthorized)
        return true
    }

    companion object {
        internal const val DEFAULT_RETRY_DELAY_MS = 1_000L
        internal const val MAX_RETRY_DELAY_MS = 2_000L
    }
}
