package com.vrcx.android.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Stored-session operations exposed by [AuthRepository]. */
interface AuthSessionActions {
    suspend fun fetchCurrentUser()
    suspend fun fetchAuthToken()
    suspend fun ensureSessionReady(): Boolean
    suspend fun hasResumableSession(): Boolean
    suspend fun tryResumeSession()
    suspend fun logout()
}

/** Owns stored-session resume, readiness, and explicit logout. */
internal class DefaultAuthSessionActions(
    private val state: AuthSessionState,
    private val remote: AuthRemoteGateway,
    private val cookies: AuthCookieStore,
    private val publisher: AuthSessionPublisher,
    private val logoutStorage: AuthLogoutStorage,
    private val sessionRuntime: AuthSessionRuntime,
) : AuthSessionActions {
    override suspend fun fetchCurrentUser() {
        sessionRuntime.transition {
            val session = state.beginTransition()
            when (val check = remote.checkSession()) {
                is SessionCheck.Active -> publisher.onLoginSuccess(session, check.user)

                is SessionCheck.TwoFactorRequired -> state.withCurrent(session) {
                    authState.value = AuthState.RequiresTwoFactor(check.methods)
                }

                is SessionCheck.Rejected -> publisher.setErrorUnlessLoggedOut(session, check.message)

                is SessionCheck.Inconclusive -> publisher.setErrorUnlessLoggedOut(session, check.message)
            }
        }
    }

    override suspend fun fetchAuthToken() {
        sessionRuntime.transition {
            publisher.fetchAuthToken(state.beginTransition())
        }
    }

    override suspend fun ensureSessionReady(): Boolean {
        if (state.isReady) return true

        var ready = false
        sessionRuntime.transition {
            val session = state.beginTransition()
            if (!state.isReady) {
                if (state.currentUser == null) tryResumeSession(session)
                if (state.isCurrent(session) && state.currentUser != null && state.authToken.isNullOrBlank()) {
                    publisher.fetchAuthToken(session)
                }
            }
            ready = state.isCurrent(session) && state.isReady
        }
        return ready
    }

    override suspend fun hasResumableSession(): Boolean = cookies.hasAuthCookie()

    override suspend fun tryResumeSession() {
        sessionRuntime.transition {
            tryResumeSession(state.beginTransition())
        }
    }

    override suspend fun logout() {
        val session = state.beginTransition()
        var cancellation: CancellationException? = null
        try {
            sessionRuntime.transition {
                if (state.isCurrent(session)) {
                    try {
                        remote.logout()
                    } catch (failure: CancellationException) {
                        cancellation = failure
                    }
                    withContext(NonCancellable) { completeExplicitLogout(session) }
                }
            }
        } catch (failure: CancellationException) {
            // Cancellation while queued behind another cookie-bearing request
            // must still complete local sign-out. A newer generation still wins.
            cancellation = failure
            withContext(NonCancellable) {
                sessionRuntime.transition { completeExplicitLogout(session) }
            }
        }
        cancellation?.let { throw it }
    }

    private suspend fun tryResumeSession(session: SessionToken) {
        val resumeStarted = state.authState.value !is AuthState.LoggedIn &&
            hasResumableSession() &&
            state.publish(session) { authState.value = AuthState.LoggingIn }
        if (resumeStarted) runResumeAttempts(session)
    }

    private suspend fun runResumeAttempts(session: SessionToken) {
        var lastFailure: SessionCheck.Inconclusive? = null
        for (delayMs in RESUME_RETRY_DELAYS_MS) {
            when (val check = checkSessionAfterDelay(session, delayMs) ?: return) {
                is SessionCheck.Inconclusive -> lastFailure = check

                else -> {
                    when (check) {
                        is SessionCheck.Active -> publisher.onLoginSuccess(session, check.user)

                        is SessionCheck.TwoFactorRequired -> state.withCurrent(session) {
                            authState.value = AuthState.RequiresTwoFactor(check.methods)
                        }

                        is SessionCheck.Rejected -> publisher.endSessionIfCurrent(session)

                        is SessionCheck.Inconclusive -> Unit
                    }
                    return
                }
            }
        }
        publisher.setErrorUnlessLoggedOut(session, lastFailure?.message ?: UNREACHABLE_MESSAGE)
    }

    private suspend fun checkSessionAfterDelay(session: SessionToken, delayMs: Long): SessionCheck? {
        if (!state.isCurrent(session)) return null
        if (delayMs > NO_RESUME_DELAY_MS) delay(delayMs)
        return if (state.isCurrent(session)) remote.checkSession() else null
    }

    private suspend fun completeExplicitLogout(session: SessionToken) {
        if (state.isCurrent(session)) {
            sessionRuntime.publishExplicitLogout()
            try {
                logoutStorage.forgetStoredSecrets(cookies)
            } finally {
                publisher.endSessionIfCurrent(session)
            }
        }
    }

    private companion object {
        const val NO_RESUME_DELAY_MS = 0L
        val RESUME_RETRY_DELAYS_MS = longArrayOf(NO_RESUME_DELAY_MS, 2_000L, 5_000L)
    }
}
