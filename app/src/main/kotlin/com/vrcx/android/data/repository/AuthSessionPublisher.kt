package com.vrcx.android.data.repository

import com.vrcx.android.data.api.CookieStorageStatus
import com.vrcx.android.data.api.model.CurrentUser
import kotlinx.coroutines.flow.update

/** Publishes authenticated state only while the initiating session generation still owns it. */
internal class AuthSessionPublisher(
    private val state: AuthSessionState,
    private val remote: AuthRemoteGateway,
    private val cookies: AuthCookieStore,
) {
    suspend fun onLoginSuccess(session: SessionToken, user: CurrentUser) {
        if (publishLoginSuccess(session, user)) fetchAuthToken(session)
    }

    suspend fun commitAuthenticatedSession(session: SessionToken): Boolean {
        val committed = cookies.commitAuthenticatedSession()
        val sessionCurrent = state.isCurrent(session)
        if (sessionCurrent && !committed) {
            // Publishing while the old durable blob can still win on restart
            // would create a split session, so fail closed and remove cookies.
            cookies.clearSession()
            cookies.reportError(AUTHENTICATED_SESSION_STORAGE_ERROR)
            setErrorUnlessLoggedOut(session, AUTHENTICATED_SESSION_STORAGE_ERROR)
        } else if (sessionCurrent) {
            if (cookies.storageStatus == CookieStorageStatus.LEGACY_CLEANUP_FAILED) {
                cookies.reportError(COOKIE_LEGACY_CLEANUP_ERROR)
            } else {
                cookies.dismissError()
            }
        }
        return sessionCurrent && committed
    }

    suspend fun restoreCookiesIfCurrent(session: SessionToken, snapshot: Map<String, String>) {
        val shouldRestore = state.isCurrent(session)
        val restored = if (shouldRestore) cookies.restore(snapshot) else true
        if (state.isCurrent(session) && !restored) {
            cookies.reportError(COOKIE_RESTORE_ERROR)
        }
    }

    fun publishLoginSuccess(session: SessionToken, user: CurrentUser): Boolean = state.withCurrent(session) {
        if (authState.value is AuthState.NotLoggedIn) {
            false
        } else {
            replaceAccount(user)
            true
        }
    } == true

    suspend fun fetchAuthToken(session: SessionToken) {
        remote.fetchAuthToken()?.let { token ->
            state.withCurrent(session) { authToken = token }
        }
    }

    suspend fun endSessionIfCurrent(session: SessionToken): Boolean {
        val ended = state.clearIfCurrent(session, advanceGeneration = true)
        if (ended) cookies.clearSession()
        return ended
    }

    suspend fun resetSessionIfCurrent(session: SessionToken): Boolean {
        val reset = state.clearIfCurrent(session, advanceGeneration = false)
        if (reset) cookies.clearSession()
        return reset && state.isCurrent(session)
    }

    fun setErrorUnlessLoggedOut(session: SessionToken, message: String) {
        state.withCurrent(session) {
            authState.update { current ->
                if (current is AuthState.NotLoggedIn) current else AuthState.Error(message)
            }
        }
    }

    fun setTwoFactorError(session: SessionToken, message: String) {
        state.withCurrent(session) {
            authState.update { current ->
                if (current is AuthState.RequiresTwoFactor) {
                    current.copy(verification = TwoFactorVerification.Failed(message))
                } else {
                    current
                }
            }
        }
    }
}
