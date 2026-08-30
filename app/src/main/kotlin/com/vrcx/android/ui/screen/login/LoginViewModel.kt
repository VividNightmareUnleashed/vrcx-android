package com.vrcx.android.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.ExplicitLogoutSignal
import com.vrcx.android.data.security.CredentialMutationResult
import com.vrcx.android.data.security.CredentialWriteToken
import com.vrcx.android.data.security.SavedCredentials
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferences: VrcxPreferences,
    private val secureSecretsStore: SecureSecretsStore,
    private val explicitLogoutSignal: ExplicitLogoutSignal,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

    private val _canResendEmailCode = MutableStateFlow(false)
    val canResendEmailCode: StateFlow<Boolean> = _canResendEmailCode.asStateFlow()

    private val localCredentialStorageError = MutableStateFlow<String?>(null)
    val credentialStorageError: StateFlow<String?> = combine(
        localCredentialStorageError,
        authRepository.storageError,
    ) { localError, sessionError -> localError ?: sessionError }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            localCredentialStorageError.value ?: authRepository.storageError.value,
        )

    private val logger = Logger.getLogger(TAG)
    private val savedCredentialsMutex = Mutex()
    private var cachedSavedCredentials: SavedCredentials? = null
    private var savedCredentialsLoaded = false
    private var autoLoginAttempted = false
    private var usernameEdited = false
    private var passwordEdited = false
    private var rememberMeEdited = false
    private val credentialGeneration = AtomicLong()
    private var nextLoginAttemptId = 0L
    private var pendingLoginAttempt: LoginAttempt? = null
    private val explicitLogoutListener: () -> Unit = ::clearCredentialsAfterExplicitLogout

    init {
        explicitLogoutSignal.subscribe(explicitLogoutListener)
        viewModelScope.launch {
            ensureSavedCredentialsLoaded()
        }
    }

    private fun clearCredentialsAfterExplicitLogout() {
        credentialGeneration.incrementAndGet()
        cachedSavedCredentials = null
        savedCredentialsLoaded = true
        autoLoginAttempted = true
        usernameEdited = false
        passwordEdited = false
        rememberMeEdited = false
        setPendingLoginAttempt(null)
        _username.value = ""
        _password.value = ""
        _twoFactorCode.value = ""
        _passwordVisible.value = false
        _rememberMe.value = false
    }

    override fun onCleared() {
        explicitLogoutSignal.unsubscribe(explicitLogoutListener)
        super.onCleared()
    }

    fun updateUsername(value: String) {
        usernameEdited = true
        _username.value = value
    }
    fun updatePassword(value: String) {
        passwordEdited = true
        _password.value = value
    }
    fun updateTwoFactorCode(value: String) {
        _twoFactorCode.value = value
    }
    fun togglePasswordVisibility() {
        _passwordVisible.value = !_passwordVisible.value
    }
    fun toggleRememberMe() {
        rememberMeEdited = true
        _rememberMe.value = !_rememberMe.value
    }
    fun dismissCredentialStorageError() {
        if (localCredentialStorageError.value != null) {
            localCredentialStorageError.value = null
        } else {
            authRepository.dismissStorageError()
        }
    }

    fun login() {
        val attempt = LoginAttempt(
            id = ++nextLoginAttemptId,
            username = _username.value,
            password = _password.value,
            credentialPolicy = if (_rememberMe.value) {
                CredentialPolicy.SAVE
            } else {
                CredentialPolicy.CLEAR
            },
            credentialWriteToken = secureSecretsStore.credentialWriteToken(),
        )
        setPendingLoginAttempt(attempt)
        viewModelScope.launch {
            autoLoginAttempted = true
            authRepository.login(attempt.username, attempt.password)
            completeLoginAttempt(attempt)
        }
    }

    fun submitTwoFactor(useEmail: Boolean = false) {
        val code = _twoFactorCode.value
        if (!isTwoFactorCodeValid(code, useEmail)) return
        val attempt = pendingLoginAttempt
        viewModelScope.launch {
            if (useEmail) {
                authRepository.verifyEmailOtp(code)
            } else {
                authRepository.verifyTotp(code)
            }
            // Only a successful attempt clears the field. A network drop or a
            // rate limit would otherwise make the user re-read a rolled TOTP or
            // retype a recovery code off paper.
            if (authRepository.authState.value is AuthState.LoggedIn &&
                _twoFactorCode.value == code
            ) {
                _twoFactorCode.value = ""
            }
            if (attempt != null) completeLoginAttempt(attempt)
        }
    }

    /**
     * The remember-credentials policy for both sign-in entry points. Authentication
     * remains successful if local persistence fails; the failed promise is surfaced,
     * and remember-me is switched off when a requested save did not land.
     */
    private suspend fun completeLoginAttempt(attempt: LoginAttempt) {
        if (pendingLoginAttempt?.id != attempt.id) return
        val authState = authRepository.authState.value
        if (authState is AuthState.LoggedIn) {
            persistRememberedCredentials(attempt)
            if (pendingLoginAttempt?.id == attempt.id) setPendingLoginAttempt(null)
        } else if (authState !is AuthState.RequiresTwoFactor) {
            setPendingLoginAttempt(null)
        }
    }

    private suspend fun persistRememberedCredentials(attempt: LoginAttempt) = savedCredentialsMutex.withLock {
        if (pendingLoginAttempt?.id != attempt.id || authRepository.authState.value !is AuthState.LoggedIn) {
            return@withLock
        }
        when (attempt.credentialPolicy) {
            CredentialPolicy.SAVE -> saveRememberedCredentials(attempt)
            CredentialPolicy.CLEAR -> forgetRememberedCredentials(attempt)
            CredentialPolicy.KEEP -> Unit
        }
    }

    private suspend fun saveRememberedCredentials(attempt: LoginAttempt) {
        val credentials = SavedCredentials(attempt.username, attempt.password)
        val result = runCatchingCancellable {
            withContext(ioDispatcher) {
                secureSecretsStore.saveSavedCredentialsIfCurrent(
                    attempt.credentialWriteToken,
                    credentials.username,
                    credentials.password,
                )
            }
        }
        if (result.getOrNull() == CredentialMutationResult.STALE) return
        if (result.getOrNull() != CredentialMutationResult.APPLIED) {
            if (pendingLoginAttempt?.id == attempt.id) {
                _rememberMe.value = false
                reportCredentialStorageFailure(SAVE_CREDENTIALS_ERROR, result.exceptionOrNull())
            }
            return
        }

        cachedSavedCredentials = credentials
        savedCredentialsLoaded = true
        if (pendingLoginAttempt?.id == attempt.id) _rememberMe.value = true
        clearLegacySavedCredentials(LEGACY_CLEANUP_ERROR)
    }

    private suspend fun forgetRememberedCredentials(attempt: LoginAttempt) {
        val result = runCatchingCancellable {
            withContext(ioDispatcher) {
                secureSecretsStore.clearSavedCredentialsIfCurrent(attempt.credentialWriteToken)
            }
        }
        if (result.getOrNull() == CredentialMutationResult.STALE) return
        if (result.getOrNull() != CredentialMutationResult.APPLIED) {
            if (pendingLoginAttempt?.id == attempt.id) {
                reportCredentialStorageFailure(CLEAR_CREDENTIALS_ERROR, result.exceptionOrNull())
            }
        } else {
            cachedSavedCredentials = null
            savedCredentialsLoaded = true
            if (pendingLoginAttempt?.id == attempt.id) _rememberMe.value = false
        }
        clearLegacySavedCredentials(CLEAR_CREDENTIALS_ERROR)
    }

    private suspend fun clearLegacySavedCredentials(errorMessage: String) {
        runCatchingCancellable {
            withContext(ioDispatcher) { preferences.clearLegacySavedCredentials() }
        }.exceptionOrNull()?.let { failure ->
            reportCredentialStorageFailure(errorMessage, failure)
        }
    }

    fun resendEmailCode() {
        val attempt = pendingLoginAttempt ?: return
        viewModelScope.launch {
            if (attempt.username.isBlank() || attempt.password.isBlank()) {
                return@launch
            }
            authRepository.resendEmailOtp(attempt.username, attempt.password)
            completeLoginAttempt(attempt)
        }
    }

    private fun setPendingLoginAttempt(attempt: LoginAttempt?) {
        pendingLoginAttempt = attempt
        _canResendEmailCode.value = attempt != null &&
            attempt.username.isNotBlank() &&
            attempt.password.isNotBlank()
    }

    fun tryResumeSession() {
        viewModelScope.launch {
            authRepository.tryResumeSession()
            when (authRepository.authState.value) {
                is AuthState.LoggedIn, is AuthState.RequiresTwoFactor -> return@launch
                else -> Unit
            }
            // A stored session that merely failed to reach the server is still
            // good. Re-logging in with the saved password would throw it away
            // and drag the user back through the 2FA challenge, so leave it be
            // and let the next launch (or a manual sign-in) resume it.
            if (authRepository.hasResumableSession()) return@launch
            maybeAutoLogin()
        }
    }

    private suspend fun ensureSavedCredentialsLoaded(): SavedCredentials? = savedCredentialsMutex.withLock {
        val generation = credentialGeneration.get()
        val writeToken = secureSecretsStore.credentialWriteToken()
        if (generation > 0L) return@withLock null
        if (savedCredentialsLoaded) return@withLock cachedSavedCredentials

        val secureRead = runCatchingCancellable {
            withContext(ioDispatcher) { secureSecretsStore.readSavedCredentials() }
        }.getOrElse { failure ->
            reportCredentialStorageFailure(LOAD_CREDENTIALS_ERROR, failure)
            return@withLock null
        }
        if (!secureRead.isReadable) {
            reportCredentialStorageFailure(LOAD_CREDENTIALS_ERROR)
            return@withLock null
        }

        if (generation != credentialGeneration.get()) return@withLock null
        val savedCredentials = secureRead.credentials ?: loadAndMigrateLegacyCredentials(writeToken)

        if (generation != credentialGeneration.get()) return@withLock null
        cachedSavedCredentials = savedCredentials
        savedCredentialsLoaded = true

        if (savedCredentials != null) {
            if (!usernameEdited) _username.value = savedCredentials.username
            if (!passwordEdited) _password.value = savedCredentials.password
            if (!rememberMeEdited) _rememberMe.value = true
        }

        savedCredentials
    }

    private suspend fun loadAndMigrateLegacyCredentials(token: CredentialWriteToken): SavedCredentials? {
        val legacyRead = runCatchingCancellable {
            withContext(ioDispatcher) { preferences.getLegacySavedCredentials() }
        }
        val legacy = legacyRead.getOrNull()
        if (legacy == null) {
            legacyRead.exceptionOrNull()?.let { failure ->
                reportCredentialStorageFailure(LOAD_CREDENTIALS_ERROR, failure)
            }
            return null
        }

        val credentials = SavedCredentials(username = legacy.first, password = legacy.second)
        val migration = runCatchingCancellable {
            withContext(ioDispatcher) {
                secureSecretsStore.saveSavedCredentialsIfCurrent(
                    token,
                    credentials.username,
                    credentials.password,
                )
            }
        }
        return when (migration.getOrNull()) {
            CredentialMutationResult.APPLIED -> {
                clearLegacySavedCredentials(LEGACY_CLEANUP_ERROR)
                credentials
            }

            CredentialMutationResult.STALE -> null

            else -> {
                // The DataStore copy is intentionally retained until the encrypted
                // write is known to have landed.
                reportCredentialStorageFailure(SAVE_CREDENTIALS_ERROR, migration.exceptionOrNull())
                credentials
            }
        }
    }

    private suspend fun maybeAutoLogin() {
        if (autoLoginAttempted) return
        val savedCredentials = ensureSavedCredentialsLoaded() ?: return
        if (credentialGeneration.get() > 0L) return
        if (usernameEdited || passwordEdited || rememberMeEdited) return
        val autoLoginEnabled = runCatchingCancellable { preferences.autoLogin.first() }
            .getOrElse { failure ->
                reportCredentialStorageFailure(AUTO_LOGIN_ERROR, failure)
                return
            }
        if (!autoLoginEnabled) return

        autoLoginAttempted = true
        val attempt = LoginAttempt(
            id = ++nextLoginAttemptId,
            username = savedCredentials.username,
            password = savedCredentials.password,
            credentialPolicy = CredentialPolicy.KEEP,
            credentialWriteToken = secureSecretsStore.credentialWriteToken(),
        )
        setPendingLoginAttempt(attempt)
        authRepository.login(attempt.username, attempt.password)
        completeLoginAttempt(attempt)
    }

    private fun reportCredentialStorageFailure(message: String, failure: Throwable? = null) {
        if (failure == null) {
            logger.warning(message)
        } else {
            logger.log(Level.SEVERE, message, failure)
        }
        localCredentialStorageError.value = message
    }

    private companion object {
        const val TAG = "LoginViewModel"
        const val LOAD_CREDENTIALS_ERROR =
            "Saved sign-in details couldn't be loaded. Sign in manually."
        const val SAVE_CREDENTIALS_ERROR =
            "Signed in, but your credentials couldn't be saved on this device."
        const val CLEAR_CREDENTIALS_ERROR =
            "Saved credentials couldn't be removed from this device."
        const val LEGACY_CLEANUP_ERROR =
            "Credentials were secured, but an older saved copy couldn't be removed."
        const val AUTO_LOGIN_ERROR =
            "Automatic sign-in settings couldn't be loaded. Sign in manually."
    }
}

private data class LoginAttempt(
    val id: Long,
    val username: String,
    val password: String,
    val credentialPolicy: CredentialPolicy,
    val credentialWriteToken: CredentialWriteToken,
)

private enum class CredentialPolicy {
    SAVE,
    CLEAR,
    KEEP,
}
