package com.vrcx.android.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64

class AuthInterceptor : Interceptor {

    private val basicAuth = AtomicReference<String?>(null)

    fun setBasicAuth(username: String, password: String) {
        basicAuth.set("Basic ${encodeBasicAuth(username, password)}")
    }

    internal fun encodeBasicAuth(username: String, password: String): String {
        val credentials = "${encodeURIComponent(username)}:${encodeURIComponent(password)}"
        return Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
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

    internal fun encodeURIComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }
}
