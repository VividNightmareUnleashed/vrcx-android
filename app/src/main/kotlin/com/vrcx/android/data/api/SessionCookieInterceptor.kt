package com.vrcx.android.data.api

import java.io.IOException
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Identifies the cookie session in which an OkHttp call was started. */
internal data class CookieSessionGeneration(val value: Long)

/** Captures cookie-session ownership before redirects, retries, and OkHttp's cookie bridge run. */
class SessionCookieRequestInterceptor(private val cookieJar: CookieJarImpl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .tag(
                CookieSessionGeneration::class.java,
                CookieSessionGeneration(cookieJar.sessionGeneration()),
            )
            .build()
        return chain.proceed(request)
    }
}

/**
 * Makes response-cookie persistence conditional on the session that sent the request.
 *
 * This is a network interceptor, so it sees the request after OkHttp's bridge attached cookies
 * and the response before that bridge forwards `Set-Cookie` to [CookieJarImpl].
 */
class SessionCookieResponseInterceptor(private val cookieJar: CookieJarImpl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val generation = request.tag(CookieSessionGeneration::class.java)
        return if (request.tag(AccountBoundCookies::class.java) != null || generation == null) {
            chain.proceed(request)
        } else {
            interceptSessionRequest(chain, request, generation.value)
        }
    }

    private fun interceptSessionRequest(
        chain: Interceptor.Chain,
        request: Request,
        expectedGeneration: Long,
    ): Response {
        if (!cookieJar.isSessionGenerationCurrent(expectedGeneration)) {
            throw IOException("Authenticated session changed before the request was sent")
        }

        val response = chain.proceed(request)
        val responseCookies = Cookie.parseAll(response.request.url, response.headers)
        if (responseCookies.isNotEmpty()) {
            cookieJar.saveFromResponseIfCurrent(expectedGeneration, response.request.url, responseCookies)
        }

        // The cookies were either accepted atomically above or rejected as stale. In both cases,
        // keeping the headers would let BridgeInterceptor write them again without the generation.
        return response.newBuilder()
            .removeHeader("Set-Cookie")
            .build()
    }
}
