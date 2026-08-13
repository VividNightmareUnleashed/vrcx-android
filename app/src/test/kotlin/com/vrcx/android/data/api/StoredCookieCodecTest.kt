package com.vrcx.android.data.api

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredCookieCodecTest {
    @Test
    fun `round trips cookie identity and expiry`() {
        val cookie = Cookie.Builder()
            .name("auth")
            .value("token")
            .domain("api.vrchat.cloud")
            .path("/")
            .expiresAt(2_000_000_000_000)
            .secure()
            .httpOnly()
            .build()

        val decoded = StoredCookieCodec.deserialize(StoredCookieCodec.serialize(cookie))!!

        assertEquals(cookie.name, decoded.name)
        assertEquals(cookie.value, decoded.value)
        assertEquals(cookie.expiresAt, decoded.expiresAt)
        assertEquals(cookie.domain, decoded.domain)
    }

    @Test
    fun `round trips values containing the record delimiters`() {
        val cookie = Cookie.Builder()
            .name("auth")
            .value("a|b; c%d%25e")
            .domain("api.vrchat.cloud")
            .path("/")
            .expiresAt(2_000_000_000_000)
            .build()

        val decoded = StoredCookieCodec.deserialize(StoredCookieCodec.serialize(cookie))!!

        // Either sequence used to split the record apart, dropping the cookie on
        // the next load — a sign-out with no explanation.
        assertEquals(cookie.value, decoded.value)
        assertEquals(cookie.name, decoded.name)
    }

    @Test
    fun `keeps a host-only cookie host-only`() {
        val cookie = Cookie.Builder()
            .name("auth")
            .value("token")
            .hostOnlyDomain("api.vrchat.cloud")
            .path("/")
            .expiresAt(2_000_000_000_000)
            .build()

        val decoded = StoredCookieCodec.deserialize(StoredCookieCodec.serialize(cookie))!!

        // Widening it would offer the cookie to subdomains the server never
        // scoped it to.
        assertTrue(decoded.hostOnly)
        assertEquals(cookie.domain, decoded.domain)
    }

    @Test
    fun `reads records written before values were escaped`() {
        val legacy = "auth=authcookie_test; domain=api.vrchat.cloud; path=/; expires=2000000000000"

        val decoded = StoredCookieCodec.deserialize(legacy)!!

        assertEquals("authcookie_test", decoded.value)
        assertFalse(decoded.hostOnly)
    }

    @Test
    fun `rejects malformed cookie records`() {
        assertNull(StoredCookieCodec.deserialize("; domain"))
    }
}
