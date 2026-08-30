package com.vrcx.android.data.repository

import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the in-memory state and atomic mutations for one account's friend pipeline. */
internal class FriendRuntimeState(
    internal val account: AccountScope,
    private val scope: CoroutineScope,
    private val publishNotifyIds: (Set<String>) -> Unit,
) {
    private val friendsRevision = AtomicLong(0)

    // A snapshot may replace only friends that no pipeline frame touched after its first request.
    private val friendRevisions = ConcurrentHashMap<String, Long>()
    private val pendingOfflineJobs = ConcurrentHashMap<String, Job>()
    internal val pendingOfflineIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal var offlineDelayMs = DEFAULT_OFFLINE_DELAY_MS

    private val friendsMutex = Mutex()
    private val _friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
    val friends: StateFlow<Map<String, FriendContext>> = _friends.asStateFlow()

    private val _friendTransitions = MutableSharedFlow<AccountScopedEvent<FriendTransition>>(extraBufferCapacity = 64)
    val friendTransitions: SharedFlow<AccountScopedEvent<FriendTransition>> = _friendTransitions.asSharedFlow()

    // Account teardown cancels emitters suspended behind SharedFlow backpressure.
    private val pendingTransitionEmitters: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    fun clear() {
        friendsRevision.incrementAndGet()
        pendingOfflineJobs.values.forEach(Job::cancel)
        pendingOfflineJobs.clear()
        pendingTransitionEmitters.forEach(Job::cancel)
        pendingTransitionEmitters.clear()
        friendRevisions.clear()
        pendingOfflineIds.clear()
        _friends.value = emptyMap()
    }

    fun snapshotRevision(): Long = friendsRevision.get()

    suspend fun commitSnapshot(
        token: AccountScope.Token,
        fetched: Map<String, FriendContext>,
        revision: Long,
    ): Map<String, FriendContext>? = friendsMutex.withLock {
        val merged = mergeFriendSnapshot(fetched, _friends.value, friendRevisions, revision)
        val published = account.publishIfCurrent(token) {
            supersededOfflineConfirmations(
                pendingIds = pendingOfflineIds,
                fetched = fetched,
                friendRevisions = friendRevisions,
                revision = revision,
            ).forEach(::cancelPendingOffline)
            _friends.value = merged
            friendsRevision.incrementAndGet()
        }
        if (published) merged else null
    }

    fun publishNotifyIds(token: AccountScope.Token, enabledIds: Set<String>): Boolean =
        account.publishIfCurrent(token) { publishNotifyIds(enabledIds) }

    suspend fun updateFriend(
        userId: String,
        token: AccountScope.Token,
        onPublish: () -> Unit = {},
        update: (FriendContext) -> FriendContext,
    ): FriendContext? = friendsMutex.withLock {
        val current = _friends.value.toMutableMap()
        val existing = current[userId]
            ?: FriendContext(id = userId, name = userId, state = FriendState.OFFLINE)
        current[userId] = update(existing)
        val published = account.publishIfCurrent(token) {
            onPublish()
            _friends.value = current
            friendRevisions[userId] = friendsRevision.incrementAndGet()
        }
        if (published) existing else null
    }

    suspend fun removeFriend(userId: String, token: AccountScope.Token) {
        friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            current.remove(userId)
            account.publishIfCurrent(token) {
                _friends.value = current
                friendRevisions[userId] = friendsRevision.incrementAndGet()
            }
        }
    }

    fun scheduleOfflineConfirmation(token: AccountScope.Token, userId: String, onConfirmed: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(offlineDelayMs)
            if (confirmOffline(userId, token)) onConfirmed()
            // A later offline frame may already own a different confirmation job for this user.
            pendingOfflineJobs.remove(userId, coroutineContext[Job])
        }
        var registered = false
        val current = account.publishIfCurrent(token) {
            if (userId in pendingOfflineIds) {
                pendingOfflineJobs.put(userId, job)?.cancel()
                registered = true
                job.start()
            }
        }
        if (!current || !registered) job.cancel()
    }

    private suspend fun confirmOffline(userId: String, token: AccountScope.Token): Boolean = friendsMutex.withLock {
        var confirmed = false
        account.publishIfCurrent(token) {
            if (!pendingOfflineIds.remove(userId)) return@publishIfCurrent
            val current = _friends.value.toMutableMap()
            val existing = current[userId]
                ?: FriendContext(id = userId, name = userId, state = FriendState.OFFLINE)
            current[userId] = existing.copy(
                state = FriendState.OFFLINE,
                ref = existing.ref?.copy(
                    location = "offline",
                    travelingToLocation = "offline",
                    travelingToWorld = "offline",
                    travelingToInstance = "offline",
                    instanceId = "offline",
                ),
            )
            _friends.value = current
            friendRevisions[userId] = friendsRevision.incrementAndGet()
            confirmed = true
        }
        confirmed
    }

    fun cancelPendingOffline(userId: String) {
        pendingOfflineIds.remove(userId)
        pendingOfflineJobs.remove(userId)?.cancel()
    }

    suspend fun emitTransition(token: AccountScope.Token, transition: FriendTransition) {
        val emitter = currentCoroutineContext().job
        val registered = account.publishIfCurrent(token) {
            pendingTransitionEmitters.add(emitter)
        }
        if (!registered) return
        try {
            account.ensureCurrent(token)
            _friendTransitions.emit(AccountScopedEvent(origin = token, value = transition))
        } finally {
            pendingTransitionEmitters.remove(emitter)
        }
    }

    private companion object {
        const val DEFAULT_OFFLINE_DELAY_MS = 5_000L
    }
}

private fun mergeFriendSnapshot(
    fetched: Map<String, FriendContext>,
    live: Map<String, FriendContext>,
    friendRevisions: Map<String, Long>,
    revision: Long,
): Map<String, FriendContext> {
    val merged = LinkedHashMap<String, FriendContext>(fetched.size)
    for ((userId, context) in fetched) {
        if (changedSince(friendRevisions, userId, revision)) {
            live[userId]?.let { merged[userId] = it }
        } else {
            merged[userId] = context
        }
    }
    for ((userId, context) in live) {
        if (userId !in fetched && changedSince(friendRevisions, userId, revision)) merged[userId] = context
    }
    return merged
}

private fun changedSince(friendRevisions: Map<String, Long>, userId: String, revision: Long): Boolean =
    (friendRevisions[userId] ?: 0L) > revision

private fun supersededOfflineConfirmations(
    pendingIds: Set<String>,
    fetched: Map<String, FriendContext>,
    friendRevisions: Map<String, Long>,
    revision: Long,
): List<String> = pendingIds.filter { userId ->
    val authoritativeState = fetched[userId]?.state
    !changedSince(friendRevisions, userId, revision) && authoritativeState != FriendState.OFFLINE
}
