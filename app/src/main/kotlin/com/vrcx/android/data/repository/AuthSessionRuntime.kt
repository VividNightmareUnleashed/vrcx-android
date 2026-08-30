package com.vrcx.android.data.repository

import com.vrcx.android.data.api.RequestDeduplicator
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the process-wide coordination that must move with an authenticated session. */
@Singleton
internal class AuthSessionRuntime @Inject constructor(
    private val accountScope: AccountScope,
    private val authenticatedSessionGate: AuthenticatedSessionGate,
    private val requestDeduplicator: RequestDeduplicator,
    private val explicitLogoutSignal: ExplicitLogoutSignal,
) {
    suspend fun <T> transition(block: suspend () -> T): T = authenticatedSessionGate.transition(block)

    fun currentAccount(): AccountScope.Token = accountScope.current()

    fun isAccountCurrent(token: AccountScope.Token): Boolean = accountScope.isCurrent(token)

    fun bindAccount(ownerUserId: String) {
        accountScope.bind(ownerUserId)
    }

    fun invalidateAccount() {
        accountScope.invalidate()
    }

    fun clearRequestCache() {
        requestDeduplicator.clearCache()
    }

    fun publishExplicitLogout() {
        explicitLogoutSignal.publish()
    }
}
