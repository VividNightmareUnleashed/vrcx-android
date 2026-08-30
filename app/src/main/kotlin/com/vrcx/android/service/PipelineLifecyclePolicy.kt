package com.vrcx.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.PipelineSession
import com.vrcx.android.data.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

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

/** Persists the requested service lifecycle independently of a service instance. */
internal object PipelineServiceStateStore {
    fun mode(context: Context): ServiceMode = ServiceMode.fromStored(
        preferences(context).getInt(KEY_SERVICE_MODE, ServiceMode.NONE.storedValue),
    )

    fun set(context: Context, mode: ServiceMode, synchronous: Boolean = false) {
        val editor = preferences(context).edit().putInt(KEY_SERVICE_MODE, mode.storedValue)
        if (synchronous) persistBeforePossibleProcessExit(editor) else editor.apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)

    /** The timeout callback may be followed immediately by process teardown. */
    @SuppressLint("ApplySharedPref")
    private fun persistBeforePossibleProcessExit(editor: SharedPreferences.Editor) {
        editor.commit()
    }

    private const val SERVICE_STATE_PREFERENCES = "websocket_service_state"
    private const val KEY_SERVICE_MODE = "requested_mode"
}

/** Starts a replay-zero collector and does not return until its subscription exists. */
internal suspend fun <T> launchSubscribedCollector(
    scope: CoroutineScope,
    events: SharedFlow<T>,
    consume: suspend (T) -> Unit,
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val collector = scope.launch {
        events
            .onSubscription { subscribed.complete(Unit) }
            .collect { event -> consume(event) }
    }
    collector.invokeOnCompletion { cause ->
        subscribed.completeExceptionally(
            cause ?: IllegalStateException("Event collector completed before subscribing"),
        )
    }
    try {
        subscribed.await()
    } catch (cancellation: CancellationException) {
        collector.cancel(cancellation)
        throw cancellation
    }
    return collector
}

/** Starts a state collector and waits until its first value has been applied. */
internal suspend fun <T> launchInitialValueCollector(
    scope: CoroutineScope,
    values: Flow<T>,
    consume: (T) -> Unit,
): Job {
    val ready = CompletableDeferred<Unit>()
    val collector = scope.launch {
        val failure = runCatchingCancellable {
            values.collect { value ->
                consume(value)
                ready.complete(Unit)
            }
        }.exceptionOrNull()
        when {
            failure != null -> ready.completeExceptionally(failure)

            !ready.isCompleted -> ready.completeExceptionally(
                IllegalStateException("State collector completed before its initial value"),
            )
        }
    }
    collector.invokeOnCompletion { cause ->
        if (cause != null) ready.completeExceptionally(cause)
    }
    try {
        ready.await()
    } catch (cancellation: CancellationException) {
        collector.cancel(cancellation)
        throw cancellation
    }
    return collector
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

/** Waits through transient cold-start failures while a resumable session remains. */
internal suspend fun awaitPipelineSession(authRepository: AuthRepository): Boolean {
    var attempt = 0
    while (true) {
        if (authRepository.ensureSessionReady()) return true
        val decision = sessionStartupDecision(
            authState = authRepository.authState.value,
            hasResumableSession = authRepository.hasResumableSession(),
        )
        if (decision == SessionStartup.STOP) return false
        attempt++
        Log.d(SERVICE_LOG_TAG, "Session not ready; retrying attempt $attempt")
        delay(sessionRetryDelayMs(attempt))
    }
}

private const val SESSION_RETRY_BASE_DELAY_MS = 10_000L
private const val SESSION_RETRY_MAX_DELAY_MS = 300_000L
private const val SESSION_RETRY_MAX_EXPONENT = 10
private const val TIMED_OUT_STORED_VALUE = 3

internal fun sessionRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(SESSION_RETRY_MAX_EXPONENT)
    return minOf(SESSION_RETRY_BASE_DELAY_MS shl exponent, SESSION_RETRY_MAX_DELAY_MS)
}
