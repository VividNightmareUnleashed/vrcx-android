package com.vrcx.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthInterceptorTest {
    private val interceptor = AuthInterceptor()

    @Test
    fun `encodeURIComponent matches desktop-compatible escaping`() {
        assertEquals("hello%20world", interceptor.encodeURIComponent("hello world"))
        assertEquals("email%2Balias%40example.com", interceptor.encodeURIComponent("email+alias@example.com"))
        assertEquals("keep!*'()~safe", interceptor.encodeURIComponent("keep!*'()~safe"))
    }

    @Test
    fun `encodeBasicAuth keeps base64 padding to match desktop btoa`() {
        // 1-byte tail produces "==" padding.
        // user "a" pass "b" -> "a:b" (3 bytes, divisible by 3, no padding needed).
        // user "ab" pass "" -> "ab:" (3 bytes, no padding).
        // user "ab" pass "c" -> "ab:c" (4 bytes, 2 bytes tail -> "=" padding).
        assertEquals("YWI6Yw==", interceptor.encodeBasicAuth("ab", "c"))

        // user "a" pass "" -> "a:" (2 bytes, 2 bytes tail -> "=" padding).
        assertEquals("YTo=", interceptor.encodeBasicAuth("a", ""))

        // user "vrcx" pass "test" -> "vrcx:test" (9 bytes, divisible by 3, no padding).
        assertEquals("dnJjeDp0ZXN0", interceptor.encodeBasicAuth("vrcx", "test"))
    }

    @Test
    fun `encodeBasicAuth url-encodes credentials before base64 like desktop`() {
        // "user@example.com" -> "user%40example.com", ":pass!" -> ":pass!" (! is preserved)
        // Combined: "user%40example.com:pass!" (24 bytes, divisible by 3, no padding)
        val encoded = interceptor.encodeBasicAuth("user@example.com", "pass!")
        // Base64 of "user%40example.com:pass!"
        assertEquals("dXNlciU0MGV4YW1wbGUuY29tOnBhc3Mh", encoded)
    }
}
