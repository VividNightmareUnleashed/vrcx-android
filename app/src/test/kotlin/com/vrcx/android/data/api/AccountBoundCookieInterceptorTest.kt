package com.vrcx.android.data.api

import java.net.HttpURLConnection
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AccountBoundCookieInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var cookieJar: RecordingCookieJar

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cookieJar = RecordingCookieJar(currentCookies = listOf(cookie("new-account")))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `bound request sends captured cookies and cannot persist a late response cookie`() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "auth=late-old-account; Path=/"))
        val snapshot = AccountBoundCookies(
            mapOf(server.hostName to listOf(cookie("old-account"))),
        )
        val request = Request.Builder()
            .url(server.url("/upload"))
            .tag(AccountBoundCookies::class.java, snapshot)
            .build()

        client().newCall(request).execute().use { response ->
            assertTrue(response.headers.values("Set-Cookie").isEmpty())
        }

        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
        assertTrue(cookieJar.savedCookies.isEmpty())
    }

    @Test
    fun `empty bound snapshot removes cookies from the live jar`() {
        server.enqueue(MockResponse())
        val request = Request.Builder()
            .url(server.url("/upload"))
            .tag(AccountBoundCookies::class.java, AccountBoundCookies(emptyMap()))
            .build()

        client().newCall(request).execute().close()

        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `redirected bound request keeps the captured cookies`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .setHeader("Location", "/final"),
        )
        server.enqueue(MockResponse())
        val snapshot = AccountBoundCookies(
            mapOf(server.hostName to listOf(cookie("old-account"))),
        )
        val request = Request.Builder()
            .url(server.url("/upload"))
            .tag(AccountBoundCookies::class.java, snapshot)
            .build()

        client().newCall(request).execute().close()

        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `cross-subdomain redirect keeps a captured domain cookie`() {
        val uploadUrl = server.url("/upload").newBuilder()
            .host("api.example.test")
            .build()
        val finalUrl = server.url("/final").newBuilder()
            .host("cdn.example.test")
            .build()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .setHeader("Location", finalUrl),
        )
        server.enqueue(MockResponse())
        val domainCookie = Cookie.Builder()
            .name("auth")
            .value("old-account")
            .domain("example.test")
            .path("/")
            .build()
        val request = Request.Builder()
            .url(uploadUrl)
            .tag(
                AccountBoundCookies::class.java,
                AccountBoundCookies(mapOf("api.example.test" to listOf(domainCookie))),
            )
            .build()

        client().newCall(request).execute().close()

        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `Retrofit upload tag reaches the account-bound interceptor`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"file_test\"}"),
        )
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client())
            .addConverterFactory(
                Json.asConverterFactory("application/json".toMediaType()),
            )
            .build()
            .create(GalleryApi::class.java)
        val accountCookies = AccountBoundCookies(
            mapOf(server.hostName to listOf(cookie("old-account"))),
        )
        val image = MultipartBody.Part.createFormData(
            "file",
            "image.png",
            byteArrayOf().toRequestBody("image/png".toMediaType()),
        )

        val uploaded = api.uploadFile(
            accountCookies,
            "gallery".toRequestBody("text/plain".toMediaType()),
            image,
        )

        assertEquals("file_test", uploaded.id)
        assertEquals("auth=old-account", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `ordinary requests keep the shared cookie jar behavior`() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "auth=refreshed; Path=/"))

        client().newCall(Request.Builder().url(server.url("/ordinary")).build())
            .execute()
            .close()

        assertEquals("auth=new-account", server.takeRequest().getHeader("Cookie"))
        assertEquals(listOf("refreshed"), cookieJar.savedCookies.map { cookie -> cookie.value })
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
            },
        )
        .cookieJar(cookieJar)
        .addNetworkInterceptor(AccountBoundCookieInterceptor())
        .build()

    private fun cookie(value: String): Cookie = Cookie.Builder()
        .name("auth")
        .value(value)
        .hostOnlyDomain(server.hostName)
        .path("/")
        .build()

    private class RecordingCookieJar(private val currentCookies: List<Cookie>) : CookieJar {
        val savedCookies = mutableListOf<Cookie>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> = currentCookies

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            savedCookies += cookies
        }
    }
}
