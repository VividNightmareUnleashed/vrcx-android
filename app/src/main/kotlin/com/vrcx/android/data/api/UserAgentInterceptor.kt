package com.vrcx.android.data.api

import com.vrcx.android.BuildConfig
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
        val USER_AGENT = "VRCX-Android/${BuildConfig.VERSION_NAME} https://github.com/VividNightmareUnleashed/vrcx-android"
    }
}
