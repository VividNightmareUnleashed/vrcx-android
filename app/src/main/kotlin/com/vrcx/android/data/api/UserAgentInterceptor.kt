package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val USER_AGENT = "VRCX-Android/1.0.0"
    }
}
