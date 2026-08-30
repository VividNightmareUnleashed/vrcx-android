package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.CookieStorageStatus
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.basicAuthorization
import com.vrcx.android.data.api.model.AuthToken
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.directTestDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response

private const val AUTH_TEST_IO_THREADS = 2

open class AuthRepositoryTestFixture {
    private val fixtureExecutor = Executors.newFixedThreadPool(AUTH_TEST_IO_THREADS)
    protected val testIoDispatcher: ExecutorCoroutineDispatcher = fixtureExecutor.asCoroutineDispatcher()
    protected val authApi = mock<AuthApi>()
    protected val cookieStorageStatus = MutableStateFlow(CookieStorageStatus.READY)
    protected val cookieJar = mock<CookieJarImpl>().also {
        whenever(it.storageStatus).thenReturn(cookieStorageStatus)
        whenever(it.clearAll()).thenReturn(true)
        whenever(it.commitAuthenticatedSession()).thenReturn(true)
        whenever(it.completeLogoutAfterSecretsDeleted()).thenReturn(true)
        whenever(it.restore(any())).thenReturn(true)
    }
    protected val preferences = mock<VrcxPreferences>()
    protected val secureSecretsStore = mock<SecureSecretsStore>().also {
        whenever(it.clearAll()).thenReturn(true)
    }
    protected val dedup = mock<RequestDeduplicator>()
    protected val favoriteRepository = mock<FavoriteRepository>()

    protected val avatarRepository = mock<AvatarRepository>()
    protected val friendRepository = mock<FriendRepository>()
    protected val galleryRepository = mock<GalleryRepository>()
    protected val groupRepository = mock<GroupRepository>()
    protected val moderationRepository = mock<ModerationRepository>()
    protected val notificationRepository = mock<NotificationRepository>()
    protected val userRepository = mock<UserRepository>()
    protected val worldRepository = mock<WorldRepository>()

    protected val accountScope = AccountScope()
    protected val explicitLogoutSignal = ExplicitLogoutSignal()
    private val sessionRuntime = AuthSessionRuntime(
        accountScope = accountScope,
        authenticatedSessionGate = AuthenticatedSessionGate(accountScope),
        requestDeduplicator = dedup,
        explicitLogoutSignal = explicitLogoutSignal,
    )

    protected val repository = AuthRepository(
        authApi = authApi,
        cookieJar = cookieJar,
        preferences = preferences,
        secureSecretsStore = secureSecretsStore,
        json = Json { ignoreUnknownKeys = true },
        sessionRuntime = sessionRuntime,
        defaultDispatcher = directTestDispatcher,
        ioDispatcher = testIoDispatcher,
    )

    @After
    fun closeTestIoDispatcher() {
        testIoDispatcher.close()
    }

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
    protected fun assertAccountScopedStateCleared() {
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

    /**
     * Signing in clears the jar by design, so callers drop the setup interactions
     * before asserting what the path under test did to it.
     */
    protected suspend fun signInAndForgetSetup() {
        repository.login("test-user", "test-password")
        clearInvocations(cookieJar)
    }

    protected suspend fun stubLoginFollowUp() {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            currentUserJson("usr_test", "Test User"),
        )
        whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
    }

    protected fun currentUserJson(id: String, displayName: String) = buildJsonObject {
        put("id", id)
        put("displayName", displayName)
    }

    protected suspend fun enterTwoFactorState(method: String = "totp") {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            buildJsonObject {
                put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive(method)) })
            },
        )
        repository.login("test-user", "test-password")
        assertTrue(repository.authState.value is AuthState.RequiresTwoFactor)
    }

    protected fun httpException(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))
}

class AuthRepositoryTest : AuthRepositoryTestFixture() {

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
    fun `a completed two factor login commits its authenticated cookies before publication`() {
        runBlocking {
            enterTwoFactorState()
            whenever(authApi.verifyTotp(any())).thenReturn(TwoFactorAuthResponse(verified = true))
            whenever(authApi.getCurrentUser()).thenReturn(currentUserJson("usr_test", "Test User"))
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))

            repository.verifyTotp("123456")

