package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.CredentialMutationResult
import com.vrcx.android.data.security.CredentialWriteToken
import com.vrcx.android.data.security.SavedCredentials
import com.vrcx.android.data.security.SavedCredentialsRead
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.util.runCatchingCancellable
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal sealed interface SavedCredentialsLoadResult {
    data class Loaded(val credentials: SavedCredentials?) : SavedCredentialsLoadResult

    data object Failed : SavedCredentialsLoadResult
}

internal enum class CredentialStorageFailure(val message: String) {
    LOAD("Saved sign-in details couldn't be loaded. Sign in manually."),
    SAVE("Signed in, but your credentials couldn't be saved on this device."),
    CLEAR("Saved credentials couldn't be removed from this device."),
    LEGACY_CLEANUP("Credentials were secured, but an older saved copy couldn't be removed."),
    AUTO_LOGIN("Automatic sign-in settings couldn't be loaded. Sign in manually."),
}

internal class LoginCredentialStorage(
    private val preferences: VrcxPreferences,
    private val secureSecretsStore: SecureSecretsStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val logger = Logger.getLogger(TAG)
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun dismissError(): Boolean {
        val dismissed = mutableError.value != null
        if (dismissed) mutableError.value = null
        return dismissed
    }

    fun credentialWriteToken(): CredentialWriteToken = secureSecretsStore.credentialWriteToken()

    suspend fun load(token: CredentialWriteToken, isCurrent: () -> Boolean): SavedCredentialsLoadResult {
        val secureRead = readSecureCredentials()
        return when {
            secureRead == null -> SavedCredentialsLoadResult.Failed

            !secureRead.isReadable -> {
                report(CredentialStorageFailure.LOAD)
                SavedCredentialsLoadResult.Failed
            }

            else -> SavedCredentialsLoadResult.Loaded(
                secureRead.credentials ?: if (isCurrent()) {
                    loadAndMigrateLegacyCredentials(token)
                } else {
                    null
                },
            )
        }
    }

    suspend fun save(attempt: LoginAttempt): Result<CredentialMutationResult> = runCatchingCancellable {
        withContext(ioDispatcher) {
            secureSecretsStore.saveSavedCredentialsIfCurrent(
                attempt.credentialWriteToken,
                attempt.username,
                attempt.password,
            )
        }
    }

    suspend fun clear(attempt: LoginAttempt): Result<CredentialMutationResult> = runCatchingCancellable {
        withContext(ioDispatcher) {
            secureSecretsStore.clearSavedCredentialsIfCurrent(attempt.credentialWriteToken)
        }
    }

    suspend fun clearLegacy(failureType: CredentialStorageFailure) {
        runCatchingCancellable {
            withContext(ioDispatcher) { preferences.clearLegacySavedCredentials() }
        }.exceptionOrNull()?.let { failure -> report(failureType, failure) }
    }

    suspend fun isAutoLoginEnabled(): Boolean? = runCatchingCancellable {
        preferences.autoLogin.first()
    }.getOrElse { failure ->
        report(CredentialStorageFailure.AUTO_LOGIN, failure)
        null
    }

    fun report(failureType: CredentialStorageFailure, failure: Throwable? = null) {
        if (failure == null) {
            logger.warning(failureType.message)
        } else {
            logger.log(Level.SEVERE, failureType.message, failure)
        }
        mutableError.value = failureType.message
    }

    private suspend fun readSecureCredentials(): SavedCredentialsRead? = runCatchingCancellable {
        withContext(ioDispatcher) { secureSecretsStore.readSavedCredentials() }
    }.getOrElse { failure ->
        report(CredentialStorageFailure.LOAD, failure)
        null
    }

    private suspend fun loadAndMigrateLegacyCredentials(token: CredentialWriteToken): SavedCredentials? {
        val legacyRead = runCatchingCancellable {
            withContext(ioDispatcher) { preferences.getLegacySavedCredentials() }
        }
        val legacy = legacyRead.getOrNull()
        if (legacy == null) {
            legacyRead.exceptionOrNull()?.let { failure -> report(CredentialStorageFailure.LOAD, failure) }
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
                clearLegacy(CredentialStorageFailure.LEGACY_CLEANUP)
                credentials
            }

            CredentialMutationResult.STALE -> null

            else -> {
                // Retain the DataStore copy until the encrypted write is known to have landed.
                report(CredentialStorageFailure.SAVE, migration.exceptionOrNull())
                credentials
            }
        }
    }

    private companion object {
        const val TAG = "LoginCredentialStorage"
    }
}
