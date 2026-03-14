package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import android.util.Base64

class AuthInterceptor : Interceptor {

    private val basicAuth = AtomicReference<String?>(null)

    fun setBasicAuth(username: String, password: String) {
        val encodedUser = URLEncoder.encode(username, "UTF-8")
        val encodedPass = URLEncoder.encode(password, "UTF-8")
        val credentials = "$encodedUser:$encodedPass"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        basicAuth.set("Basic $encoded")
    }

    fun clearBasicAuth() {
        basicAuth.set(null)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestBuilder = chain.request().newBuilder()

        basicAuth.get()?.let { auth ->
            requestBuilder.header("Authorization", auth)
        }

        return chain.proceed(requestBuilder.build())
    }
}
