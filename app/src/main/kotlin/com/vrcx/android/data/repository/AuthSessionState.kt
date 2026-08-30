package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.CurrentUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SessionToken(val generation: Long)

internal class MutableAuthSession(private val runtime: AuthSessionRuntime) {
    val authState = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    var currentUser: CurrentUser? = null
    var authToken: String? = null

    fun clear() {
        runtime.invalidateAccount()
        currentUser = null
        authToken = null
        runtime.clearRequestCache()
        authState.value = AuthState.NotLoggedIn
    }

    fun replaceAccount(user: CurrentUser) {
        runtime.invalidateAccount()
        authToken = null
        currentUser = user
        authState.value = AuthState.LoggedIn(user)
        runtime.bindAccount(user.id)
    }
}

/** Owns the auth generation and every mutable field that must publish atomically with it. */
internal class AuthSessionState(private val runtime: AuthSessionRuntime) {
    private val lock = Any()
    private val mutable = MutableAuthSession(runtime)
    private var generation = 0L

    val authState: StateFlow<AuthState> = mutable.authState.asStateFlow()
    val currentUser: CurrentUser? get() = synchronized(lock) { mutable.currentUser }
    val authToken: String? get() = synchronized(lock) { mutable.authToken }
    val isReady: Boolean get() = synchronized(lock) {
        mutable.currentUser != null && !mutable.authToken.isNullOrBlank() &&
            mutable.authState.value is AuthState.LoggedIn
    }
    val hasRuntimeSession: Boolean get() = synchronized(lock) {
        mutable.currentUser != null || mutable.authToken != null
    }
    val pipelineSession: PipelineSession? get() = synchronized(lock) {
        if (mutable.authState.value !is AuthState.LoggedIn) return@synchronized null
        val userId = mutable.currentUser?.id ?: return@synchronized null
        val token = mutable.authToken ?: return@synchronized null
        val account = runtime.currentAccount()
        if (account.ownerUserId == userId) PipelineSession(token, account) else null
    }

    fun beginTransition(): SessionToken = synchronized(lock) {
        SessionToken(++generation)
    }

    fun beginPipelineTransition(expectedAccount: AccountScope.Token): SessionToken? = synchronized(lock) {
        val account = runtime.currentAccount()
        if (account != expectedAccount || mutable.currentUser?.id != account.ownerUserId) {
            null
        } else {
            SessionToken(++generation)
        }
    }

    fun capture(): SessionToken = synchronized(lock) { SessionToken(generation) }

    fun isCurrent(session: SessionToken): Boolean = synchronized(lock) {
        session.generation == generation
    }

    fun publish(session: SessionToken, block: MutableAuthSession.() -> Unit): Boolean = synchronized(lock) {
        if (session.generation != generation) return false
        mutable.block()
        true
    }

    fun <T> withCurrent(session: SessionToken, block: MutableAuthSession.() -> T): T? = synchronized(lock) {
        if (session.generation == generation) mutable.block() else null
    }

    fun mutateForAccount(token: AccountScope.Token, block: MutableAuthSession.() -> Unit): Boolean =
        synchronized(lock) {
            if (token.ownerUserId.isEmpty() || !runtime.isAccountCurrent(token)) return false
            mutable.block()
            true
        }

    fun clearIfCurrent(session: SessionToken, advanceGeneration: Boolean): Boolean = synchronized(lock) {
        if (session.generation != generation) {
            false
        } else {
            if (advanceGeneration) generation++
            mutable.clear()
            true
        }
    }
}
