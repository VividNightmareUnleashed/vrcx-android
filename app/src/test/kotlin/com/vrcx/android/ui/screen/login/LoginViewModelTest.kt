package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.timeout
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
            awaitCredentialWrite()
        }

    @Test
    fun `an authenticator challenge is answered on the totp endpoint`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        viewModel.updateTwoFactorCode("123456")

        viewModel.submitTwoFactor(useEmail = false)
        advanceUntilIdle()

        verify(authRepository).verifyTotp("123456")
        verify(authRepository, never()).verifyEmailOtp(any())
        awaitCredentialWrite()
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
        awaitCredentialWrite()
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
        awaitCredentialWrite()
    }

    @Test
    fun `an unresolved sign-in leaves the stored credentials alone`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        viewModel.toggleRememberMe()

        viewModel.login()
        advanceUntilIdle()

        // Remember-me with the attempt still on a 2FA challenge is not a decision
        // either way, so neither branch of the policy may fire.
        verify(secureSecretsStore, never()).saveSavedCredentials(any(), any())
        verify(secureSecretsStore, never()).clearSavedCredentials()
    }

    /**
     * Remember-me off means the stored credentials are cleared, and the store is
     * blocking so the ViewModel writes it on [kotlinx.coroutines.Dispatchers.IO].
     * Waiting for that hop — and letting it resume — keeps the write inside the
     * test rather than landing after the main dispatcher has been reset.
     */
    private fun TestScope.awaitCredentialWrite() {
        verify(secureSecretsStore, timeout(CREDENTIAL_WRITE_TIMEOUT_MS)).clearSavedCredentials()
        advanceUntilIdle()
    }

    private fun buildViewModel(): LoginViewModel {
        whenever(authRepository.authState).thenReturn(authState)
        return LoginViewModel(authRepository, preferences, secureSecretsStore)
    }

    private companion object {
        const val CREDENTIAL_WRITE_TIMEOUT_MS = 5_000L
    }
}
