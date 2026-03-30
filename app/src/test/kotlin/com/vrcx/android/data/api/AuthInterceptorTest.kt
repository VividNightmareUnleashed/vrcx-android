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
}