            verify(cookieJar).commitAuthenticatedSession()
            assertTrue(repository.authState.value is AuthState.LoggedIn)
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
            whenever(authApi.loginWithBasicAuth(any())).thenReturn(
                buildJsonObject {
                    put("id", "usr_test")
                    put("displayName", "Before")
                    put("location", "wrld_keep:instance")
                    put("travelingToLocation", "wrld_destination:instance")
                },
            )
            whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
            repository.login("test-user", "test-password")
            repository.handleEvent(
                PipelineEvent.UserUpdate(
                    buildJsonObject { put("user", buildJsonObject { put("displayName", "After") }) },
                ),
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
                    },
                ),
            )

            assertEquals("wrld_moved:instance", repository.currentUser?.location)
        }
    }

    @Test
    fun `wrong-shaped user pipeline fields are ignored without changing the session user`() = runBlocking {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            buildJsonObject {
                put("id", "usr_test")
                put("displayName", "Before")
                put("location", "wrld_keep:instance")
            },
        )
        whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))
        repository.login("test-user", "test-password")
        val before = repository.currentUser

        listOf(
            PipelineEvent.UserUpdate(buildJsonArray { add(JsonPrimitive("not-an-object")) }),
            PipelineEvent.UserUpdate(
                buildJsonObject { put("user", buildJsonArray { add(JsonPrimitive("not-an-object")) }) },
            ),
            PipelineEvent.UserUpdate(
                buildJsonObject {
                    put("user", buildJsonObject { put("displayName", buildJsonArray {}) })
                },
            ),
            PipelineEvent.UserLocation(buildJsonArray { add(JsonPrimitive("not-an-object")) }),
            PipelineEvent.UserLocation(
                buildJsonObject {
                    put("userId", buildJsonObject {})
                    put("location", "wrld_replace:instance")
                },
            ),
            PipelineEvent.UserLocation(
                buildJsonObject {
                    put("userId", "usr_test")
                    put("location", buildJsonArray {})
                },
            ),
            PipelineEvent.UserLocation(
                buildJsonObject {
                    put("userId", "usr_test")
                    put("location", 42)
                },
            ),
        ).forEach { event -> repository.handleEvent(event) }

        assertEquals(before, repository.currentUser)
        assertEquals(before, (repository.authState.value as AuthState.LoggedIn).user)
    }

    @Test
    fun `a malformed optional user-location field does not discard the valid location`() = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")

        repository.handleEvent(
            PipelineEvent.UserLocation(
                buildJsonObject {
                    put("userId", "usr_test")
                    put("location", "wrld_moved:instance")
                    put("travelingToLocation", buildJsonObject {})
                },
            ),
        )

        assertEquals("wrld_moved:instance", repository.currentUser?.location)
        assertNull(repository.currentUser?.travelingToLocation)
    }
}

class AuthRepositoryPipelineTest : AuthRepositoryTestFixture() {
    @Test
    fun `password login passes basic auth only to its own request`() {
        runBlocking {
            stubLoginFollowUp()

            repository.login("test-user", "test-password")

            val header = argumentCaptor<String>()
            verify(authApi).loginWithBasicAuth(header.capture())
            assertEquals(basicAuthorization("test-user", "test-password"), header.firstValue)
            verify(cookieJar).commitAuthenticatedSession()
            assertTrue(repository.authState.value is AuthState.LoggedIn)
        }
    }

    @Test
    fun `pipeline session keeps the websocket credential and account origin together`(): Unit = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        val oldAccount = accountScope.current()

        assertEquals(
            PipelineSession(authToken = "token", account = oldAccount),
            repository.pipelineSession(),
        )

        accountScope.invalidate()
        accountScope.bind("usr_new")
        clearInvocations(authApi)

