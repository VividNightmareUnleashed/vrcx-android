package com.vrcx.android.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferences: VrcxPreferences,
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
        // Load saved credentials
        viewModelScope.launch {
            val savedUser = preferences.savedUsername.first()
            val savedPass = preferences.savedPassword.first()
            if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
                _username.value = savedUser
                _password.value = savedPass
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
            // Save credentials after successful login if remember me is checked
            if (_rememberMe.value && authRepository.authState.value is AuthState.LoggedIn) {
                preferences.setSavedCredentials(_username.value, _password.value)
            } else if (!_rememberMe.value) {
                preferences.clearSavedCredentials()
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
            // Save credentials after 2FA success
            if (_rememberMe.value && authRepository.authState.value is AuthState.LoggedIn) {
                preferences.setSavedCredentials(_username.value, _password.value)
            }
        }
    }

    fun tryResumeSession() {
        viewModelScope.launch {
            authRepository.tryResumeSession()
        }
    }
}
