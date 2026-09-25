package io.github.vrcxandroid.bridge.webapi

import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The shared OkHttp client, configured like upstream's `SocketsHttpHandler`:
 * cookie jar, brotli + gzip, 5 minute connection pool, 10 requests per host, 100 s per call, the `VRCX <version>`
 * User-Agent, .NET redirect rules and the optional proxy ([ProxySpec]: HTTP and SOCKS5 through OkHttp's own proxy
 * support, SOCKS4/4a through [Socks4SocketFactory], anything else fails every call).
 */
object HttpClients {
    const val CALL_TIMEOUT_SECONDS = 100L
    const val MAX_REQUESTS_PER_HOST = 10

    fun create(cookieJar: CookieJar, userAgent: String, proxy: ProxySpec?): OkHttpClient {
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // .NET has only the overall timeout; slow APIs (translation, avatar search) may pause longer than OkHttp's
            // 10 s read default. The call timeout still bounds everything.
            .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Redirects are followed by DotNetRedirectInterceptor (max 50, https → http refused, http → https allowed).
            .followRedirects(false)
            .followSslRedirects(false)
        if (proxy != null) applyProxy(builder, proxy)
        builder
            .addInterceptor(DotNetRedirectInterceptor())
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(BrotliInterceptor)
        return builder.build()
    }

    private fun applyProxy(builder: OkHttpClient.Builder, proxy: ProxySpec) {
        val address = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
        when (proxy.nativeKind) {
            ProxySpec.Kind.HTTP -> {
                builder.proxy(Proxy(Proxy.Type.HTTP, address))
                val user = proxy.username
                if (user != null) {
                    val credential = Credentials.basic(user, proxy.password.orEmpty())
                    builder.proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                    }
                }
            }
            // The JDK's SOCKS5 client; OkHttp passes the host name, which the proxy resolves (like .NET's socks5).
            ProxySpec.Kind.SOCKS5 -> builder.proxy(Proxy(Proxy.Type.SOCKS, address))
            ProxySpec.Kind.SOCKS4, ProxySpec.Kind.SOCKS4A -> builder
                .proxy(Proxy.NO_PROXY)
                .socketFactory(Socks4SocketFactory(proxy))
                .dns(Socks4Dns(remoteResolution = proxy.nativeKind == ProxySpec.Kind.SOCKS4A))
            // Fail every call rather than send it around the proxy the user configured.
            null -> builder.proxy(Proxy.NO_PROXY).addInterceptor(UnsupportedProxyInterceptor(proxy))
        }
    }
}

/** Fails every call: the configured proxy uses a scheme OkHttp cannot speak (for example `https://`). */
class UnsupportedProxyInterceptor(private val proxy: ProxySpec) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        throw IOException(
            "The proxy ${proxy.displayName} is not supported for VRCX's own requests on Android. " +
                "Use an http://, socks4:// or socks5:// proxy.",
        )
}

/** Default `User-Agent` for requests that do not set their own (upstream `DefaultRequestHeaders`). */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }
}

/**
 * .NET `RedirectHandler` semantics: follows 300/301/302/303/307/308 with a `Location` up to [maxRedirects] times
 * (then returns the last 3xx response), never from https to http, drops `Authorization` on every hop, and switches to
 * a body-less GET for POST on 300/301/302 and for anything but GET/HEAD on 303. Each hop passes through the rest of
 * the chain, so the cookie jar sees every response.
 */
class DotNetRedirectInterceptor(private val maxRedirects: Int = 50) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var redirects = 0
        while (true) {
            val target = redirectTarget(request.url, response) ?: return response
            if (++redirects > maxRedirects) return response
            val builder = request.newBuilder().url(target).removeHeader("Authorization")
            if (forceGet(response.code, request.method)) {
                builder.method("GET", null)
                // The content (and with it the content headers) is dropped.
                for (h in CONTENT_HEADERS) builder.removeHeader(h)
            }
            response.close()
            request = builder.build()
            response = chain.proceed(request)
        }
    }

    private fun redirectTarget(from: HttpUrl, response: Response): HttpUrl? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.header("Location") ?: return null
        val target = from.resolve(location) ?: return null
        if (from.isHttps && !target.isHttps) return null
        return target
    }

    private fun forceGet(code: Int, method: String): Boolean = when (code) {
        300, 301, 302 -> method == "POST"
        303 -> method != "GET" && method != "HEAD"
        else -> false
    }

    private companion object {
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val CONTENT_HEADERS = listOf("Content-Type", "Content-Length", "Content-MD5", "Content-Encoding", "Transfer-Encoding")
    }
}