        assertEquals(null, repository.pipelineSession())
        assertEquals(null, repository.refreshPipelineSession(oldAccount))
        verify(authApi, never()).getAuthToken()
    }

    @Test
    fun `pipeline reconciliation preserves a renewed two-factor challenge`(): Unit = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        val origin = accountScope.current()
        clearInvocations(cookieJar)
        whenever(authApi.getCurrentUser()).thenReturn(
            buildJsonObject {
                put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("emailOtp")) })
            },
        )

        repository.resynchronizePipelineState(origin)

        val state = repository.authState.value as AuthState.RequiresTwoFactor
        assertEquals(listOf("emailOtp"), state.methods)
        assertEquals(null, repository.pipelineSession())
        verify(cookieJar, never()).clearAll()
    }

    @Test
    fun `active pipeline reconciliation updates the user without resetting account state`() = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        val origin = accountScope.current()
        clearInvocations(friendRepository, notificationRepository, groupRepository, galleryRepository)
        whenever(authApi.getCurrentUser()).thenReturn(currentUserJson("usr_test", "Updated User"))

        repository.resynchronizePipelineState(origin)

        assertEquals("Updated User", repository.currentUser?.displayName)
        assertEquals(origin, repository.pipelineSession()?.account)
        verify(friendRepository, never()).clearRuntimeState()
        verify(notificationRepository, never()).clearRuntimeState()
        verify(groupRepository, never()).clearRuntimeState()
        verify(galleryRepository, never()).clearRuntimeState()
    }

    @Test
    fun `an authenticated login is not published when its cookies cannot be committed`() {
        runBlocking {
            stubLoginFollowUp()
            whenever(cookieJar.commitAuthenticatedSession()).thenReturn(false)

            repository.login("test-user", "test-password")

            assertTrue(repository.authState.value is AuthState.Error)
            assertEquals(null, repository.currentUser)
            assertNotNull(repository.storageError.value)
            verify(authApi, never()).getAuthToken()
            // One clear starts the replacement attempt; the second prevents its
            // uncommitted in-memory cookies from masquerading as a live session.
            verify(cookieJar, times(2)).clearAll()
        }
    }

    @Test
    fun `every non-ready cookie store status is explicit before any resume attempt`() {
        CookieStorageStatus.values()
            .filterNot { it == CookieStorageStatus.READY }
            .forEach { status ->
                val unhealthyCookieJar = mock<CookieJarImpl>()
                whenever(unhealthyCookieJar.storageStatus).thenReturn(MutableStateFlow(status))
                val unhealthyAccountScope = AccountScope()

                val unhealthyRepository = AuthRepository(
                    authApi = authApi,
                    cookieJar = unhealthyCookieJar,
                    preferences = preferences,
                    secureSecretsStore = secureSecretsStore,
                    json = Json { ignoreUnknownKeys = true },
                    sessionRuntime = AuthSessionRuntime(
                        accountScope = unhealthyAccountScope,
                        authenticatedSessionGate = AuthenticatedSessionGate(unhealthyAccountScope),
                        requestDeduplicator = dedup,
                        explicitLogoutSignal = ExplicitLogoutSignal(),
                    ),
                    defaultDispatcher = directTestDispatcher,
                    ioDispatcher = testIoDispatcher,
                )

                assertNotNull("$status must be surfaced", unhealthyRepository.storageError.value)
                assertSame(AuthState.NotLoggedIn, unhealthyRepository.authState.value)
            }
    }

    @Test
    fun `cookie persistence failures after startup are surfaced globally`() = runBlocking {
        val expectedErrors = mapOf(
            CookieStorageStatus.WRITE_FAILED to
                "Saved session data couldn't be moved to secure storage.",
            CookieStorageStatus.LEGACY_CLEANUP_FAILED to
                "The new session is secure, but an older saved session copy couldn't be removed.",
        )

        expectedErrors.forEach { (status, expectedError) ->
            repository.dismissStorageError()
            cookieStorageStatus.value = status

            val observed = withTimeout(1_000) {
                repository.storageError.first { it == expectedError }
            }
            assertEquals(expectedError, observed)
            cookieStorageStatus.value = CookieStorageStatus.READY
        }
    }

    @Test
    fun `password login propagates cancellation without publishing an error`(): Unit = runBlocking {
        whenever(authApi.loginWithBasicAuth(any())).thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.login("test-user", "test-password") }
        }

        assertTrue(repository.authState.value !is AuthState.Error)
    }

    @Test
    fun `token fetch cancellation does not restore cookies after login committed`(): Unit = runBlocking {
        val storedCookies = mapOf("api.vrchat.cloud" to "auth=previous")
        whenever(cookieJar.snapshot()).thenReturn(storedCookies)
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            currentUserJson("usr_new", "New User"),
        )
        whenever(authApi.getAuthToken()).thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.login("new-user", "new-password") }
        }

        verify(cookieJar, never()).restore(any())
        assertEquals("usr_new", (repository.authState.value as AuthState.LoggedIn).user.id)
        assertEquals(null, repository.authToken)
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
}

class AuthRepositoryLoginTest : AuthRepositoryTestFixture() {
    @Test
    fun `signing in drops the stored cookie before authenticating`() {
        runBlocking {
            stubLoginFollowUp()

            repository.login("test-user", "test-password")

            // The login screen is reachable with a previous account's auth cookie
            // still in the jar, and VRChat may honour it over the Basic header.
            val order = inOrder(cookieJar, authApi)
            order.verify(cookieJar).clearAll()
            order.verify(authApi).loginWithBasicAuth(any())
        }
    }

