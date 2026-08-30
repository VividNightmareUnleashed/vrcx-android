package com.vrcx.android.data.repository

import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.CookieStorageStatus
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Owns cookie I/O and the user-visible durability status derived from it. */
internal class AuthCookieStore(private val cookieJar: CookieJarImpl, private val ioDispatcher: CoroutineDispatcher) {
    private val _storageError = MutableStateFlow(cookieStorageError(cookieJar.storageStatus.value))
    val storageError: StateFlow<String?> = _storageError.asStateFlow()
    val storageStatus: CookieStorageStatus get() = cookieJar.storageStatus.value

    suspend fun monitorStorageStatus() {
        cookieJar.storageStatus.collect { status ->
            cookieStorageError(status)?.let { message ->
                if (cookieJar.storageStatus.value == status) {
                    _storageError.update { current -> current ?: message }
                }
            }
        }
    }

    suspend fun snapshot(): Map<String, String> = withContext(ioDispatcher) { cookieJar.snapshot() }

    suspend fun clearForLoginAttempt() {
        withContext(ioDispatcher) { cookieJar.clearAll() }
    }

    suspend fun commitAuthenticatedSession(): Boolean = runCatchingCancellable {
        withContext(ioDispatcher) { cookieJar.commitAuthenticatedSession() }
    }.getOrDefault(false)

    suspend fun restore(snapshot: Map<String, String>): Boolean = withContext(NonCancellable + ioDispatcher) {
        cookieJar.restore(snapshot)
    }

    suspend fun hasAuthCookie(): Boolean = withContext(ioDispatcher) { cookieJar.getAuthCookie() != null }

    suspend fun clearSession() {
        withContext(NonCancellable + ioDispatcher) { cookieJar.clearAll() }
    }

    suspend fun completeLogoutAfterSecretsDeleted(): Boolean = runCatchingCancellable {
        withContext(ioDispatcher) { cookieJar.completeLogoutAfterSecretsDeleted() }
    }.getOrDefault(false)

    fun reportError(message: String) {
        _storageError.value = message
    }

    fun dismissError() {
        _storageError.value = null
    }
}

/** Completes the explicit-logout durability promise without owning session state. */
internal class AuthLogoutStorage(
    private val secureSecretsStore: SecureSecretsStore,
    private val preferences: VrcxPreferences,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun forgetStoredSecrets(cookies: AuthCookieStore) = withContext(ioDispatcher) {
        var cleanupFailed = false
        val encryptedSecretsCleared = runCatchingCancellable {
            secureSecretsStore.clearAll()
        }.getOrDefault(false)
        if (!encryptedSecretsCleared) cleanupFailed = true

        if (encryptedSecretsCleared && !cookies.completeLogoutAfterSecretsDeleted()) {
            cleanupFailed = true
        }

        if (runCatchingCancellable { preferences.clearLegacySavedCredentials() }.isFailure) {
            cleanupFailed = true
        }
        if (cleanupFailed) cookies.reportError(LOGOUT_STORAGE_ERROR)
    }
}

private fun cookieStorageError(status: CookieStorageStatus): String? = when (status) {
    CookieStorageStatus.READY -> null
    CookieStorageStatus.UNREADABLE -> COOKIE_STORAGE_UNREADABLE_ERROR
    CookieStorageStatus.WRITE_FAILED -> COOKIE_STORAGE_WRITE_ERROR
    CookieStorageStatus.LEGACY_CLEANUP_FAILED -> COOKIE_LEGACY_CLEANUP_ERROR
}

internal const val COOKIE_STORAGE_UNREADABLE_ERROR =
    "Saved session data couldn't be read. Sign in again to replace it safely."
internal const val COOKIE_STORAGE_WRITE_ERROR =
    "Saved session data couldn't be moved to secure storage."
internal const val AUTHENTICATED_SESSION_STORAGE_ERROR =
    "VRChat accepted the sign-in, but the new session couldn't be saved securely."
internal const val COOKIE_LEGACY_CLEANUP_ERROR =
    "The new session is secure, but an older saved session copy couldn't be removed."
internal const val LOGOUT_STORAGE_ERROR =
    "Signed out, but some saved sign-in data couldn't be removed from this device."
internal const val COOKIE_RESTORE_ERROR =
    "The previous saved session couldn't be restored after sign-in failed."
