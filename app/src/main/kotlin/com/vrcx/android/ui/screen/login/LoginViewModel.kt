package com.vrcx.android.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.security.SavedCredentials
import com.vrcx.android.data.security.SecureSecretsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferences: VrcxPreferences,
    private val secureSecretsStore: SecureSecretsStore,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _twoFactorCode = MutableStateFlow("")
    val twoFactorCode: StateFlow<String> = _twoFactorCode.asStateFlow()

    private val _passwordVisible = MutableStateFlow(false)
    val passwordVisible: StateFlow<Boolean> = _passwordVisible.asStateFlow()

    private val _rememberMe = MutableStateFlow(false)
    val rememberMe: StateFlow<Boolean> = _rememberMe.asStateFlow()

    init {
        viewModelScope.launch {
            val savedCredentials = withContext(Dispatchers.IO) {
                secureSecretsStore.getSavedCredentials() ?: preferences.getLegacySavedCredentials()?.let { legacy ->
                    secureSecretsStore.saveSavedCredentials(legacy.first, legacy.second)
                    preferences.clearLegacySavedCredentials()
                    SavedCredentials(
                        username = legacy.first,
                        password = legacy.second,
                    )
                }
            }
            if (savedCredentials != null) {
                _username.value = savedCredentials.username
                _password.value = savedCredentials.password
                _rememberMe.value = true
            }
        }
    }

    fun updateUsername(value: String) { _username.value = value }
    fun updatePassword(value: String) { _password.value = value }
    fun updateTwoFactorCode(value: String) { _twoFactorCode.value = value }
    fun togglePasswordVisibility() { _passwordVisible.value = !_passwordVisible.value }
    fun toggleRememberMe() { _rememberMe.value = !_rememberMe.value }

    fun login() {
        viewModelScope.launch {
            authRepository.login(_username.value, _password.value)
            if (_rememberMe.value && authRepository.authState.value is AuthState.LoggedIn) {
                withContext(Dispatchers.IO) {
                    secureSecretsStore.saveSavedCredentials(_username.value, _password.value)
                    preferences.clearLegacySavedCredentials()
                }
            } else if (!_rememberMe.value) {
                withContext(Dispatchers.IO) {
                    secureSecretsStore.clearSavedCredentials()
                    preferences.clearLegacySavedCredentials()
                }
            }
        }
    }

    fun submitTwoFactor(useEmail: Boolean = false) {
        viewModelScope.launch {
            if (useEmail) {
                authRepository.verifyEmailOtp(_twoFactorCode.value)
            } else {
                authRepository.verifyTotp(_twoFactorCode.value)
            }
            _twoFactorCode.value = ""
            if (_rememberMe.value && authRepository.authState.value is AuthState.LoggedIn) {
                withContext(Dispatchers.IO) {
                    secureSecretsStore.saveSavedCredentials(_username.value, _password.value)
                    preferences.clearLegacySavedCredentials()
                }
            } else if (!_rememberMe.value) {
                withContext(Dispatchers.IO) {
                    secureSecretsStore.clearSavedCredentials()
                    preferences.clearLegacySavedCredentials()
                }
            }
        }
    }

    fun tryResumeSession() {
        viewModelScope.launch {
            authRepository.tryResumeSession()
        }
    }
}
