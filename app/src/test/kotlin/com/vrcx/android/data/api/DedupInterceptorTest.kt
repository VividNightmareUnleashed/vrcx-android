package com.vrcx.android.data.api

import com.vrcx.android.directTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DedupInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var deduplicator: RequestDeduplicator
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        deduplicator = RequestDeduplicator(directTestDispatcher)
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
    fun `clearing the cache stops the short-circuit`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        client.newCall(Request.Builder().url(server.url("/missing")).build()).execute().close()
        deduplicator.clearCache()
        val afterClear = client.newCall(Request.Builder().url(server.url("/missing")).build()).execute()

        // Sign-out clears the cache so the next account is not shown the
        // previous one's missing resources.
        assertEquals(2, server.requestCount)
        assertNull(afterClear.header("X-VRCX-Cached-Failure"))
        afterClear.close()
    }

    @Test
    fun `does not cache 403 GET responses`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))

        client.newCall(Request.Builder().url(server.url("/forbidden")).build()).execute().close()
        val second = client.newCall(Request.Builder().url(server.url("/forbidden")).build()).execute()

        // A 403 is about a permission the user can change from inside the app —
        // joining the group has to be enough to make its posts load.
        assertEquals(403, second.code)
        assertEquals(2, server.requestCount)
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

    @Test
    fun `a failing auth endpoint never seals the sign-in path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(
                Json.asConverterFactory("application/json".toMediaType()),
            )
            .build()
            .create(AuthApi::class.java)

        // Nothing clears the failure cache while signed out, so a cached failure
        // here would block every later attempt without a network round trip.
        repeat(2) {
            try {
                api.getCurrentUser()
            } catch (_: HttpException) {
                // Expected: the 404 has to reach the caller, not the cache.
            }
        }

        assertEquals(2, server.requestCount)
    }
}
