package com.vrcx.android.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.ExplicitLogoutSignal
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import java.lang.AutoCloseable
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    preferences: VrcxPreferences,
    secureSecretsStore: SecureSecretsStore,
    private val explicitLogoutSignal: ExplicitLogoutSignal,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val form = LoginFormState()
    private val credentialStorage = LoginCredentialStorage(preferences, secureSecretsStore, ioDispatcher)
    private val credentialOwner = LoginCredentialOwner(credentialStorage, form)
    private val loginAttempts = LoginAttemptCoordinator(authRepository, credentialStorage, credentialOwner)
    private val explicitLogoutListener: () -> Unit = {
        credentialOwner.resetAfterExplicitLogout()
        loginAttempts.clear()
        form.resetAfterExplicitLogout()
    }

    val authState: StateFlow<AuthState> = authRepository.authState
    val username: StateFlow<String> = form.username
    val password: StateFlow<String> = form.password
    val twoFactorCode: StateFlow<String> = form.twoFactorCode
    val passwordVisible: StateFlow<Boolean> = form.passwordVisible
    val rememberMe: StateFlow<Boolean> = form.rememberMe
    val canResendEmailCode: StateFlow<Boolean> = loginAttempts.canResendEmailCode
    val credentialStorageError: StateFlow<String?> = combine(
        credentialStorage.error,
        authRepository.storageError,
    ) { localError, sessionError -> localError ?: sessionError }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            credentialStorage.error.value ?: authRepository.storageError.value,
        )

    init {
        explicitLogoutSignal.subscribe(explicitLogoutListener)
        addCloseable(AutoCloseable { explicitLogoutSignal.unsubscribe(explicitLogoutListener) })
        viewModelScope.launch { credentialOwner.load() }
    }

    fun updateUsername(value: String) = form.updateUsername(value)

    fun updatePassword(value: String) = form.updatePassword(value)

    fun updateTwoFactorCode(value: String) = form.updateTwoFactorCode(value)

    fun togglePasswordVisibility() = form.togglePasswordVisibility()

    fun toggleRememberMe() = form.toggleRememberMe()

    fun dismissCredentialStorageError() {
        if (!credentialStorage.dismissError()) authRepository.dismissStorageError()
    }

    fun login() {
        val attempt = loginAttempts.beginManual(form.snapshot())
        viewModelScope.launch {
            credentialOwner.markLoginAttempted()
            authRepository.login(attempt.username, attempt.password)
            loginAttempts.complete(attempt)
        }
    }

    fun submitTwoFactor(useEmail: Boolean = false) {
        val code = twoFactorCode.value
        if (!isTwoFactorCodeValid(code, useEmail)) return
        val attempt = loginAttempts.current()
        viewModelScope.launch {
            if (useEmail) {
                authRepository.verifyEmailOtp(code)
            } else {
                authRepository.verifyTotp(code)
            }
            if (authRepository.authState.value is AuthState.LoggedIn) {
                form.clearTwoFactorCodeIf(code)
            }
            if (attempt != null) loginAttempts.complete(attempt)
        }
    }

    fun resendEmailCode() {
        val attempt = loginAttempts.current() ?: return
        viewModelScope.launch {
            if (attempt.username.isBlank() || attempt.password.isBlank()) return@launch
            authRepository.resendEmailOtp(attempt.username, attempt.password)
            loginAttempts.complete(attempt)
        }
    }

    fun tryResumeSession() {
        viewModelScope.launch {
            authRepository.tryResumeSession()
            when (authRepository.authState.value) {
                is AuthState.LoggedIn, is AuthState.RequiresTwoFactor -> return@launch
                else -> Unit
            }
            // A temporarily unreachable stored session remains preferable to password login,
            // which would discard the session and may force another 2FA challenge.
            if (authRepository.hasResumableSession()) return@launch
            val savedCredentials = credentialOwner.autoLoginCredentials() ?: return@launch
            val attempt = loginAttempts.beginAuto(savedCredentials)
            authRepository.login(attempt.username, attempt.password)
            loginAttempts.complete(attempt)
        }
    }
}
