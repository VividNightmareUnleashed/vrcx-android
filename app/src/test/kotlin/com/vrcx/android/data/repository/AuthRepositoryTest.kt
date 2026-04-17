package com.vrcx.android.data.repository

import android.content.Context
import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthInterceptor
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.AuthToken
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.preferences.VrcxPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthRepositoryTest {
    private val authApi = mock<AuthApi>()
    private val authInterceptor = mock<AuthInterceptor>()
    private val cookieJar = mock<CookieJarImpl>()
    private val preferences = mock<VrcxPreferences>()
    private val dedup = mock<RequestDeduplicator>()
    private val favoriteRepository = mock<FavoriteRepository>()
    private val context = mock<Context>()

    private val repository = AuthRepository(
        authApi = authApi,
        authInterceptor = authInterceptor,
        cookieJar = cookieJar,
        preferences = preferences,
        json = Json { ignoreUnknownKeys = true },
        dedup = dedup,
        favoriteRepository = favoriteRepository,
        context = context,
    )

    @Test
    fun `eight digit recovery codes use otp verification endpoint`() {
        runBlocking {
            whenever(authApi.verifyOtp(any())).thenReturn(TwoFactorAuthResponse(verified = true))
            stubLoginFollowUp()

            repository.verifyTotp("12345678")

            val requestCaptor = argumentCaptor<TwoFactorAuthRequest>()
            verify(authApi).verifyOtp(requestCaptor.capture())
            verify(authApi, never()).verifyTotp(any())
            assertEquals("1234-5678", requestCaptor.firstValue.code)
        }
    }

    @Test
    fun `authenticator codes keep using totp verification endpoint`() {
        runBlocking {
            whenever(authApi.verifyTotp(any())).thenReturn(TwoFactorAuthResponse(verified = true))
            stubLoginFollowUp()

            repository.verifyTotp("123456")

            val requestCaptor = argumentCaptor<TwoFactorAuthRequest>()
            verify(authApi).verifyTotp(requestCaptor.capture())
            verify(authApi, never()).verifyOtp(any())
            assertEquals("123456", requestCaptor.firstValue.code)
        }
    }

    @Test
    fun `logout invalidates the session server-side before clearing local state`() {
        runBlocking {
            repository.logout()

            verify(authApi).logout()
            verify(cookieJar).clearAll()
            verify(authInterceptor).clearBasicAuth()
            verify(dedup).clearCache()
            verify(favoriteRepository).clearRuntimeState()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `logout still clears local state when the server call fails`() {
        runBlocking {
            // Suspend functions don't declare checked exceptions, so Mockito only
            // accepts unchecked Throwables here. RuntimeException stands in for the
            // network-failure case.
            authApi.stub {
                onBlocking { logout() } doThrow RuntimeException("offline")
            }

            repository.logout()

            verify(authApi).logout()
            verify(cookieJar).clearAll()
            verify(authInterceptor).clearBasicAuth()
            verify(dedup).clearCache()
            verify(favoriteRepository).clearRuntimeState()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    private suspend fun stubLoginFollowUp() {
        whenever(authApi.getCurrentUser()).thenReturn(
            buildJsonObject {
                put("id", "usr_test")
                put("displayName", "Test User")
            }
        )
        whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
    }
}
