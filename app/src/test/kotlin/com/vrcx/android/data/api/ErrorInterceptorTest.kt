package com.vrcx.android.data.api

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErrorInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var bus: AuthEventBus
    private lateinit var emittedEvents: MutableList<AuthEvent>
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        bus = AuthEventBus()
        emittedEvents = mutableListOf()
        // The bus uses a SharedFlow with a buffer; collect events synchronously
        // by snapshotting tryEmit calls through a wrapper.
        client = OkHttpClient.Builder()
            .addInterceptor(ErrorInterceptor(bus))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401 emits Unauthorized on the bus and passes the response through unchanged`() {
        val collected = collectEvents()
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, collected().size)
        assertTrue(collected().first() is AuthEvent.Unauthorized)
        response.close()
    }

    @Test
    fun `429 retries once with Retry-After and respects the cap`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.nanoTime()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(200, response.code)
        // Retry-After: 1 → 1000ms, capped at 2000ms; should be at least 1000 but well below 30s.
        assertTrue("retry waited ${elapsedMs}ms, expected ~1000-2500", elapsedMs in 900..2500)
        assertEquals(2, server.requestCount)
        response.close()
    }

    @Test
    fun `429 without Retry-After uses default 1s delay`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.nanoTime()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(200, response.code)
        assertTrue("retry waited ${elapsedMs}ms, expected ~1000ms", elapsedMs in 900..1800)
        response.close()
    }

    @Test
    fun `429 retries only once - second 429 is returned without further retry`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(429, response.code)
        assertEquals(2, server.requestCount)
        response.close()
    }

    @Test
    fun `429 retry response 401 emits Unauthorized on the bus`() {
        val collected = collectEvents()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, collected().size)
        assertTrue(collected().first() is AuthEvent.Unauthorized)
        response.close()
    }

    @Test
    fun `Retry-After larger than the cap is clamped to 2 seconds`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(MockResponse().setResponseCode(200))

        val start = System.nanoTime()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(200, response.code)
        assertTrue("clamp failed: waited ${elapsedMs}ms", elapsedMs in 1900..3000)
        response.close()
    }

    @Test
    fun `500 is passed through without retry or auth event`() {
        val collected = collectEvents()
        server.enqueue(MockResponse().setResponseCode(500))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(500, response.code)
        assertEquals(0, collected().size)
        assertEquals(1, server.requestCount)
        response.close()
    }

    /** Collects events emitted to the bus during a single test, blocking-free. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun collectEvents(): () -> List<AuthEvent> {
        val collected = mutableListOf<AuthEvent>()
        val job = GlobalScope.launch {
            bus.events.collect { collected += it }
        }
        // Give the collector a tick to subscribe before the test triggers tryEmit.
        Thread.sleep(50)
        return {
            // Drain time after the request completes.
            Thread.sleep(50)
            job.cancel()
            collected.toList()
        }
    }
}
