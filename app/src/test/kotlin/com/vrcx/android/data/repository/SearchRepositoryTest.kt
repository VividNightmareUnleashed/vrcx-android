package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class SearchRepositoryTest {
    private val userApi = mock<UserApi>()
    private val worldApi = mock<WorldApi>()
    private val avatarApi = mock<AvatarApi>()
    private val groupApi = mock<GroupApi>()

    private lateinit var server: MockWebServer

    // Stands in for the authenticated API client: it attaches the VRChat session
    // cookie and the header the app's own interceptors add, so a remote-provider
    // request that leaks either shows up in the recorded request.
    private val connectionPool = ConnectionPool()
    private val sessionCookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
            Cookie.Builder().name("auth").value("authcookie_test").domain(url.host).build()
        )

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }
    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .cookieJar(sessionCookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Api-Client", "vrcx").build())
        }
        .build()

    private val repository = SearchRepository(
        userApi = userApi,
        worldApi = worldApi,
        avatarApi = avatarApi,
        groupApi = groupApi,
        okHttpClient = okHttpClient,
        json = Json { ignoreUnknownKeys = true },
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `remote avatar requests carry no VRChat session or API interceptors`() {
        runBlocking {
            server.enqueue(jsonResponse("[]"))

            repository.searchRemoteAvatars("test", server.url("/provider").toString())

            // The provider URL is whatever the user typed, so nothing that
            // identifies the VRChat session may ride along with the request.
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("X-Api-Client"))
        }
    }

    @Test
    fun `the remote avatar client reuses the base client connection pool`() {
        runBlocking {
            server.enqueue(jsonResponse("[]"))

            repository.searchRemoteAvatars("test", server.url("/provider").toString())

            // Derived with newBuilder(), not a fresh Builder — otherwise the
            // remote client gets its own pool, dispatcher and timeouts.
            assertEquals(1, connectionPool.connectionCount())
        }
    }

    @Test
    fun `remote avatar provider failures surface as errors`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("failure"))

            val error = runCatching {
                repository.searchRemoteAvatars("test", server.url("/provider").toString())
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("HTTP 500"))
        }
    }

    @Test
    fun `remote avatar request and parser share the same result cap`() {
        runBlocking {
            server.enqueue(jsonResponse("[]"))

            repository.searchRemoteAvatars("needle", server.url("/provider").toString())

            val url = server.takeRequest().requestUrl!!
            assertEquals("needle", url.queryParameter("search"))
            assertEquals("1000", url.queryParameter("n"))
        }
    }

    @Test
    fun `invalid remote avatar provider URLs fail fast`() {
        runBlocking {
            val error = runCatching {
                repository.searchRemoteAvatars("test", "not-a-url")
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("Enter a valid remote avatar provider URL.", error?.message)
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
