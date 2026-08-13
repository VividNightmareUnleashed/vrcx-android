package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthInterceptor
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.AuthToken
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
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
    private val secureSecretsStore = mock<SecureSecretsStore>()
    private val dedup = mock<RequestDeduplicator>()
    private val favoriteRepository = mock<FavoriteRepository>()

    private val avatarRepository = mock<AvatarRepository>()
    private val friendRepository = mock<FriendRepository>()
    private val galleryRepository = mock<GalleryRepository>()
    private val groupRepository = mock<GroupRepository>()
    private val moderationRepository = mock<ModerationRepository>()
    private val notificationRepository = mock<NotificationRepository>()
    private val userRepository = mock<UserRepository>()
    private val worldRepository = mock<WorldRepository>()

    private val accountScope = AccountScope()

    private val repository = AuthRepository(
        authApi = authApi,
        authInterceptor = authInterceptor,
        cookieJar = cookieJar,
        preferences = preferences,
        secureSecretsStore = secureSecretsStore,
        json = Json { ignoreUnknownKeys = true },
        dedup = dedup,
        accountScope = accountScope,
    )

    init {
        wireAccountScopedRepositories()
    }

    /**
     * Binds the same sibling repositories the scope holds on device. Without
     * this the account-scoped clears have nothing to reach and every teardown
     * assertion below would pass against a repository that resets nothing.
     */
    private fun wireAccountScopedRepositories() {
        listOf(
            avatarRepository,
            friendRepository,
            galleryRepository,
            groupRepository,
            moderationRepository,
            notificationRepository,
            userRepository,
            worldRepository,
            favoriteRepository,
        ).forEach(accountScope::bindTo)
    }

    /**
     * Ending a session has to reset every repository that holds data belonging to
     * the account that is going away, or the next account sees the previous one's
     * friends, notifications, groups, gallery and cached users.
     */
    private suspend fun assertAccountScopedStateCleared() {
        verify(avatarRepository, atLeastOnce()).clearRuntimeState()
        verify(friendRepository, atLeastOnce()).clearRuntimeState()
        verify(galleryRepository, atLeastOnce()).clearRuntimeState()
        verify(groupRepository, atLeastOnce()).clearRuntimeState()
        verify(moderationRepository, atLeastOnce()).clearRuntimeState()
        verify(notificationRepository, atLeastOnce()).clearRuntimeState()
        verify(userRepository, atLeastOnce()).clearRuntimeState()
        verify(worldRepository, atLeastOnce()).clearRuntimeState()
        verify(favoriteRepository, atLeastOnce()).clearRuntimeState()
    }

    @Test
    fun `eight digit recovery codes use otp verification endpoint`() {
        runBlocking {
            enterTwoFactorState()
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
            enterTwoFactorState()
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
    fun `email only accounts verify through the email otp endpoint`() {
        runBlocking {
            enterTwoFactorState("emailOtp")
            whenever(authApi.verifyEmailOtp(any())).thenReturn(TwoFactorAuthResponse(verified = true))
            stubLoginFollowUp()

            repository.verifyEmailOtp("123456")

            val requestCaptor = argumentCaptor<TwoFactorAuthRequest>()
            verify(authApi).verifyEmailOtp(requestCaptor.capture())
            verify(authApi, never()).verifyTotp(any())
            verify(authApi, never()).verifyOtp(any())
            assertEquals("123456", requestCaptor.firstValue.code)
        }
    }

    @Test
    fun `alphanumeric recovery codes keep their letters and use the otp endpoint`() {
        runBlocking {
            enterTwoFactorState()
            whenever(authApi.verifyOtp(any())).thenReturn(TwoFactorAuthResponse(verified = true))
            stubLoginFollowUp()

            repository.verifyTotp("ab12cd34")

            val requestCaptor = argumentCaptor<TwoFactorAuthRequest>()
            verify(authApi).verifyOtp(requestCaptor.capture())
            verify(authApi, never()).verifyTotp(any())
            assertEquals("ab12-cd34", requestCaptor.firstValue.code)
        }
    }

    @Test
    fun `failed two factor verification preserves the challenge phase`() {
        runBlocking {
            enterTwoFactorState()
            whenever(authApi.verifyTotp(any())).thenReturn(TwoFactorAuthResponse(verified = false))
            repository.verifyTotp("123456")
            val state = repository.authState.value as AuthState.RequiresTwoFactor
            assertEquals(listOf("totp"), state.methods)
            assertEquals(TwoFactorVerification.Failed("Verification failed"), state.verification)
        }
    }

    @Test
    fun `partial user update preserves omitted authenticated user fields`() {
        runBlocking {
            whenever(authApi.getCurrentUser()).thenReturn(
                buildJsonObject {
                    put("id", "usr_test")
                    put("displayName", "Before")
                    put("location", "wrld_keep:instance")
                    put("travelingToLocation", "wrld_destination:instance")
                }
            )
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            repository.login("test-user", "test-password")
            repository.handleEvent(
                PipelineEvent.UserUpdate(
                    buildJsonObject { put("user", buildJsonObject { put("displayName", "After") }) }
                )
            )
            val user = (repository.authState.value as AuthState.LoggedIn).user
            assertEquals("After", user.displayName)
            assertEquals("wrld_keep:instance", user.location)
            assertEquals("wrld_destination:instance", user.travelingToLocation)
        }
    }

    @Test
    fun `a user-location event is accepted under either spelling of the id key`() {
        runBlocking {
            stubLoginFollowUp()
            repository.login("test-user", "test-password")

            // Dropping the lowercase variant freezes the signed-in user's own
            // presence, which invite sending and the dashboard both read.
            repository.handleEvent(
                PipelineEvent.UserLocation(
                    buildJsonObject {
                        put("userid", "usr_test")
                        put("location", "wrld_moved:instance")
                    }
                )
            )

            assertEquals("wrld_moved:instance", repository.currentUser?.location)
        }
    }

    @Test
    fun `successful password login clears temporary basic auth`() {
        runBlocking {
            stubLoginFollowUp()

            repository.login("test-user", "test-password")

            // The header has to be set before auth/user and dropped after it —
            // clearing early would send the request unauthenticated.
            val order = inOrder(authInterceptor, authApi)
            order.verify(authInterceptor).setBasicAuth("test-user", "test-password")
            order.verify(authApi).getCurrentUser()
            order.verify(authInterceptor).clearBasicAuth()
            assertTrue(repository.authState.value is AuthState.LoggedIn)
        }
    }

    @Test
    fun `password login propagates cancellation without publishing an error`(): Unit = runBlocking {
        whenever(authApi.getCurrentUser()).thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.login("test-user", "test-password") }
        }

        verify(authInterceptor).clearBasicAuth()
        assertTrue(repository.authState.value !is AuthState.Error)
    }

    @Test
    fun `session recheck propagates cancellation without dropping the live session`(): Unit = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        whenever(authApi.getCurrentUser()).thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.fetchCurrentUser() }
        }

        assertTrue(repository.authState.value is AuthState.LoggedIn)
    }

    @Test
    fun `signing in drops the stored cookie before authenticating`() {
        runBlocking {
            stubLoginFollowUp()

            repository.login("test-user", "test-password")

            // The login screen is reachable with a previous account's auth cookie
            // still in the jar, and VRChat may honour it over the Basic header.
            val order = inOrder(cookieJar, authApi)
            order.verify(cookieJar).clearAll()
            order.verify(authApi).getCurrentUser()
        }
    }

    @Test
    fun `an unreachable server puts back the cookies the sign-in attempt cleared`() {
        runBlocking {
            val stored = mapOf("api.vrchat.cloud" to "auth=stored")
            whenever(cookieJar.snapshot()).thenReturn(stored)
            whenever(authApi.getCurrentUser()).thenThrow(RuntimeException("Unable to resolve host"))

            repository.login("test-user", "test-password")

            verify(cookieJar).restore(stored)
        }
    }

    @Test
    fun `a rejected sign-in does not put the previous account's cookies back`() {
        runBlocking {
            whenever(cookieJar.snapshot()).thenReturn(mapOf("api.vrchat.cloud" to "auth=stored"))
            whenever(authApi.getCurrentUser()).thenThrow(httpException(401))

            repository.login("test-user", "test-password")

            verify(cookieJar, never()).restore(any())
        }
    }

    @Test
    fun `a login whose token fetch fails does not report the session ready`() {
        runBlocking {
            stubLoginFollowUp()
            repository.login("test-user", "test-password")
            assertTrue(repository.ensureSessionReady())

            whenever(authApi.getAuthToken()).thenThrow(RuntimeException("offline"))
            repository.login("test-user", "test-password")

            // The previous session's token must not stand in for this one, or the
            // service connects the pipeline with a token VRChat will reject.
            assertFalse(repository.ensureSessionReady())
        }
    }

    @Test
    fun `a session check that lands after the session ended does not republish it`() {
        runBlocking {
            stubLoginFollowUp()
            repository.login("test-user", "test-password")
            repository.logout()

            // The request was already in flight when the session ended; letting it
            // win would leave the logged-in shell over cleared cookies and no
            // websocket, with every later request 401ing.
            repository.fetchCurrentUser()

            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `unauthorized signal keeps session when validation succeeds`() {
        runBlocking {
            stubLoginFollowUp()
            signInAndForgetSetup()

            repository.handleUnauthorizedSignal()

            verify(cookieJar, never()).clearAll()
            assertTrue(repository.authState.value is AuthState.LoggedIn)
        }
    }

    @Test
    fun `unauthorized signal clears session when the server rejects the credentials`() {
        runBlocking {
            whenever(authApi.getCurrentUser())
                .thenReturn(
                    buildJsonObject {
                        put("id", "usr_test")
                        put("displayName", "Test User")
                    }
                )
                .thenThrow(httpException(401))
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            signInAndForgetSetup()

            repository.handleUnauthorizedSignal()

            verify(cookieJar).clearAll()
            verify(dedup).clearCache()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `unauthorized signal keeps the session when the recheck cannot reach the server`() {
        runBlocking {
            whenever(authApi.getCurrentUser())
                .thenReturn(
                    buildJsonObject {
                        put("id", "usr_test")
                        put("displayName", "Test User")
                    }
                )
                .thenThrow(RuntimeException("Unable to resolve host"))
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            signInAndForgetSetup()

            repository.handleUnauthorizedSignal()

            // A connectivity blip is not proof the session died — the stored
            // cookies have to survive it.
            verify(cookieJar, never()).clearAll()
            assertTrue(repository.authState.value is AuthState.LoggedIn)
        }
    }

    @Test
    fun `unauthorized signal keeps cookies when only the second factor lapsed`() {
        runBlocking {
            whenever(authApi.getCurrentUser())
                .thenReturn(
                    buildJsonObject {
                        put("id", "usr_test")
                        put("displayName", "Test User")
                    }
                )
                .thenReturn(
                    buildJsonObject {
                        put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("totp")) })
                    }
                )
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            signInAndForgetSetup()

            repository.handleUnauthorizedSignal()

            // verify2fa authenticates with the auth cookie, so dropping it here
            // would make the challenge impossible to answer.
            verify(cookieJar, never()).clearAll()
            assertTrue(repository.authState.value is AuthState.RequiresTwoFactor)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resume retries a transient failure before giving up on the stored session`() = runTest {
        whenever(cookieJar.getAuthCookie()).thenReturn("authcookie_test")
        whenever(authApi.getCurrentUser())
            .thenThrow(RuntimeException("timeout"))
            .thenReturn(
                buildJsonObject {
                    put("id", "usr_test")
                    put("displayName", "Test User")
                }
            )
        whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))

        repository.tryResumeSession()

        verify(cookieJar, never()).clearAll()
        assertTrue(repository.authState.value is AuthState.LoggedIn)
        // The second attempt waits out the first backoff step rather than
        // hammering a radio that is still coming up.
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resume keeps the stored session when the server stays unreachable`() = runTest {
        whenever(cookieJar.getAuthCookie()).thenReturn("authcookie_test")
        whenever(authApi.getCurrentUser()).thenThrow(RuntimeException("Unable to resolve host"))

        repository.tryResumeSession()

        verify(cookieJar, never()).clearAll()
        assertTrue(repository.authState.value is AuthState.Error)
        assertTrue(repository.hasResumableSession())
        // Three attempts spaced 0/2s/5s before giving up on this launch.
        assertEquals(7_000L, testScheduler.currentTime)
    }

    @Test
    fun `resume ends the session only when the server rejects the stored cookie`() {
        runBlocking {
            whenever(cookieJar.getAuthCookie()).thenReturn("authcookie_test")
            whenever(authApi.getCurrentUser()).thenThrow(httpException(401))

            repository.tryResumeSession()

            verify(cookieJar).clearAll()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    @Test
    fun `logout invalidates the session server-side before clearing local state`() {
        runBlocking {
            repository.logout()

            // PUT /logout has to go out while the auth cookie is still attached,
            // otherwise the server-side session survives the sign-out.
            val order = inOrder(authApi, cookieJar)
            order.verify(authApi).logout()
            order.verify(cookieJar).clearAll()
            verify(authInterceptor).clearBasicAuth()
            verify(dedup).clearCache()
            verify(favoriteRepository).clearRuntimeState()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `signing out forgets the remembered password`() {
        runBlocking {
            repository.logout()

            // The cookies are revoked server-side, but the password is reusable —
            // leaving it on the device hands the account to whoever picks it up.
            verify(secureSecretsStore).clearAll()
            verify(preferences).clearLegacySavedCredentials()
        }
    }

    @Test
    fun `an involuntary session end keeps the remembered password`() {
        runBlocking {
            whenever(authApi.getCurrentUser())
                .thenReturn(
                    buildJsonObject {
                        put("id", "usr_test")
                        put("displayName", "Test User")
                    }
                )
                .thenThrow(httpException(401))
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            repository.login("test-user", "test-password")

            repository.handleUnauthorizedSignal()

            // Remember-me has to survive a session VRChat ended on its own.
            verify(secureSecretsStore, never()).clearAll()
        }
    }

    @Test
    fun `an unauthorized signal with no session at all never reaches the api`() {
        runBlocking {
            whenever(cookieJar.getAuthCookie()).thenReturn(null)

            repository.handleUnauthorizedSignal()

            // A 401 on the login screen must not raise a 2FA prompt with no
            // session behind it.
            verify(authApi, never()).getCurrentUser()
        }
    }

    @Test
    fun `logout resets every account-scoped repository`() {
        runBlocking {
            repository.logout()

            assertAccountScopedStateCleared()
        }
    }

    @Test
    fun `a rejected session recheck resets every account-scoped repository`() {
        runBlocking {
            whenever(authApi.getCurrentUser())
                .thenReturn(
                    buildJsonObject {
                        put("id", "usr_test")
                        put("displayName", "Test User")
                    }
                )
                .thenThrow(httpException(401))
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            repository.login("test-user", "test-password")

            repository.handleUnauthorizedSignal()

            assertAccountScopedStateCleared()
        }
    }

    @Test
    fun `resending the email code resets every account-scoped repository`() {
        runBlocking {
            whenever(authApi.getCurrentUser()).thenReturn(
                buildJsonObject {
                    put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("emailOtp")) })
                }
            )

            repository.resendEmailOtp("test-user", "test-password")

            assertAccountScopedStateCleared()
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

    @Test
    fun `logout clears local state before propagating cancellation`(): Unit = runBlocking {
        authApi.stub {
            onBlocking { logout() } doThrow CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.logout() }
        }

        verify(cookieJar).clearAll()
        verify(dedup).clearCache()
        verify(favoriteRepository).clearRuntimeState()
        assertSame(AuthState.NotLoggedIn, repository.authState.value)
    }

    /**
     * Signing in clears the jar by design, so the cases below drop the setup
     * interactions first and assert only what the path under test did to it.
     */
    private suspend fun signInAndForgetSetup() {
        repository.login("test-user", "test-password")
        clearInvocations(cookieJar)
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

    private suspend fun enterTwoFactorState(method: String = "totp") {
        whenever(authApi.getCurrentUser()).thenReturn(
            buildJsonObject {
                put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive(method)) })
            }
        )
        repository.login("test-user", "test-password")
        assertTrue(repository.authState.value is AuthState.RequiresTwoFactor)
    }
}
