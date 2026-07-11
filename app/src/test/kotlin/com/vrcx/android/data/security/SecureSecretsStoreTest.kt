package com.vrcx.android.data.security

import com.vrcx.android.data.api.StoredCookieCodec
import okhttp3.Cookie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSecretsStoreTest {
    @Test
    fun `boot eligibility requires an exact unexpired auth cookie`() {
        val now = 1_900_000_000_000L
        val expiredAuth = cookie("auth", "old", now - 1)
        val misleadingName = cookie("notauth", "auth=inside-value", now + 10_000)
        val validAuth = cookie("auth", "token", now + 10_000)

        assertFalse(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(expiredAuth)), now))
        assertFalse(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(misleadingName)), now))
        assertTrue(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(validAuth)), now))
    }

    private fun cookie(name: String, value: String, expiresAt: Long): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain("api.vrchat.cloud")
        .path("/")
        .expiresAt(expiresAt)
        .build()
}
