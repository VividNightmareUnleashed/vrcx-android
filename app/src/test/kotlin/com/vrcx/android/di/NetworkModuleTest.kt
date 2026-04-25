package com.vrcx.android.di

import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.DedupInterceptor
import com.vrcx.android.data.api.ErrorInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

class NetworkModuleTest {
    @Test
    fun `image client keeps auth cookies without API error handling`() {
        val cookieJar = mock<CookieJarImpl>()

        val client = NetworkModule.provideImageOkHttpClient(cookieJar)

        assertSame(cookieJar, client.cookieJar)
        assertFalse(client.interceptors.any { it is ErrorInterceptor })
        assertFalse(client.interceptors.any { it is DedupInterceptor })
    }
}
