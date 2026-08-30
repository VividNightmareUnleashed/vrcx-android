package com.vrcx.android.di

import com.vrcx.android.data.api.AccountBoundCookieInterceptor
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.DedupInterceptor
import com.vrcx.android.data.api.ErrorInterceptor
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.SessionCookieRequestInterceptor
import com.vrcx.android.data.api.SessionCookieResponseInterceptor
import com.vrcx.android.data.websocket.PipelineOkHttpClient
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.directTestDispatcher
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class NetworkModuleTest {
    @Test
    fun `image client keeps auth cookies without API error handling`() {
        val cookieJar = mock<CookieJarImpl>()

        val client = imageClient(cookieJar)

        assertSame(cookieJar, client.cookieJar)
        assertTrue(client.interceptors.any { it is SessionCookieRequestInterceptor })
        assertTrue(client.networkInterceptors.any { it is SessionCookieResponseInterceptor })
        assertFalse(client.interceptors.any { it is ErrorInterceptor })
        assertFalse(client.interceptors.any { it is DedupInterceptor })
    }

    @Test
    fun `websocket client cannot log the pipeline auth token or carry the session`() {
        val client = NetworkModule.provideWebSocketOkHttpClient().client

        // The pipeline token travels in the connection URL, so any logging
        // interceptor on this client writes a live session token to logcat.
        assertFalse(client.interceptors.any { it is HttpLoggingInterceptor })
        assertFalse(client.networkInterceptors.any { it is HttpLoggingInterceptor })
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        // A pipeline socket stays open indefinitely; a read timeout would kill it.
        assertEquals(0, client.readTimeoutMillis)
    }

    @Test
    fun `the separate clients still pool connections to the same host together`() {
        val cookieJar = mock<CookieJarImpl>()

        val api = NetworkModule.provideOkHttpClient(
            cookieJar,
            AuthEventBus(),
            RequestDeduplicator(directTestDispatcher),
            AccountBoundCookieInterceptor(),
        )
        assertTrue(api.interceptors.any { it is SessionCookieRequestInterceptor })
        assertTrue(api.networkInterceptors.any { it is SessionCookieResponseInterceptor })

        // User images come from the API host, so they should ride the API
        // client's warm TLS connections rather than handshake again.
        assertSame(
            api.connectionPool,
            imageClient(cookieJar).connectionPool,
        )
        assertSame(
            api.connectionPool,
            NetworkModule.provideWebSocketOkHttpClient().client.connectionPool,
        )
    }

    @Test
    fun `only the pipeline client can be handed to the socket`() {
        // The guarantees above are worth nothing if some other OkHttpClient can
        // reach the socket. PipelineOkHttpClient is the only type the module
        // publishes for it and the only one VRChatWebSocket accepts, so the
        // wrong client is a compile error rather than a token in logcat.
        val provided: PipelineOkHttpClient = NetworkModule.provideWebSocketOkHttpClient()

        VRChatWebSocket(Json, provided, directTestDispatcher).disconnect()
    }

    private fun imageClient(cookieJar: CookieJarImpl) = NetworkModule.provideImageOkHttpClient(cookieJar)
}