    @Test
    fun `sign-in cookie cleanup runs off the caller without holding the session monitor`() = runBlocking {
        val callerThread = Thread.currentThread()
        val snapshotThread = AtomicReference<Thread>()
        val cleanupThread = AtomicReference<Thread>()
        val sessionMonitorWasAvailable = AtomicBoolean(false)
        val reader = Executors.newSingleThreadExecutor()
        try {
            whenever(cookieJar.snapshot()).thenAnswer {
                snapshotThread.set(Thread.currentThread())
                emptyMap<String, String>()
            }
            whenever(cookieJar.clearAll()).thenAnswer {
                cleanupThread.set(Thread.currentThread())
                val read = reader.submit<Boolean> { repository.currentUser == null }
                sessionMonitorWasAvailable.set(read.get(1, TimeUnit.SECONDS))
                true
            }
            whenever(authApi.loginWithBasicAuth(any())).thenReturn(
                buildJsonObject {
                    put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("totp")) })
                },
            )

            repository.login("test-user", "test-password")

            assertNotSame(callerThread, snapshotThread.get())
            assertNotSame(callerThread, cleanupThread.get())
            assertTrue(sessionMonitorWasAvailable.get())
        } finally {
            reader.shutdownNow()
        }
    }

    @Test
    fun `resumable-session cookie probe runs off the caller`() {
        val callerThread = Thread.currentThread()
        val probeThread = AtomicReference<Thread>()
        whenever(cookieJar.getAuthCookie()).thenAnswer {
            probeThread.set(Thread.currentThread())
            null
        }

        runBlocking { repository.hasResumableSession() }

        assertNotSame(callerThread, probeThread.get())
    }

    @Test
    fun `an unreachable server puts back the cookies the sign-in attempt cleared`() {
        runBlocking {
            val stored = mapOf("api.vrchat.cloud" to "auth=stored")
            whenever(cookieJar.snapshot()).thenReturn(stored)
            whenever(authApi.loginWithBasicAuth(any())).thenThrow(RuntimeException("Unable to resolve host"))

            repository.login("test-user", "test-password")

            verify(cookieJar).restore(stored)
        }
    }

    @Test
    fun `a rejected sign-in does not put the previous account's cookies back`() {
        runBlocking {
            whenever(cookieJar.snapshot()).thenReturn(mapOf("api.vrchat.cloud" to "auth=stored"))
            whenever(authApi.loginWithBasicAuth(any())).thenThrow(httpException(401))

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
}

class AuthRepositoryUnauthorizedTest : AuthRepositoryTestFixture() {
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
            stubLoginFollowUp()
            whenever(authApi.getCurrentUser()).thenThrow(httpException(401))
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
            stubLoginFollowUp()
            whenever(authApi.getCurrentUser()).thenThrow(RuntimeException("Unable to resolve host"))
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
            stubLoginFollowUp()
            whenever(authApi.getCurrentUser()).thenReturn(
                buildJsonObject {
                    put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("totp")) })
                },
            )
            signInAndForgetSetup()

            repository.handleUnauthorizedSignal()

            // verify2fa authenticates with the auth cookie, so dropping it here
            // would make the challenge impossible to answer.
            verify(cookieJar, never()).clearAll()
            assertTrue(repository.authState.value is AuthState.RequiresTwoFactor)
        }
    }

    @Test
    fun `a successful unauthorized recheck cannot resurrect a logged out session`() = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(authApi.getCurrentUser()).doSuspendableAnswer {
            checkStarted.complete(Unit)
            releaseCheck.await()
            currentUserJson("usr_test", "Old User")
        }

        coroutineScope {
            val recheck = async(testIoDispatcher) { repository.handleUnauthorizedSignal() }
            checkStarted.await()
            val logout = async(
                context = testIoDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) { repository.logout() }
            try {
                assertFalse(logout.isCompleted)
                verify(authApi, never()).logout()
            } finally {
                releaseCheck.complete(Unit)
            }
            recheck.await()
            logout.await()
        }

        assertSame(AuthState.NotLoggedIn, repository.authState.value)
        assertEquals(null, repository.currentUser)
    }

    @Test
    fun `cancelled logout still clears locally while waiting for a session recheck`() = runBlocking {
        stubLoginFollowUp()
        repository.login("test-user", "test-password")
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(authApi.getCurrentUser()).doSuspendableAnswer {
            checkStarted.complete(Unit)
            releaseCheck.await()
            currentUserJson("usr_test", "Test User")
        }

        coroutineScope {
            val recheck = async(testIoDispatcher) { repository.handleUnauthorizedSignal() }
            checkStarted.await()
            val logout = async(
                context = testIoDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) { repository.logout() }
            logout.cancel(CancellationException("cancelled"))
            releaseCheck.complete(Unit)
            recheck.await()
            logout.join()
        }

        verify(authApi, never()).logout()
        assertSame(AuthState.NotLoggedIn, repository.authState.value)
    }

    @Test
    fun `a successful unauthorized recheck cannot replace a newer account`() = runBlocking {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            currentUserJson("usr_old", "Old User"),
            currentUserJson("usr_new", "New User"),
        )
        whenever(authApi.getAuthToken()).thenReturn(
            AuthToken(token = "old-token"),
            AuthToken(token = "new-token"),
        )
        repository.login("old-user", "old-password")
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(authApi.getCurrentUser()).doSuspendableAnswer {
            checkStarted.complete(Unit)
            releaseCheck.await()
            currentUserJson("usr_old", "Old User")
        }

        coroutineScope {
            val recheck = async(testIoDispatcher) { repository.handleUnauthorizedSignal() }
            checkStarted.await()
            val newLogin = async(
                context = testIoDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) { repository.login("new-user", "new-password") }
            try {
                assertFalse(newLogin.isCompleted)
                verify(authApi, times(1)).loginWithBasicAuth(any())
            } finally {
                releaseCheck.complete(Unit)
            }
            recheck.await()
            newLogin.await()
        }

        assertEquals("usr_new", (repository.authState.value as AuthState.LoggedIn).user.id)
        assertEquals("new-token", repository.authToken)
    }

    @Test
    fun `a rejected unauthorized recheck cannot clear a newer account`() = runBlocking {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            currentUserJson("usr_old", "Old User"),
            currentUserJson("usr_new", "New User"),
        )
        whenever(authApi.getAuthToken()).thenReturn(
            AuthToken(token = "old-token"),
            AuthToken(token = "new-token"),
        )
        repository.login("old-user", "old-password")
        clearInvocations(cookieJar, dedup)
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(authApi.getCurrentUser()).doSuspendableAnswer {
            checkStarted.complete(Unit)
            releaseCheck.await()
            throw httpException(401)
        }

        coroutineScope {
            val recheck = async(testIoDispatcher) { repository.handleUnauthorizedSignal() }
            checkStarted.await()
            val newLogin = async(
                context = testIoDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) { repository.login("new-user", "new-password") }
            try {
                assertFalse(newLogin.isCompleted)
                verify(authApi, times(1)).loginWithBasicAuth(any())
            } finally {
                releaseCheck.complete(Unit)
            }
            recheck.await()
            newLogin.await()
        }

        assertEquals("usr_new", (repository.authState.value as AuthState.LoggedIn).user.id)
        verify(cookieJar, times(2)).clearAll()
        verify(dedup, times(1)).clearCache()
    }

    @Test
    fun `a late two factor recheck cannot replace a newer account`() = runBlocking {
        whenever(authApi.loginWithBasicAuth(any())).thenReturn(
            currentUserJson("usr_old", "Old User"),
            currentUserJson("usr_new", "New User"),
        )
        whenever(authApi.getAuthToken()).thenReturn(
            AuthToken(token = "old-token"),
            AuthToken(token = "new-token"),
        )
        repository.login("old-user", "old-password")
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(authApi.getCurrentUser()).doSuspendableAnswer {
            checkStarted.complete(Unit)
            releaseCheck.await()
            buildJsonObject {
                put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("totp")) })
            }
        }

        coroutineScope {
            val recheck = async(testIoDispatcher) { repository.handleUnauthorizedSignal() }
            checkStarted.await()
            val newLogin = async(
                context = testIoDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) { repository.login("new-user", "new-password") }
            try {
                assertFalse(newLogin.isCompleted)
                verify(authApi, times(1)).loginWithBasicAuth(any())
            } finally {
                releaseCheck.complete(Unit)
            }
            recheck.await()
            newLogin.await()
        }

        assertEquals("usr_new", (repository.authState.value as AuthState.LoggedIn).user.id)
    }
}

