package com.vrcx.android.data.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.security.CookiesRead
import com.vrcx.android.data.security.SecureSecretsStore
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionCookieInterceptorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secureSecretsStore = mock<SecureSecretsStore>()
    private lateinit var server: MockWebServer
    private lateinit var cookieJar: CookieJarImpl

    @Before
    fun setUp() {
        context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE).edit().clear().commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = true))
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(true)
        server = MockWebServer().apply { start() }
        cookieJar = CookieJarImpl(context, secureSecretsStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `late old-account response cannot replace the new session cookie`() {
        val oldRequestArrived = CountDownLatch(1)
        val releaseOldResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/old" -> {
                    oldRequestArrived.countDown()
                    assertTrue(releaseOldResponse.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    MockResponse().setHeader("Set-Cookie", "auth=late-old-account; Path=/")
                }

                "/new" -> MockResponse().setHeader("Set-Cookie", "auth=new-account; Path=/")

                else -> MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            }
        }
        cookieJar.saveFromResponse(server.url("/"), listOf(cookie("old-account")))
        val oldCall = FutureTask<Unit> {
            client().newCall(Request.Builder().url(server.url("/old")).build()).execute().close()
        }

        thread(name = "old-account-request") {
            oldCall.run()
        }

        assertTrue(oldRequestArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        cookieJar.clearAll()
        client().newCall(Request.Builder().url(server.url("/new")).build()).execute().close()
        assertEquals("new-account", authCookieValue())
        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
        assertNull(server.takeRequest().getHeader("Cookie"))

        releaseOldResponse.countDown()
        oldCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals("new-account", authCookieValue())
    }

    @Test
    fun `request queued in an old session cannot send the new account cookie`() {
        cookieJar.saveFromResponse(server.url("/"), listOf(cookie("old-account")))
        val generationCaptured = CountDownLatch(1)
        val releaseQueuedRequest = CountDownLatch(1)
        val client = client(
            afterGenerationCapture = {
                generationCaptured.countDown()
                assertTrue(releaseQueuedRequest.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            },
        )
        val queuedCall = FutureTask<Unit> {
            client.newCall(Request.Builder().url(server.url("/queued")).build()).execute().close()
        }

        thread(name = "queued-old-account-request") {
            queuedCall.run()
        }

        assertTrue(generationCaptured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        cookieJar.clearAll()
        cookieJar.saveFromResponse(server.url("/"), listOf(cookie("new-account")))
        releaseQueuedRequest.countDown()

        val failure = assertThrows(ExecutionException::class.java) {
            queuedCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is java.io.IOException)
        assertEquals(0, server.requestCount)
        assertEquals("new-account", authCookieValue())
    }

    @Test
    fun `explicit account-bound request keeps its immutable upload cookie policy`() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "auth=late-old-account; Path=/"))
        cookieJar.saveFromResponse(server.url("/"), listOf(cookie("new-account")))
        val request = Request.Builder()
            .url(server.url("/upload"))
            .tag(
                AccountBoundCookies::class.java,
                AccountBoundCookies(mapOf(server.hostName to listOf(cookie("old-account")))),
            )
            .build()

        client().newCall(request).execute().close()

        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
        assertEquals("new-account", authCookieValue())
    }

    private fun client(afterGenerationCapture: (() -> Unit)? = null): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(SessionCookieRequestInterceptor(cookieJar))
        .apply {
            if (afterGenerationCapture != null) {
                addInterceptor { chain ->
                    afterGenerationCapture()
                    chain.proceed(chain.request())
                }
            }
        }
        .addNetworkInterceptor(SessionCookieResponseInterceptor(cookieJar))
        .addNetworkInterceptor(AccountBoundCookieInterceptor())
        .build()

    private fun cookie(value: String): Cookie = Cookie.Builder()
        .name("auth")
        .value(value)
        .hostOnlyDomain(server.hostName)
        .path("/")
        .build()

    private fun authCookieValue(): String? = cookieJar.loadForRequest(server.url("/"))
        .firstOrNull { it.name == "auth" }
        ?.value

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
