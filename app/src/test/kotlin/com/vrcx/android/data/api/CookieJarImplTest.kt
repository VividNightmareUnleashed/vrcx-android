package com.vrcx.android.data.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.security.SecureSecretsStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CookieJarImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secureSecretsStore = mock<SecureSecretsStore>()
    private val requestUrl = "https://api.vrchat.cloud/api/1/auth/user".toHttpUrl()

    @Before
    fun setUp() {
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(true)
    }

    @Test
    fun `legacy cookie preferences are drained into the encrypted store on first construction`() {
        val serialized = StoredCookieCodec.serialize(authCookie())
        legacyPrefs().edit().putString("api.vrchat.cloud", serialized).commit()

        val jar = CookieJarImpl(context, secureSecretsStore)

        // Every install upgrading from the SharedPreferences era depends on this
        // one-way drain; without it they all land back on the login screen.
        assertEquals("authcookie_test", jar.getAuthCookie())
        verify(secureSecretsStore).replaceCookiesByHost(mapOf("api.vrchat.cloud" to serialized))
        assertTrue(legacyPrefs().all.isEmpty())
    }

    @Test
    fun `only an unexpired cookie named auth from a vrchat host counts as a session`() {
        storedCookies(
            "api.vrchat.cloud" to listOf(authCookie(value = "expired", expiresAt = PAST)),
            "images.example.com" to listOf(authCookie(domain = "images.example.com", value = "foreign")),
        )

        assertNull(CookieJarImpl(context, secureSecretsStore).getAuthCookie())

        storedCookies("api.vrchat.cloud" to listOf(authCookie()))

        assertEquals("authcookie_test", CookieJarImpl(context, secureSecretsStore).getAuthCookie())
    }

    @Test
    fun `an expired cookie is withheld from the request without being written out of the store`() {
        storedCookies(
            "api.vrchat.cloud" to listOf(
                authCookie(),
                authCookie(name = "twoFactorAuth", value = "stale", expiresAt = PAST),
            )
        )
        val jar = CookieJarImpl(context, secureSecretsStore)
        clearInvocations(secureSecretsStore)

        val sent = jar.loadForRequest(requestUrl)

        assertEquals(listOf("auth"), sent.map { it.name })
        // A device whose clock has jumped forward would otherwise destroy the
        // stored session on the next request, with no way back.
        verify(secureSecretsStore, never()).replaceCookiesByHost(any())
    }

    @Test
    fun `a failed encrypted write neither escapes the call nor blocks the next attempt`() {
        val jar = CookieJarImpl(context, secureSecretsStore)
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenThrow(RuntimeException("keystore"))

        jar.saveFromResponse(requestUrl, listOf(authCookie()))

        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(true)
        jar.saveFromResponse(requestUrl, listOf(authCookie()))

        // The first write never landed, so it must not be short-circuited away as
        // "already persisted" — that leaves a healthy-looking session that is gone
        // on the next launch.
        verify(secureSecretsStore, times(2))
            .replaceCookiesByHost(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie())))
    }

    @Test
    fun `a cookie value containing the record delimiters survives a reload`() {
        val jar = CookieJarImpl(context, secureSecretsStore)
        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "a|b; c%d")))

        val persisted = argumentCaptor<Map<String, String>>()
        verify(secureSecretsStore).replaceCookiesByHost(persisted.capture())
        whenever(secureSecretsStore.getCookiesByHost()).thenReturn(persisted.firstValue)

        assertEquals("a|b; c%d", CookieJarImpl(context, secureSecretsStore).getAuthCookie())
    }

    @Test
    fun `a cleared jar can be restored after a sign-in attempt that never resolved`() {
        storedCookies("api.vrchat.cloud" to listOf(authCookie()))
        val jar = CookieJarImpl(context, secureSecretsStore)
        val snapshot = jar.snapshot()

        jar.clearAll()
        assertNull(jar.getAuthCookie())

        jar.restore(snapshot)
        assertEquals("authcookie_test", jar.getAuthCookie())
    }

    private fun storedCookies(vararg hosts: Pair<String, List<Cookie>>) {
        whenever(secureSecretsStore.getCookiesByHost()).thenReturn(
            hosts.associate { (host, cookies) ->
                host to cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
            }
        )
    }

    private fun legacyPrefs() = context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)

    private fun authCookie(
        name: String = "auth",
        value: String = "authcookie_test",
        domain: String = "api.vrchat.cloud",
        expiresAt: Long = FUTURE,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path("/")
        .expiresAt(expiresAt)
        .build()

    private companion object {
        const val PAST = 1_000_000_000_000L
        const val FUTURE = 4_000_000_000_000L
    }
}
