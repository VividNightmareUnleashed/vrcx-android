package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.ExplicitLogoutSignal
import com.vrcx.android.data.security.CredentialMutationResult
import com.vrcx.android.data.security.CredentialWriteToken
import com.vrcx.android.data.security.SavedCredentials
import com.vrcx.android.data.security.SavedCredentialsRead
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.ui.common.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    private val authRepository = mock<AuthRepository>()
    private val preferences = mock<VrcxPreferences>()
    private val secureSecretsStore = mock<SecureSecretsStore>()
    private val authState = MutableStateFlow<AuthState>(AuthState.RequiresTwoFactor(listOf("emailOtp", "totp")))
    private val authStorageError = MutableStateFlow<String?>(null)
    private val explicitLogoutSignal = ExplicitLogoutSignal()

    @Before
    fun setUp() {
        whenever(authRepository.authState).thenReturn(authState)
        whenever(authRepository.storageError).thenReturn(authStorageError)
        whenever(secureSecretsStore.readSavedCredentials())
            .thenReturn(SavedCredentialsRead(credentials = null, isReadable = true))
        whenever(secureSecretsStore.credentialWriteToken()).thenReturn(CredentialWriteToken(0))
        whenever(secureSecretsStore.saveSavedCredentialsIfCurrent(any(), any(), any()))
            .thenReturn(CredentialMutationResult.APPLIED)
        whenever(secureSecretsStore.clearSavedCredentialsIfCurrent(any()))
            .thenReturn(CredentialMutationResult.APPLIED)
    }

    @Test
    fun `an email challenge is answered on the email endpoint and an authenticator one is not`() =
        runTest(testDispatcher) {
            val viewModel = buildViewModel()
            viewModel.updateTwoFactorCode("123456")

            viewModel.submitTwoFactor(useEmail = true)
            advanceUntilIdle()

            // Email-only accounts cannot log in at all if this lands on the
            // authenticator endpoint, and nothing else in the app pins it.
            verify(authRepository).verifyEmailOtp("123456")
            verify(authRepository, never()).verifyTotp(any())
        }

    @Test
    fun `an authenticator challenge is answered on the totp endpoint`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor(useEmail = false)
        advanceUntilIdle()

        verify(authRepository).verifyTotp("123456")
        verify(authRepository, never()).verifyEmailOtp(any())
    }

    @Test
    fun `a failed two factor attempt keeps the typed code`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        viewModel.updateTwoFactorCode("12345678")

        viewModel.submitTwoFactor()
        advanceUntilIdle()

        // A rolled TOTP would have to be re-read and a recovery code retyped off
        // paper, for a failure that may just be a dropped connection.
        assertEquals("12345678", viewModel.twoFactorCode.value)
    }

    @Test
    fun `a successful two factor attempt clears the code`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { verifyTotp(any()) } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor()
        advanceUntilIdle()

        assertEquals("", viewModel.twoFactorCode.value)
    }

    @Test
    fun `two factor submission verifies its snapshot and preserves a newer edit`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { verifyTotp("123456") } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor()
        viewModel.updateTwoFactorCode("654321")
        advanceUntilIdle()

        verify(authRepository).verifyTotp("123456")
        assertEquals("654321", viewModel.twoFactorCode.value)
    }

    @Test
    fun `a queued login saves the immutable attempt rather than later form edits`() = runTest(testDispatcher) {
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        whenever(authRepository.login("account-a", "secret-a")).doSuspendableAnswer {
            loginStarted.complete(Unit)
            releaseLogin.await()
            authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_a"))
        }
        val viewModel = buildViewModel()
        viewModel.updateUsername("account-a")
        viewModel.updatePassword("secret-a")
        viewModel.toggleRememberMe()

        viewModel.login()
        runCurrent()
        loginStarted.await()
        viewModel.updateUsername("account-b")
        viewModel.updatePassword("secret-b")
        releaseLogin.complete(Unit)
        advanceUntilIdle()

        verify(secureSecretsStore).saveSavedCredentialsIfCurrent(any(), eq("account-a"), eq("secret-a"))
    }

    @Test
    fun `an explicit login challenge persists its matching attempt after verification`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { verifyTotp(any()) } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        viewModel.updateUsername("account-a")
        viewModel.updatePassword("secret-a")
        viewModel.toggleRememberMe()
        viewModel.login()
        advanceUntilIdle()
        assertTrue(viewModel.canResendEmailCode.value)
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor()
        advanceUntilIdle()

        verify(secureSecretsStore).saveSavedCredentialsIfCurrent(any(), eq("account-a"), eq("secret-a"))
    }

    @Test
    fun `a resumed two factor challenge cannot rewrite unrelated remembered credentials`() = runTest(testDispatcher) {
        whenever(secureSecretsStore.readSavedCredentials()).thenReturn(
            SavedCredentialsRead(SavedCredentials("account-a", "secret-a"), isReadable = true),
        )
        authRepository.stub {
            onBlocking { verifyTotp(any()) } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_b"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.canResendEmailCode.value)
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor()
        advanceUntilIdle()

        verify(secureSecretsStore, never()).saveSavedCredentialsIfCurrent(any(), any(), any())
        verify(secureSecretsStore, never()).clearSavedCredentialsIfCurrent(any())
    }

    @Test
    fun `auto login challenge can resend with the credentials that opened it`() = runTest(testDispatcher) {
        authState.value = AuthState.NotLoggedIn
        whenever(authRepository.hasResumableSession()).thenReturn(false)
        whenever(secureSecretsStore.readSavedCredentials()).thenReturn(
            SavedCredentialsRead(SavedCredentials("account-a", "secret-a"), isReadable = true),
        )
        whenever(preferences.autoLogin).thenReturn(flowOf(true))
        authRepository.stub {
            onBlocking { login("account-a", "secret-a") } doAnswer {
                authState.value = AuthState.RequiresTwoFactor(listOf("emailOtp"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.tryResumeSession()
        advanceUntilIdle()
        assertTrue(viewModel.canResendEmailCode.value)

        viewModel.resendEmailCode()
        advanceUntilIdle()

        verify(authRepository).login("account-a", "secret-a")
        verify(authRepository).resendEmailOtp("account-a", "secret-a")
        verify(secureSecretsStore, never()).saveSavedCredentialsIfCurrent(any(), any(), any())
        verify(secureSecretsStore, never()).clearSavedCredentialsIfCurrent(any())
    }

    @Test
    fun `resend that completes login applies the matching remember policy`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { resendEmailOtp("account-a", "secret-a") } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_a"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        viewModel.updateUsername("account-a")
        viewModel.updatePassword("secret-a")
        viewModel.toggleRememberMe()
        viewModel.login()
        advanceUntilIdle()

        viewModel.resendEmailCode()
        advanceUntilIdle()

        verify(secureSecretsStore).saveSavedCredentialsIfCurrent(any(), eq("account-a"), eq("secret-a"))
        assertFalse(viewModel.canResendEmailCode.value)
    }

    @Test
    fun `an unresolved sign-in leaves the stored credentials alone`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        viewModel.toggleRememberMe()

        viewModel.login()
        advanceUntilIdle()

        // Remember-me with the attempt still on a 2FA challenge is not a decision
        // either way, so neither branch of the policy may fire.
        verify(secureSecretsStore, never()).saveSavedCredentialsIfCurrent(any(), any(), any())
        verify(secureSecretsStore, never()).clearSavedCredentialsIfCurrent(any())
    }

    @Test
    fun `a successful remembered login persists credentials without changing auth success`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { login(any(), any()) } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        viewModel.updateUsername("user")
        viewModel.updatePassword("secret")
        viewModel.toggleRememberMe()

        viewModel.login()
        awaitCredentialSave("user", "secret")
        awaitLegacyCredentialCleanup()

        assertEquals(
            AuthState.LoggedIn(CurrentUser(id = "usr_test")),
            viewModel.authState.value,
        )
        assertEquals(null, viewModel.credentialStorageError.value)
    }

    @Test
    fun `a remembered credential write failure is surfaced without undoing login`() = runTest(testDispatcher) {
        authRepository.stub {
            onBlocking { login(any(), any()) } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))
                Unit
            }
        }
        whenever(secureSecretsStore.saveSavedCredentialsIfCurrent(any(), any(), any()))
            .thenAnswer { throw IOException("keystore unavailable") }
        val viewModel = buildViewModel()
        viewModel.updateUsername("user")
        viewModel.updatePassword("secret")
        viewModel.toggleRememberMe()

        viewModel.login()
        awaitCredentialSave("user", "secret")

        assertEquals(
            AuthState.LoggedIn(CurrentUser(id = "usr_test")),
            viewModel.authState.value,
        )
        assertFalse(viewModel.rememberMe.value)
        assertNotNull(viewModel.credentialStorageError.value)
    }

    @Test
    fun `an unreadable secure record becomes an explicit startup error`() = runTest(testDispatcher) {
        whenever(secureSecretsStore.readSavedCredentials())
            .thenReturn(SavedCredentialsRead(credentials = null, isReadable = false))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        verify(secureSecretsStore).readSavedCredentials()

        assertNotNull(viewModel.credentialStorageError.value)
        verify(secureSecretsStore, never()).saveSavedCredentialsIfCurrent(any(), any(), any())
    }

    @Test
    fun `session storage failures remain observable after authentication changes`() = runTest(testDispatcher) {
        authStorageError.value = "Signed in, but the session was not stored"
        authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_test"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            "Signed in, but the session was not stored",
            viewModel.credentialStorageError.value,
        )
        viewModel.dismissCredentialStorageError()
        verify(authRepository).dismissStorageError()
    }

    @Test
    fun `legacy credentials are retained when encrypted migration cannot land`() = runTest(testDispatcher) {
        preferences.stub {
            onBlocking { getLegacySavedCredentials() } doAnswer { "legacy" to "secret" }
        }
        whenever(secureSecretsStore.saveSavedCredentialsIfCurrent(any(), any(), any()))
            .thenReturn(CredentialMutationResult.FAILED)

        val viewModel = buildViewModel()
        awaitCredentialSave("legacy", "secret")

        assertEquals("legacy", viewModel.username.value)
        assertEquals("secret", viewModel.password.value)
        assertNotNull(viewModel.credentialStorageError.value)
        verify(preferences, never()).clearLegacySavedCredentials()
    }

    @Test
    fun `remembered login lands after a deferred legacy migration`() = runTest(testDispatcher) {
        val migrationStarted = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        whenever(preferences.getLegacySavedCredentials()).doSuspendableAnswer {
            migrationStarted.complete(Unit)
            releaseMigration.await()
            "legacy" to "old-secret"
        }
        authRepository.stub {
            onBlocking { login("account-b", "new-secret") } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_b"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        runCurrent()
        migrationStarted.await()
        viewModel.updateUsername("account-b")
        viewModel.updatePassword("new-secret")
        viewModel.toggleRememberMe()

        viewModel.login()
        runCurrent()
        releaseMigration.complete(Unit)
        advanceUntilIdle()

        val writes = inOrder(secureSecretsStore)
        writes.verify(secureSecretsStore)
            .saveSavedCredentialsIfCurrent(any(), eq("legacy"), eq("old-secret"))
        writes.verify(secureSecretsStore)
            .saveSavedCredentialsIfCurrent(any(), eq("account-b"), eq("new-secret"))
        assertTrue(viewModel.rememberMe.value)
    }

    @Test
    fun `non remembered login clears credentials after a deferred legacy migration`() = runTest(testDispatcher) {
        val migrationStarted = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        whenever(preferences.getLegacySavedCredentials()).doSuspendableAnswer {
            migrationStarted.complete(Unit)
            releaseMigration.await()
            "legacy" to "old-secret"
        }
        authRepository.stub {
            onBlocking { login("account-b", "new-secret") } doAnswer {
                authState.value = AuthState.LoggedIn(CurrentUser(id = "usr_b"))
                Unit
            }
        }
        val viewModel = buildViewModel()
        runCurrent()
        migrationStarted.await()
        viewModel.updateUsername("account-b")
        viewModel.updatePassword("new-secret")

        viewModel.login()
        runCurrent()
        releaseMigration.complete(Unit)
        advanceUntilIdle()

        val writes = inOrder(secureSecretsStore)
        writes.verify(secureSecretsStore)
            .saveSavedCredentialsIfCurrent(any(), eq("legacy"), eq("old-secret"))
        writes.verify(secureSecretsStore).clearSavedCredentialsIfCurrent(any())
        assertFalse(viewModel.rememberMe.value)
    }

    @Test
    fun `explicit logout clears credentials retained by the long-lived view model`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.updateUsername("user")
        viewModel.updatePassword("secret")
        viewModel.updateTwoFactorCode("123456")
        viewModel.togglePasswordVisibility()
        viewModel.toggleRememberMe()

        explicitLogoutSignal.publish()

        assertEquals("", viewModel.username.value)
        assertEquals("", viewModel.password.value)
        assertEquals("", viewModel.twoFactorCode.value)
        assertFalse(viewModel.passwordVisible.value)
        assertFalse(viewModel.rememberMe.value)

        viewModel.tryResumeSession()
        advanceUntilIdle()
        verify(authRepository, never()).login(any(), any())
    }

    private suspend fun TestScope.awaitLegacyCredentialCleanup() {
        advanceUntilIdle()
        verify(preferences).clearLegacySavedCredentials()
    }

    private fun TestScope.awaitCredentialSave(username: String, password: String) {
        advanceUntilIdle()
        verify(secureSecretsStore).saveSavedCredentialsIfCurrent(any(), eq(username), eq(password))
    }

    private fun buildViewModel(): LoginViewModel =
        LoginViewModel(authRepository, preferences, secureSecretsStore, explicitLogoutSignal, testDispatcher)
}
