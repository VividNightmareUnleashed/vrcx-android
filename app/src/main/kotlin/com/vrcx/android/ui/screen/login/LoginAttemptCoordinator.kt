package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.security.CredentialWriteToken
import com.vrcx.android.data.security.SavedCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LoginAttempt(
    val id: Long,
    val username: String,
    val password: String,
    val credentialPolicy: CredentialPolicy,
    val credentialWriteToken: CredentialWriteToken,
)

internal enum class CredentialPolicy {
    SAVE,
    CLEAR,
    KEEP,
}

internal class LoginAttemptCoordinator(
    private val authRepository: AuthRepository,
    private val credentialStorage: LoginCredentialStorage,
    private val credentialOwner: LoginCredentialOwner,
) {
    private val mutableCanResendEmailCode = MutableStateFlow(false)
    val canResendEmailCode: StateFlow<Boolean> = mutableCanResendEmailCode.asStateFlow()

    private var nextAttemptId = 0L
    private var pendingAttempt: LoginAttempt? = null

    fun beginManual(form: LoginFormSnapshot): LoginAttempt = begin(
        username = form.username,
        password = form.password,
        policy = if (form.rememberMe) CredentialPolicy.SAVE else CredentialPolicy.CLEAR,
    )

    fun beginAuto(credentials: SavedCredentials): LoginAttempt = begin(
        username = credentials.username,
        password = credentials.password,
        policy = CredentialPolicy.KEEP,
    )

    fun current(): LoginAttempt? = pendingAttempt

    fun clear() {
        pendingAttempt = null
        mutableCanResendEmailCode.value = false
    }

    suspend fun complete(attempt: LoginAttempt) {
        if (!isCurrent(attempt)) return
        when (authRepository.authState.value) {
            is AuthState.LoggedIn -> {
                credentialOwner.persist(
                    attempt = attempt,
                    isCurrent = { isCurrent(attempt) },
                    isLoggedIn = { authRepository.authState.value is AuthState.LoggedIn },
                )
                if (isCurrent(attempt)) clear()
            }

            is AuthState.RequiresTwoFactor -> Unit

            else -> clear()
        }
    }

    private fun begin(username: String, password: String, policy: CredentialPolicy): LoginAttempt {
        val attempt = LoginAttempt(
            id = ++nextAttemptId,
            username = username,
            password = password,
            credentialPolicy = policy,
            credentialWriteToken = credentialStorage.credentialWriteToken(),
        )
        pendingAttempt = attempt
        mutableCanResendEmailCode.value = username.isNotBlank() && password.isNotBlank()
        return attempt
    }

    private fun isCurrent(attempt: LoginAttempt): Boolean = pendingAttempt?.id == attempt.id
}
