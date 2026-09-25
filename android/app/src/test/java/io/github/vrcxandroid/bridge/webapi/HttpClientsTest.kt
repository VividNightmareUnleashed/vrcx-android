package io.github.vrcxandroid.bridge.webapi

import okhttp3.Call
import okhttp3.Connection
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class HttpClientsTest {
    /** Answers each request with the next scripted response and records what was sent. */
    private class ScriptedChain(private var current: Request, private val responses: ArrayDeque<(Request) -> Response>) : Interceptor.Chain {
        val sent = ArrayList<Request>()
        override fun request(): Request = current
        override fun proceed(request: Request): Response {
            current = request
            sent.add(request)
            return responses.removeFirst()(request)
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    private fun response(request: Request, code: Int, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { if (location != null) header("Location", location) }
            .body("".toResponseBody()).build()

    @Test
    fun httpsToHttpRedirectIsNotFollowed() {
        val start = Request.Builder().url("https://a.example/x").build()
        val chain = ScriptedChain(start, ArrayDeque(listOf({ r -> response(r, 302, "http://b.example/y") })))
        val result = DotNetRedirectInterceptor().intercept(chain)
        assertEquals(302, result.code)
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun httpToHttpsRedirectIsFollowed() {
        val start = Request.Builder().url("http://a.example/x").build()
        val chain = ScriptedChain(
            start,
            ArrayDeque(listOf({ r -> response(r, 301, "https://a.example/x") }, { r -> response(r, 200) })),
        )
        assertEquals(200, DotNetRedirectInterceptor().intercept(chain).code)
        assertEquals("https://a.example/x", chain.sent[1].url.toString())
    }

    @Test
    fun seeOtherTurnsPutIntoGetButKeepsHead() {
        val put = Request.Builder().url("http://a.example/x").put(okhttp3.RequestBody.create(null, "b"))
            .header("Content-MD5", "abc").build()
        val chain = ScriptedChain(put, ArrayDeque(listOf({ r -> response(r, 303, "/y") }, { r -> response(r, 200) })))
        DotNetRedirectInterceptor().intercept(chain)
        assertEquals("GET", chain.sent[1].method)
        assertNull(chain.sent[1].body)
        assertNull(chain.sent[1].header("Content-MD5"))

        val head = Request.Builder().url("http://a.example/x").head().build()
        val chain2 = ScriptedChain(head, ArrayDeque(listOf({ r -> response(r, 303, "/y") }, { r -> response(r, 200) })))
        DotNetRedirectInterceptor().intercept(chain2)
        assertEquals("HEAD", chain2.sent[1].method)
    }

    @Test
    fun nonHttpLocationAndMissingLocationStop() {
        val start = Request.Builder().url("http://a.example/x").build()
        val chain = ScriptedChain(start, ArrayDeque(listOf({ r -> response(r, 302, "vrcx://user/x") })))
        assertEquals(302, DotNetRedirectInterceptor().intercept(chain).code)
        val chain2 = ScriptedChain(start, ArrayDeque(listOf({ r -> response(r, 307) })))
        assertEquals(307, DotNetRedirectInterceptor().intercept(chain2).code)
    }

    @Test
    fun clientConfiguration() {
        val client = HttpClients.create(CookieJar.NO_COOKIES, "VRCX 2026.09.16", null)
        assertEquals(100_000, client.callTimeoutMillis)
        assertEquals(10, client.dispatcher.maxRequestsPerHost)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.proxy)
        assertTrue(client.interceptors.any { it is DotNetRedirectInterceptor })
        assertTrue(client.interceptors.any { it is UserAgentInterceptor })
        assertTrue(client.interceptors.any { it === okhttp3.brotli.BrotliInterceptor })

        val proxied = HttpClients.create(CookieJar.NO_COOKIES, "ua", ProxySpec.parse("socks5://10.0.0.2:1080"))
        assertEquals(Proxy.Type.SOCKS, proxied.proxy!!.type())
    }
}
