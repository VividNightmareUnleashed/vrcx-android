package com.vrcx.android.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the shared authenticated CookieJar stable for one request or session transition. */
@Singleton
class AuthenticatedSessionGate @Inject constructor(private val accountScope: AccountScope) {
    private val mutex = Mutex()

    suspend fun <T> transition(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> request(token: AccountScope.Token, block: suspend () -> T): T = mutex.withLock {
        accountScope.ensureCurrent(token)
        block()
    }
}
