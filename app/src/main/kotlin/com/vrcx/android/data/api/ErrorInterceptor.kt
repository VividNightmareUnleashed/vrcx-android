package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation

/**
 * Handles common HTTP failure modes for VRChat API calls.
 *
 * - **401 Unauthorized**: emits AuthEvent.Unauthorized for cookie-auth requests so
 *   AuthRepository can verify whether the session is still valid. Authentication-
 *   phase failures are left to the login caller so they do not clear an existing
 *   cookie session — a request is auth-phase if it carries `Basic` credentials or
 *   its endpoint is annotated [AuthPhase] (read from the Retrofit [Invocation]
 *   tag, so no endpoint-path knowledge is baked into this shared interceptor).
 * - **429 Too Many Requests**: retries once with a bounded delay. The delay is
 *   capped at [MAX_RETRY_DELAY_MS] (2s) because this interceptor runs on OkHttp
 *   dispatcher threads — blocking one of them for longer starves other in-flight
 *   requests. For sustained rate-limiting, callers should implement their own
 *   coroutine-based backoff.
 */
class ErrorInterceptor(
    private val authEventBus: AuthEventBus,
    /** Blocking wait strategy used before the single rate-limit retry. */
    private val waitBeforeRetry: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())

        if (emitUnauthorizedIfNeeded(response)) {
            return response
        }

        if (response.code == HTTP_TOO_MANY_REQUESTS) {
            val delayMs = retryDelayMillis(response.header("Retry-After"))
            // Release the body and its pooled connection before waiting, so the
            // delay doesn't also hold a connection out of the per-host pool.
            response.close()
            waitBeforeRetry(delayMs)
            response = chain.proceed(chain.request())
        }

        emitUnauthorizedIfNeeded(response)
        return response
    }

    private fun retryDelayMillis(retryAfterHeader: String?): Long {
        val seconds = retryAfterHeader?.toLongOrNull()?.takeIf { it > 0 }
            ?: return DEFAULT_RETRY_DELAY_MS
        if (seconds > MAX_RETRY_DELAY_MS / MILLIS_PER_SECOND) {
            return MAX_RETRY_DELAY_MS
        }
        return minOf(seconds * MILLIS_PER_SECOND, MAX_RETRY_DELAY_MS)
    }

    private fun emitUnauthorizedIfNeeded(response: Response): Boolean {
        if (response.code != HTTP_UNAUTHORIZED) {
            return false
        }
        if (response.request.header("Authorization")?.startsWith("Basic ", ignoreCase = true) == true) {
            return true
        }
        // An invalid 2FA code is an authentication-phase error, not evidence
        // that an established cookie session expired. The endpoint declares
        // itself login-phase via @AuthPhase; let the login flow keep its
        // selected 2FA methods and surface the verification error locally.
        if (response.request.isAuthPhase()) {
            return true
        }
        authEventBus.tryEmit(AuthEvent.Unauthorized)
        return true
    }

    /**
     * True when the request targets a Retrofit endpoint marked [AuthPhase].
     * Retrofit attaches an [Invocation] tag to every call it issues; raw OkHttp
     * requests (e.g. images, the WebSocket handshake) have none and are never
     * auth-phase.
     */
    private fun okhttp3.Request.isAuthPhase(): Boolean {
        val method = tag(Invocation::class.java)?.method() ?: return false
        return method.isAnnotationPresent(AuthPhase::class.java)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        internal const val DEFAULT_RETRY_DELAY_MS = 1_000L
        internal const val MAX_RETRY_DELAY_MS = 2_000L
    }
}
