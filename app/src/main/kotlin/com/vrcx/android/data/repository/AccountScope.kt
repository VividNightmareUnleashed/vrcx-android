package com.vrcx.android.data.repository

import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime state that belongs to one signed-in account and must not outlive it.
 *
 * Implementations register through [AccountScope.bindTo], which is also how they
 * obtain the scope their guards read — holding account-scoped state and being
 * reset when that account goes away are the same registration, so a repository
 * cannot have one without the other.
 */
interface AccountScoped {
    /**
     * Drops everything belonging to the account that is going away. Runs under
     * the scope lock, so it must stay cheap: no blocking, no suspension, no
     * network.
     */
    fun clearRuntimeState()
}

/**
 * Aborts an in-flight load whose account the app has since left. Stays a
 * [CancellationException] so it unwinds a multi-step load without being
 * reported as a failure, but is distinguishable from genuine cancellation.
 */
class AccountChangedException(
    message: String = "Invalidated by account change",
) : CancellationException(message)

/**
 * The signed-in account's id plus a generation counter that advances on every
 * account change. This is the whole of the per-account isolation rule: work that
 * started under one account captures a [Token] before its network call and
 * re-checks it before publishing, so the previous account's late results cannot
 * land in the new session.
 *
 * [publishIfCurrent] and [invalidate] take the same lock, and [invalidate]
 * advances the generation before it resets anything — so a publish racing an
 * account change either lands first and is then cleared, or sees the new
 * generation and is skipped. There is no window in between, which is what the
 * per-repository copies of this rule could not guarantee.
 */
@Singleton
class AccountScope @Inject constructor() {

    /** The account a unit of work started under. */
    data class Token(val ownerUserId: String, val generation: Long)

    private val lock = Any()
    private val members = CopyOnWriteArrayList<AccountScoped>()
    private var generation = 0L
    private var owner = ""

    /** The signed-in user's id, blank while no account is bound. */
    val ownerUserId: String get() = synchronized(lock) { owner }

    /** Registers [member] for teardown and hands back the scope its guards read. */
    fun bindTo(member: AccountScoped): AccountScope {
        members.addIfAbsent(member)
        return this
    }

    /** Captures the account the caller is about to do work on behalf of. */
    fun current(): Token = synchronized(lock) { Token(owner, generation) }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { isCurrentLocked(token) }

    /**
     * Runs [publish] only while [token]'s account is still the signed-in one,
     * and returns whether it ran. This is the silent-skip half of the guard: the
     * caller has nothing left to do either way.
     */
    fun publishIfCurrent(token: Token, publish: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrentLocked(token)) return false
        publish()
        true
    }

    /**
     * The throwing half of the guard, for a multi-step sequence that cannot
     * express "abandon the rest of this" as a return value — a paginator fetcher
     * or a several-request write path.
     */
    fun ensureCurrent(token: Token) {
        if (!isCurrent(token)) throw AccountChangedException()
    }

    /** [publishIfCurrent] for a sequence whose remaining steps are meaningless once it skips. */
    fun publishOrAbort(token: Token, publish: () -> Unit) {
        if (!publishIfCurrent(token, publish)) throw AccountChangedException()
    }

    /** Points the scope at a freshly signed-in account. */
    fun bind(ownerUserId: String) {
        synchronized(lock) { owner = ownerUserId }
    }

    /**
     * Ends the current account: advances the generation so work already in
     * flight can no longer publish, then resets every bound member.
     */
    fun invalidate() {
        synchronized(lock) {
            generation++
            owner = ""
            members.forEach(AccountScoped::clearRuntimeState)
        }
    }

    private fun isCurrentLocked(token: Token): Boolean =
        token.generation == generation && token.ownerUserId == owner
}
