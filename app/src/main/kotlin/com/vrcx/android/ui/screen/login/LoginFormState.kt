package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.security.SavedCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LoginFormSnapshot(
    val username: String,
    val password: String,
    val rememberMe: Boolean,
    val credentialsEdited: Boolean,
)

internal class LoginFormState {
    private val mutableUsername = MutableStateFlow("")
    val username: StateFlow<String> = mutableUsername.asStateFlow()

    private val mutablePassword = MutableStateFlow("")
    val password: StateFlow<String> = mutablePassword.asStateFlow()

    private val mutableTwoFactorCode = MutableStateFlow("")
    val twoFactorCode: StateFlow<String> = mutableTwoFactorCode.asStateFlow()

    private val mutablePasswordVisible = MutableStateFlow(false)
    val passwordVisible: StateFlow<Boolean> = mutablePasswordVisible.asStateFlow()

    private val mutableRememberMe = MutableStateFlow(false)
    val rememberMe: StateFlow<Boolean> = mutableRememberMe.asStateFlow()

    private var usernameEdited = false
    private var passwordEdited = false
    private var rememberMeEdited = false

    fun updateUsername(value: String) {
        usernameEdited = true
        mutableUsername.value = value
    }

    fun updatePassword(value: String) {
        passwordEdited = true
        mutablePassword.value = value
    }

    fun updateTwoFactorCode(value: String) {
        mutableTwoFactorCode.value = value
    }

    fun togglePasswordVisibility() {
        mutablePasswordVisible.value = !mutablePasswordVisible.value
    }

    fun toggleRememberMe() {
        rememberMeEdited = true
        mutableRememberMe.value = !mutableRememberMe.value
    }

    fun snapshot(): LoginFormSnapshot = LoginFormSnapshot(
        username = mutableUsername.value,
        password = mutablePassword.value,
        rememberMe = mutableRememberMe.value,
        credentialsEdited = usernameEdited || passwordEdited || rememberMeEdited,
    )

    fun applySavedCredentials(credentials: SavedCredentials) {
        if (!usernameEdited) mutableUsername.value = credentials.username
        if (!passwordEdited) mutablePassword.value = credentials.password
        if (!rememberMeEdited) mutableRememberMe.value = true
    }

    fun setRememberMe(value: Boolean) {
        mutableRememberMe.value = value
    }

    fun clearTwoFactorCodeIf(value: String) {
        if (mutableTwoFactorCode.value == value) mutableTwoFactorCode.value = ""
    }

    fun resetAfterExplicitLogout() {
        usernameEdited = false
        passwordEdited = false
        rememberMeEdited = false
        mutableUsername.value = ""
        mutablePassword.value = ""
        mutableTwoFactorCode.value = ""
        mutablePasswordVisible.value = false
        mutableRememberMe.value = false
    }
}
