package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.PipelineSession
import kotlinx.coroutines.flow.Flow

// Stored values are stable so enum reordering cannot reinterpret persisted service state.
internal enum class ServiceMode(val storedValue: Int) {
    NONE(0),
    FOREGROUND(1),
    NON_FOREGROUND(2),
    TIMED_OUT(TIMED_OUT_STORED_VALUE),
    ;

    companion object {
        fun fromStored(value: Int): ServiceMode = values().firstOrNull { it.storedValue == value } ?: NONE
    }
}

internal suspend fun watchPipelineSession(
    authState: Flow<AuthState>,
    expectedUserId: String,
    stop: () -> Unit,
    restart: () -> Unit,
) {
    authState.collect { state ->
        when {
            state is AuthState.NotLoggedIn -> stop()
            state is AuthState.RequiresTwoFactor -> stop()
            state is AuthState.LoggedIn && state.user.id != expectedUserId -> restart()
        }
    }
}

internal fun isCurrentPipelineOrigin(accountScope: AccountScope, origin: AccountScope.Token): Boolean =
    origin.ownerUserId.isNotEmpty() && accountScope.isCurrent(origin)

internal fun shouldRestartPipelineStartup(accountScope: AccountScope, session: PipelineSession?): Boolean =
    session == null || !isCurrentPipelineOrigin(accountScope, session.account)

internal enum class SessionStartup { RETRY, STOP }

// Transient resume failures retain their cookies and remain eligible for retry.
internal fun sessionStartupDecision(authState: AuthState, hasResumableSession: Boolean): SessionStartup = when {
    !hasResumableSession -> SessionStartup.STOP
    authState is AuthState.RequiresTwoFactor -> SessionStartup.STOP
    else -> SessionStartup.RETRY
}

private const val SESSION_RETRY_BASE_DELAY_MS = 10_000L
private const val SESSION_RETRY_MAX_DELAY_MS = 300_000L
private const val SESSION_RETRY_MAX_EXPONENT = 10
private const val TIMED_OUT_STORED_VALUE = 3

internal fun sessionRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(SESSION_RETRY_MAX_EXPONENT)
    return minOf(SESSION_RETRY_BASE_DELAY_MS shl exponent, SESSION_RETRY_MAX_DELAY_MS)
}
