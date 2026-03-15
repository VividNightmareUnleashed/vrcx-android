package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response

class ErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())

        if (response.code == 401) {
            // Session expired - will be handled by auth state
            return response
        }

        var retryCount = 0
        while (response.code == 429 && retryCount < MAX_RETRIES) {
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            val delayMs = if (retryAfterHeader != null && retryAfterHeader > 0) {
                minOf(retryAfterHeader * 1000, MAX_RETRY_DELAY_MS)
            } else {
                minOf(DEFAULT_RETRY_DELAY_MS * (1L shl retryCount), MAX_RETRY_DELAY_MS)
            }
            Thread.sleep(delayMs)
            response.close()
            response = chain.proceed(chain.request())
            retryCount++
        }

        return response
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val DEFAULT_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }
}
