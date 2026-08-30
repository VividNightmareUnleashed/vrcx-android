package com.vrcx.android.data.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.security.CookiesRead
import com.vrcx.android.data.security.SecureSecretsStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doThrow
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
        legacyPrefs().edit().clear().commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = true))
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(true)
    }

    @Test
    fun `construction defers encrypted storage access until the jar is used`() {
        val jar = CookieJarImpl(context, secureSecretsStore)

        verify(secureSecretsStore, never()).readCookiesByHost()

        jar.getAuthCookie()
        jar.snapshot()

        verify(secureSecretsStore).readCookiesByHost()
    }

    @Test
    fun `legacy cookie preferences are drained into the encrypted store on first access`() {
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
    fun `legacy cookie preferences remain until encrypted migration is confirmed`() {
        val serialized = StoredCookieCodec.serialize(authCookie())
        legacyPrefs().edit().putString("api.vrchat.cloud", serialized).commit()
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(false)

        val jar = CookieJarImpl(context, secureSecretsStore)

        assertEquals("authcookie_test", jar.getAuthCookie())
        assertFalse(legacyPrefs().all.isEmpty())
        assertEquals(CookieStorageStatus.WRITE_FAILED, jar.storageStatus.value)
    }

    @Test
    fun `an unreadable encrypted cookie record is explicit and leaves legacy data untouched`() {
        val serialized = StoredCookieCodec.serialize(authCookie(value = "legacy"))
        legacyPrefs().edit().putString("api.vrchat.cloud", serialized).commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = false))

        val jar = CookieJarImpl(context, secureSecretsStore)

        assertNull(jar.getAuthCookie())
        assertEquals(CookieStorageStatus.UNREADABLE, jar.storageStatus.value)
        assertFalse(legacyPrefs().all.isEmpty())
        verify(secureSecretsStore, never()).replaceCookiesByHost(any())
    }

    @Test
    fun `an authenticated session deliberately replaces an unreadable old cookie blob`() {
        val oldCookie = StoredCookieCodec.serialize(authCookie(value = "old"))
        legacyPrefs().edit().putString("api.vrchat.cloud", oldCookie).commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = false))
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(false)
        val jar = CookieJarImpl(context, secureSecretsStore)

        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "new")))

        assertTrue(jar.commitAuthenticatedSession())
        verify(secureSecretsStore).establishAuthenticatedCookies(
            mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie(value = "new"))),
        )
        assertEquals(CookieStorageStatus.READY, jar.storageStatus.value)
        assertTrue(legacyPrefs().all.isEmpty())
    }

    @Test
    fun `a failed authenticated cookie commit is explicit and keeps legacy recovery data`() {
        val oldCookie = StoredCookieCodec.serialize(authCookie(value = "old"))
        legacyPrefs().edit().putString("api.vrchat.cloud", oldCookie).commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = false))
        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(false)
        doThrow(RuntimeException("disk full"))
            .whenever(secureSecretsStore)
            .establishAuthenticatedCookies(any())
        val jar = CookieJarImpl(context, secureSecretsStore)

        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "new")))

        assertFalse(jar.commitAuthenticatedSession())
        assertEquals(CookieStorageStatus.WRITE_FAILED, jar.storageStatus.value)
        assertFalse(legacyPrefs().all.isEmpty())
    }

    @Test
    fun `legacy cookies are cleared after encrypted logout deletion is confirmed`() {
        val serialized = StoredCookieCodec.serialize(authCookie(value = "legacy"))
        legacyPrefs().edit().putString("api.vrchat.cloud", serialized).commit()
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = emptyMap(), isReadable = false))
        val jar = CookieJarImpl(context, secureSecretsStore)

        assertTrue(jar.completeLogoutAfterSecretsDeleted())

        assertTrue(legacyPrefs().all.isEmpty())
        assertEquals(CookieStorageStatus.READY, jar.storageStatus.value)
        assertNull(jar.getAuthCookie())
        verify(secureSecretsStore, never()).readCookiesByHost()
    }

    @Test
    fun `only an unexpired cookie named auth from a vrchat host counts as a session`() {
        storedCookies(
            "api.vrchat.cloud" to listOf(authCookie(value = "expired", expiresAt = PAST)),
            "images.example.com" to
                listOf(authCookie(domain = "images.example.com", value = "foreign")),
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
            ),
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
        whenever(
            secureSecretsStore.replaceCookiesByHost(any()),
        ).thenThrow(RuntimeException("keystore"))

        jar.saveFromResponse(requestUrl, listOf(authCookie()))
        assertEquals(CookieStorageStatus.WRITE_FAILED, jar.storageStatus.value)

        whenever(secureSecretsStore.replaceCookiesByHost(any())).thenReturn(true)
        jar.saveFromResponse(requestUrl, listOf(authCookie()))
        assertEquals(CookieStorageStatus.READY, jar.storageStatus.value)

        // The first write never landed, so it must not be short-circuited away as
        // "already persisted" — that leaves a healthy-looking session that is gone
        // on the next launch.
        verify(secureSecretsStore, times(2))
            .replaceCookiesByHost(
                mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie())),
            )
    }

    @Test
    fun `a cookie value containing the record delimiters survives a reload`() {
        val jar = CookieJarImpl(context, secureSecretsStore)
        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "a|b; c%d")))

        val persisted = argumentCaptor<Map<String, String>>()
        verify(secureSecretsStore).replaceCookiesByHost(persisted.capture())
        whenever(secureSecretsStore.readCookiesByHost())
            .thenReturn(CookiesRead(cookiesByHost = persisted.firstValue, isReadable = true))

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

    @Test
    fun `an old response cannot overwrite cookies established after a session boundary`() {
        val jar = CookieJarImpl(context, secureSecretsStore)
        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "old-account")))
        val oldGeneration = jar.sessionGeneration()

        jar.clearAll()
        val newGeneration = jar.sessionGeneration()
        assertTrue(
            jar.saveFromResponseIfCurrent(
                newGeneration,
                requestUrl,
                listOf(authCookie(value = "new-account")),
            ),
        )

        assertFalse(
            jar.saveFromResponseIfCurrent(
                oldGeneration,
                requestUrl,
                listOf(authCookie(value = "late-old-account")),
            ),
        )
        assertEquals("new-account", jar.getAuthCookie())
    }

    @Test
    fun `restoring a previous session invalidates responses from the failed login attempt`() {
        val jar = CookieJarImpl(context, secureSecretsStore)
        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "previous-session")))
        val snapshot = jar.snapshot()

        jar.clearAll()
        val failedLoginGeneration = jar.sessionGeneration()
        jar.restore(snapshot)

        assertFalse(
            jar.saveFromResponseIfCurrent(
                failedLoginGeneration,
                requestUrl,
                listOf(authCookie(value = "late-failed-login")),
            ),
        )
        assertEquals("previous-session", jar.getAuthCookie())
    }

    @Test
    fun `an account-bound snapshot does not follow later cookie mutations`() {
        storedCookies("api.vrchat.cloud" to listOf(authCookie(value = "old")))
        val jar = CookieJarImpl(context, secureSecretsStore)
        val snapshot = jar.snapshotForAccountBoundRequest()

        jar.saveFromResponse(requestUrl, listOf(authCookie(value = "new")))

        assertEquals("auth=old", snapshot.headerFor(requestUrl))
        assertEquals("auth=new", jar.snapshotForAccountBoundRequest().headerFor(requestUrl))
    }

    private fun storedCookies(vararg hosts: Pair<String, List<Cookie>>) {
        whenever(secureSecretsStore.readCookiesByHost()).thenReturn(
            CookiesRead(
                cookiesByHost = hosts.associate { (host, cookies) ->
                    host to cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
                },
                isReadable = true,
            ),
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
