package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (!response.isSuccessful) {
            when (response.code) {
                401 -> {
                    // Session expired - will be handled by auth state
                }
                429 -> {
                    // Rate limited - extract retry-after header
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    if (retryAfter != null && retryAfter > 0) {
                        Thread.sleep(minOf(retryAfter * 1000, 30_000L))
                        response.close()
                        return chain.proceed(chain.request())
                    }
                }
            }
        }

        return response
    }
}
