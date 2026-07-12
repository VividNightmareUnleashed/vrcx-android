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
        internal const val DEFAULT_RETRY_DELAY_MS = 1_000L
        internal const val MAX_RETRY_DELAY_MS = 2_000L
    }
}
