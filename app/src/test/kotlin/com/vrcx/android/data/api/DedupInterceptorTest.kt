package com.vrcx.android.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DedupInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var deduplicator: RequestDeduplicator
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        deduplicator = RequestDeduplicator()
        client = OkHttpClient.Builder()
            .addInterceptor(DedupInterceptor(deduplicator))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `caches a 404 GET response and short-circuits the next identical request`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val first = client.newCall(Request.Builder().url(server.url("/missing")).build()).execute()
        first.close()
        val second = client.newCall(Request.Builder().url(server.url("/missing")).build()).execute()

        assertEquals(404, first.code)
        assertEquals(404, second.code)
        assertEquals("true", second.header("X-VRCX-Cached-Failure"))
        // Only the first request actually hit the server.
        assertEquals(1, server.requestCount)
        second.close()
    }

    @Test
    fun `caches a 403 GET response`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val first = client.newCall(Request.Builder().url(server.url("/forbidden")).build()).execute()
        first.close()
        val second = client.newCall(Request.Builder().url(server.url("/forbidden")).build()).execute()

        assertEquals(403, second.code)
        assertEquals(1, server.requestCount)
        second.close()
    }

    @Test
    fun `does not cache 500 responses`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        client.newCall(Request.Builder().url(server.url("/boom")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/boom")).build()).execute().close()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not cache POST responses`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        client.newCall(
            Request.Builder()
                .url(server.url("/missing"))
                .post("".toRequestBody())
                .build(),
        ).execute().close()
        client.newCall(
            Request.Builder()
                .url(server.url("/missing"))
                .post("".toRequestBody())
                .build(),
        ).execute().close()

        assertEquals(2, server.requestCount)
    }
}
