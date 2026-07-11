package com.vrcx.android.data.api

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `rejects malformed cookie records`() {
        assertNull(StoredCookieCodec.deserialize("; domain"))
    }
}
