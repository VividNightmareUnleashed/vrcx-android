package com.vrcx.android.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Invocation

class ErrorInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var bus: AuthEventBus
    private lateinit var retryDelaysMs: MutableList<Long>
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        bus = AuthEventBus()
        retryDelaysMs = mutableListOf()
        // Record the retry wait instead of serving it: the assertion becomes the
        // exact delay the interceptor chose, and the suite doesn't sleep for it.
        client = OkHttpClient.Builder()
            .addInterceptor(ErrorInterceptor(bus) { retryDelaysMs += it })
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
    fun `401 from basic auth request does not emit Unauthorized`() {
        val collected = collectEvents()
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad credentials"))

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/auth/user"))
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .build(),
        ).execute()

        assertEquals(401, response.code)
        assertEquals(0, collected().size)
        response.close()
    }

    @Test
    fun `401 from any two factor endpoint does not expire the session`() {
        // A wrong code on any of the three methods is the user's mistake, not an
        // expired session — the challenge has to survive it.
        for (methodName in listOf("verifyTotp", "verifyOtp", "verifyEmailOtp")) {
            val collected = collectEvents()
            server.enqueue(MockResponse().setResponseCode(401).setBody("bad code"))

            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/auth/twofactorauth/verify"))
                    .tag(Invocation::class.java, invocationFor(methodName))
                    .build(),
            ).execute()

            assertEquals(401, response.code)
            assertEquals(methodName, 0, collected().size)
            response.close()
        }
    }

    @Test
    fun `401 from a non-AuthPhase endpoint expires the session`() {
        val collected = collectEvents()
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

        // getAuthToken() is a post-login endpoint with no @AuthPhase marker, so a
        // 401 there is genuine session expiry and must reach the bus.
        val response = client.newCall(
            Request.Builder()
                .url(server.url("/auth"))
                .tag(Invocation::class.java, invocationFor("getAuthToken"))
                .build(),
        ).execute()

        assertEquals(401, response.code)
        assertEquals(1, collected().size)
        assertTrue(collected().first() is AuthEvent.Unauthorized)
        response.close()
    }

    /**
     * Builds the Retrofit [Invocation] tag that would accompany a real call to
     * the named [AuthApi] method, so the interceptor can read its @AuthPhase
     * annotation exactly as it does in production.
     */
    @Suppress("DEPRECATION") // Invocation.of(Method, List) is the only public factory in Retrofit 2.11.
    private fun invocationFor(methodName: String): Invocation {
        val method = AuthApi::class.java.declaredMethods.first { it.name == methodName }
        return Invocation.of(method, emptyList<Any>())
    }

    @Test
    fun `429 retries once with Retry-After and respects the cap`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        // Retry-After: 1 → 1000ms, under the 2000ms cap.
        assertEquals(listOf(1_000L), retryDelaysMs)
        assertEquals(2, server.requestCount)
        response.close()
    }

    @Test
    fun `429 without Retry-After uses default 1s delay`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(ErrorInterceptor.DEFAULT_RETRY_DELAY_MS), retryDelaysMs)
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

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(ErrorInterceptor.MAX_RETRY_DELAY_MS), retryDelaysMs)
        response.close()
    }

    @Test
    fun `Retry-After Long max is clamped without overflowing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", Long.MAX_VALUE.toString()),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(ErrorInterceptor.MAX_RETRY_DELAY_MS), retryDelaysMs)
        response.close()
    }

    @Test
    fun `Retry-After outside Long range uses the default delay`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "999999999999999999999999999999"),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(ErrorInterceptor.DEFAULT_RETRY_DELAY_MS), retryDelaysMs)
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

    /**
     * Collects events emitted to the bus during a single test. The bus keeps no
     * replay, so anything emitted before the collector subscribes is gone —
     * wait for the subscription itself rather than guessing at how long it takes.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun collectEvents(): () -> List<AuthEvent> {
        val received = Channel<AuthEvent>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val job = GlobalScope.launch {
            bus.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { received.trySend(it) }
        }
        runBlocking { withTimeout(SUBSCRIBE_TIMEOUT_MS) { subscribed.await() } }
        val collected = mutableListOf<AuthEvent>()
        var drained = false
        return {
            if (!drained) {
                drained = true
                runBlocking {
                    while (true) {
                        collected += withTimeoutOrNull(DRAIN_TIMEOUT_MS) { received.receive() } ?: break
                    }
                }
                job.cancel()
            }
            collected
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        const val DRAIN_TIMEOUT_MS = 200L
    }
}