class AuthRepositoryResumeAndLogoutTest : AuthRepositoryTestFixture() {
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
                },
            )
        whenever(authApi.getAuthToken()).thenReturn(AuthToken(token = "token"))

        repository.tryResumeSession()

        verify(cookieJar, never()).clearAll()
        verify(cookieJar, never()).commitAuthenticatedSession()
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

    @Test
    fun `logout invalidates the session server-side before clearing local state`() {
        runBlocking {
            repository.logout()

            // PUT /logout has to go out while the auth cookie is still attached,
            // otherwise the server-side session survives the sign-out.
            val order = inOrder(authApi, cookieJar)
            order.verify(authApi).logout()
            order.verify(cookieJar).clearAll()
            verify(dedup).clearCache()
            verify(favoriteRepository).clearRuntimeState()
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `signing out forgets the remembered password`() {
        runBlocking {
            val logoutSignaled = AtomicBoolean()
            explicitLogoutSignal.subscribe { logoutSignaled.set(true) }

            repository.logout()

            // The cookies are revoked server-side, but the password is reusable —
            // leaving it on the device hands the account to whoever picks it up.
            verify(secureSecretsStore).clearAll()
            verify(cookieJar).completeLogoutAfterSecretsDeleted()
            verify(preferences).clearLegacySavedCredentials()
            assertTrue(logoutSignaled.get())
        }
    }

    @Test
    fun `logout clears process credentials before publishing the logged-out state`() {
        runBlocking {
            stubLoginFollowUp()
            repository.login("test-user", "test-password")
            val stateAtSignal = AtomicReference<AuthState>()
            explicitLogoutSignal.subscribe { stateAtSignal.set(repository.authState.value) }

            repository.logout()

            assertTrue(stateAtSignal.get() is AuthState.LoggedIn)
            assertSame(AuthState.NotLoggedIn, repository.authState.value)
        }
    }

    @Test
    fun `a failing logout observer cannot veto secret and session cleanup`() = runBlocking {
        explicitLogoutSignal.subscribe { error("observer failed") }

        repository.logout()

        verify(secureSecretsStore).clearAll()
        assertSame(AuthState.NotLoggedIn, repository.authState.value)
    }

    @Test
    fun `logout secret deletion runs off the caller`() {
        val callerThread = Thread.currentThread()
        val deletionThread = AtomicReference<Thread>()
        whenever(secureSecretsStore.clearAll()).thenAnswer {
            deletionThread.set(Thread.currentThread())
            true
        }

        runBlocking { repository.logout() }

        assertNotSame(callerThread, deletionThread.get())
    }

    @Test
    fun `durable logout cleanup failure is surfaced after in-memory logout completes`() {
        runBlocking {
            whenever(secureSecretsStore.clearAll()).thenReturn(false)

            repository.logout()

            assertSame(AuthState.NotLoggedIn, repository.authState.value)
            assertNotNull(repository.storageError.value)
            verify(cookieJar, never()).completeLogoutAfterSecretsDeleted()
            verify(preferences).clearLegacySavedCredentials()
        }
    }

    @Test
    fun `an involuntary session end keeps the remembered password`() {
        runBlocking {
            val logoutSignaled = AtomicBoolean()
            explicitLogoutSignal.subscribe { logoutSignaled.set(true) }
            stubLoginFollowUp()
            whenever(authApi.getCurrentUser()).thenThrow(httpException(401))
            repository.login("test-user", "test-password")

            repository.handleUnauthorizedSignal()

            // Remember-me has to survive a session VRChat ended on its own.
            verify(secureSecretsStore, never()).clearAll()
            assertFalse(logoutSignaled.get())
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
            stubLoginFollowUp()
            whenever(authApi.getCurrentUser()).thenThrow(httpException(401))
            repository.login("test-user", "test-password")

            repository.handleUnauthorizedSignal()

            assertAccountScopedStateCleared()
        }
    }

    @Test
    fun `resending the email code resets every account-scoped repository`() {
        runBlocking {
            whenever(authApi.loginWithBasicAuth(any())).thenReturn(
                buildJsonObject {
                    put("requiresTwoFactorAuth", buildJsonArray { add(JsonPrimitive("emailOtp")) })
                },
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

    @Test
    fun `legacy credential cleanup propagates cancellation after local logout`(): Unit = runBlocking {
        preferences.stub {
            onBlocking { clearLegacySavedCredentials() } doThrow CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.logout() }
        }

        verify(secureSecretsStore).clearAll()
        verify(cookieJar).completeLogoutAfterSecretsDeleted()
        assertSame(AuthState.NotLoggedIn, repository.authState.value)
    }
}
