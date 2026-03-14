package com.vrcx.android.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _twoFactorCode = MutableStateFlow("")
    val twoFactorCode: StateFlow<String> = _twoFactorCode.asStateFlow()

    fun updateUsername(value: String) { _username.value = value }
    fun updatePassword(value: String) { _password.value = value }
    fun updateTwoFactorCode(value: String) { _twoFactorCode.value = value }

    fun login() {
        viewModelScope.launch {
            authRepository.login(_username.value, _password.value)
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
        }
    }

    fun tryResumeSession() {
        viewModelScope.launch {
            authRepository.tryResumeSession()
        }
    }
}
