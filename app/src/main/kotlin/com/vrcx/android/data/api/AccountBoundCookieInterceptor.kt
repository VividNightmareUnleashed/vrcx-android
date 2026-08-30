package com.vrcx.android.data.api

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Immutable cookies captured for a request that must remain owned by one account. */
class AccountBoundCookies internal constructor(private val cookiesByHost: Map<String, List<Cookie>>) {
    internal fun headerFor(url: HttpUrl, nowMillis: Long = System.currentTimeMillis()): String? = cookiesByHost.values
        .flatten()
        .filter { cookie -> cookie.expiresAt >= nowMillis && cookie.matches(url) }
        .distinctBy { cookie -> Triple(cookie.name, cookie.domain, cookie.path) }
        .takeIf { cookies -> cookies.isNotEmpty() }
        ?.joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
}

/** Keeps an account-bound request independent from later mutations of the shared cookie jar. */
@Singleton
class AccountBoundCookieInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = request.tag(AccountBoundCookies::class.java)
            ?: return chain.proceed(request)
        val cookieHeader = cookies.headerFor(request.url)
        val boundRequest = request.newBuilder()
            .removeHeader("Cookie")
            .apply {
                if (cookieHeader != null) header("Cookie", cookieHeader)
            }
            .build()

        // A canceled call may finish unwinding after logout or account switch.
        // Keeping its response cookies away from the shared jar prevents that
        // late callback from reviving or contaminating the previous session.
        return chain.proceed(boundRequest)
            .newBuilder()
            .removeHeader("Set-Cookie")
            .build()
    }
}
