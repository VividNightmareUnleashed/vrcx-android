package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException

/** Credential-entry operations exposed by [AuthRepository]. */
interface AuthLoginActions {
    suspend fun login(username: String, password: String)
    suspend fun resendEmailOtp(username: String, password: String)
    suspend fun verifyTotp(code: String)
    suspend fun verifyEmailOtp(code: String)
}

/** Owns login attempts and the two-factor challenge they establish. */
internal class DefaultAuthLoginActions(
    private val state: AuthSessionState,
    private val remote: AuthRemoteGateway,
    private val cookies: AuthCookieStore,
    private val publisher: AuthSessionPublisher,
    private val sessionRuntime: AuthSessionRuntime,
) : AuthLoginActions {
    override suspend fun login(username: String, password: String) {
        sessionRuntime.transition {
            login(state.beginTransition(), username, password)
        }
    }

    override suspend fun resendEmailOtp(username: String, password: String) {
        sessionRuntime.transition {
            val session = state.beginTransition()
            if (publisher.resetSessionIfCurrent(session)) {
                login(session, username, password)
            }
        }
    }

    override suspend fun verifyTotp(code: String) {
        val normalized = code.filter(Char::isLetterOrDigit).ifEmpty { code }
        val isRecoveryCode = normalized.length == RECOVERY_CODE_LENGTH
        val submittedCode = if (isRecoveryCode) {
            val split = RECOVERY_CODE_HALF_LENGTH
            "${normalized.substring(0, split)}-${normalized.substring(split)}"
        } else {
            normalized
        }
        sessionRuntime.transition {
            verifyTwoFactor(state.beginTransition()) {
                remote.verifyTotp(submittedCode, isRecoveryCode)
            }
        }
    }

    override suspend fun verifyEmailOtp(code: String) {
        sessionRuntime.transition {
            verifyTwoFactor(state.beginTransition()) { remote.verifyEmailOtp(code) }
        }
    }

    private suspend fun login(session: SessionToken, username: String, password: String) {
        // A stored cookie outranks Basic auth on auth/user. Preserve it long
        // enough to restore an inconclusive attempt, but never a rejected one.
        val storedCookies = cookies.snapshot()
        var sessionCommitted = false
        try {
            val failure = runCatchingCancellable {
                if (!state.publish(session) { authState.value = AuthState.LoggingIn }) {
                    return@runCatchingCancellable
                }
                cookies.clearForLoginAttempt()
                if (!state.isCurrent(session)) return@runCatchingCancellable

                when (val response = remote.login(username, password)) {
                    is RemoteLoginResult.TwoFactorRequired -> {
                        state.withCurrent(session) {
                            authState.value = AuthState.RequiresTwoFactor(response.methods)
                        }
                    }

                    is RemoteLoginResult.Authenticated -> {
                        if (publisher.commitAuthenticatedSession(session)) {
                            sessionCommitted = publisher.publishLoginSuccess(session, response.user)
                            if (sessionCommitted) publisher.fetchAuthToken(session)
                        }
                    }
                }
            }.exceptionOrNull()
            if (failure != null) {
                if (!sessionCommitted && !isCredentialRejection(failure)) {
                    publisher.restoreCookiesIfCurrent(session, storedCookies)
                }
                publisher.setErrorUnlessLoggedOut(session, failure.message ?: LOGIN_FAILED_MESSAGE)
            }
        } catch (cancellation: CancellationException) {
            if (!sessionCommitted) publisher.restoreCookiesIfCurrent(session, storedCookies)
            throw cancellation
        }
    }

    private suspend fun verifyTwoFactor(session: SessionToken, submit: suspend () -> TwoFactorAuthResponse) {
        val phase = state.withCurrent(session) {
            authState.value as? AuthState.RequiresTwoFactor
        }
        if (phase != null) {
            val started = state.publish(session) {
                authState.value = phase.copy(verification = TwoFactorVerification.InProgress)
            }
            if (started) {
                val failure = runCatchingCancellable {
                    if (submit().verified) {
                        fetchCurrentUserAfterVerification(session)
                    } else {
                        publisher.setTwoFactorError(session, VERIFICATION_FAILED_MESSAGE)
                    }
                }.exceptionOrNull()
                if (failure != null) {
                    publisher.setTwoFactorError(
                        session,
                        failure.message ?: VERIFICATION_FAILED_MESSAGE,
                    )
                }
            }
        }
    }

    private suspend fun fetchCurrentUserAfterVerification(session: SessionToken) {
        when (val check = remote.checkSession()) {
            is SessionCheck.Active -> {
                if (publisher.commitAuthenticatedSession(session)) {
                    publisher.onLoginSuccess(session, check.user)
                }
            }

            is SessionCheck.TwoFactorRequired -> state.withCurrent(session) {
                authState.value = AuthState.RequiresTwoFactor(check.methods)
            }

            is SessionCheck.Rejected -> publisher.setErrorUnlessLoggedOut(session, check.message)

            is SessionCheck.Inconclusive -> publisher.setErrorUnlessLoggedOut(session, check.message)
        }
    }

    private companion object {
        const val RECOVERY_CODE_LENGTH = 8
        const val RECOVERY_CODE_HALF_LENGTH = 4
        const val LOGIN_FAILED_MESSAGE = "Login failed"
        const val VERIFICATION_FAILED_MESSAGE = "Verification failed"
    }
}
