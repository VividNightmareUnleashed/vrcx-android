package com.vrcx.android.ui.screen.login

import com.vrcx.android.data.security.CredentialMutationResult
import com.vrcx.android.data.security.SavedCredentials
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LoginCredentialOwner(private val storage: LoginCredentialStorage, private val form: LoginFormState) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var cachedSavedCredentials: SavedCredentials? = null
    private var savedCredentialsLoaded = false
    private var autoLoginAttempted = false

    fun markLoginAttempted() {
        autoLoginAttempted = true
    }

    fun resetAfterExplicitLogout() {
        generation.incrementAndGet()
        cachedSavedCredentials = null
        savedCredentialsLoaded = true
        autoLoginAttempted = true
    }

    suspend fun load(): SavedCredentials? = mutex.withLock {
        val loadGeneration = generation.get()
        val writeToken = storage.credentialWriteToken()
        if (loadGeneration > 0L) return@withLock null
        if (savedCredentialsLoaded) return@withLock cachedSavedCredentials

        val loadResult = storage.load(writeToken) { loadGeneration == generation.get() }
        if (loadResult !is SavedCredentialsLoadResult.Loaded) return@withLock null
        if (loadGeneration != generation.get()) return@withLock null

        cachedSavedCredentials = loadResult.credentials
        savedCredentialsLoaded = true
        loadResult.credentials?.let(form::applySavedCredentials)
        loadResult.credentials
    }

    suspend fun persist(attempt: LoginAttempt, isCurrent: () -> Boolean, isLoggedIn: () -> Boolean) = mutex.withLock {
        if (!isCurrent() || !isLoggedIn()) return@withLock
        when (attempt.credentialPolicy) {
            CredentialPolicy.SAVE -> save(attempt, isCurrent)
            CredentialPolicy.CLEAR -> forget(attempt, isCurrent)
            CredentialPolicy.KEEP -> Unit
        }
    }

    suspend fun autoLoginCredentials(): SavedCredentials? {
        if (autoLoginAttempted) return null
        val credentials = load() ?: return null
        if (generation.get() > 0L || form.snapshot().credentialsEdited) return null
        if (storage.isAutoLoginEnabled() != true) return null
        autoLoginAttempted = true
        return credentials
    }

    private suspend fun save(attempt: LoginAttempt, isCurrent: () -> Boolean) {
        val result = storage.save(attempt)
        when (result.getOrNull()) {
            CredentialMutationResult.STALE -> return

            CredentialMutationResult.APPLIED -> {
                cachedSavedCredentials = SavedCredentials(attempt.username, attempt.password)
                savedCredentialsLoaded = true
                if (isCurrent()) form.setRememberMe(true)
                storage.clearLegacy(CredentialStorageFailure.LEGACY_CLEANUP)
            }

            else -> if (isCurrent()) {
                form.setRememberMe(false)
                storage.report(CredentialStorageFailure.SAVE, result.exceptionOrNull())
            }
        }
    }

    private suspend fun forget(attempt: LoginAttempt, isCurrent: () -> Boolean) {
        val result = storage.clear(attempt)
        when (result.getOrNull()) {
            CredentialMutationResult.STALE -> return

            CredentialMutationResult.APPLIED -> {
                cachedSavedCredentials = null
                savedCredentialsLoaded = true
                if (isCurrent()) form.setRememberMe(false)
            }

            else -> if (isCurrent()) {
                storage.report(CredentialStorageFailure.CLEAR, result.exceptionOrNull())
            }
        }
        storage.clearLegacy(CredentialStorageFailure.CLEAR)
    }
}
