package com.vrcx.android.data.repository

import com.vrcx.android.data.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Serializes writes per friend and owns the account-generation lifetime of queued work. */
internal class FriendActivityWriteCoordinator(private val account: AccountScope, ioDispatcher: CoroutineDispatcher) {
    private val lifetimeJob = SupervisorJob()
    private val scope = CoroutineScope(lifetimeJob + ioDispatcher)
    private val lifecycleLock = Any()
    private var accountJob: Job = SupervisorJob(lifetimeJob)

    // Each job awaits the tail it replaced, preserving order for one friend without blocking other friends.
    private val userWriteTails = HashMap<String, Job>()
    private val recentWrites = ConcurrentHashMap<String, Long>()
    private val logger = Logger.getLogger(FriendActivityRecorder::class.java.name)

    /**
     * A filtered offline/private hop after the latest GPS row proves that a
     * return to the same location is a revisit rather than a pipeline re-emit.
     */
    private val lastFilteredTransitionAt = ConcurrentHashMap<String, Long>()

    fun clearRuntimeState() {
        val previousJob = synchronized(lifecycleLock) {
            val previous = accountJob
            accountJob = SupervisorJob(lifetimeJob)
            userWriteTails.clear()
            clearDedupeMarkers()
            previous
        }
        previousJob.cancel()
    }

    fun resetDedupe(token: AccountScope.Token) {
        account.publishIfCurrent(token) {
            synchronized(lifecycleLock) { clearDedupeMarkers() }
        }
    }

    fun markFilteredTransition(token: AccountScope.Token, userId: String) {
        account.publishIfCurrent(token) {
            lastFilteredTransitionAt[userId] = System.currentTimeMillis()
        }
    }

    fun hasFilteredTransitionAfter(userId: String, timestamp: Long): Boolean =
        (lastFilteredTransitionAt[userId] ?: 0L) > timestamp

    fun launchRecord(
        token: AccountScope.Token,
        userId: String,
        key: String,
        write: suspend (ownerId: String) -> Unit,
    ) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        val job = synchronized(lifecycleLock) {
            val parent = accountJob
            if (!parent.isActive) return
            val predecessor = userWriteTails[userId]
            scope.launch(parent, start = CoroutineStart.LAZY) {
                predecessor?.join()
                account.ensureCurrent(token)
                val reservation = synchronized(lifecycleLock) {
                    if (parent !== accountJob || !parent.isActive) null else reserveWrite(key)
                } ?: return@launch

                var writeSucceeded = false
                try {
                    val failure = runCatchingCancellable { write(token.ownerUserId) }.exceptionOrNull()
                    if (failure == null) {
                        writeSucceeded = true
                    } else {
                        logger.log(Level.WARNING, "Friend activity write failed", failure)
                    }
                } finally {
                    if (!writeSucceeded) rollbackReservation(key, reservation)
                }
            }.also { userWriteTails[userId] = it }
        }
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (userWriteTails[userId] === job) userWriteTails.remove(userId)
            }
        }
        job.start()
    }

    private fun clearDedupeMarkers() {
        recentWrites.clear()
        lastFilteredTransitionAt.clear()
    }

    private fun reserveWrite(key: String): Long? {
        val now = System.nanoTime() / NANOS_PER_MILLISECOND
        var reservation: Long? = null
        recentWrites.compute(key) { _, lastWrite ->
            if (lastWrite != null && now - lastWrite < DEDUP_WINDOW_MS) {
                lastWrite
            } else {
                reservation = now
                now
            }
        }
        if (reservation != null && recentWrites.size > MAX_RECENT_WRITES) {
            recentWrites.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        return reservation
    }

    private fun rollbackReservation(key: String, reservation: Long) {
        recentWrites.remove(key, reservation)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEDUP_WINDOW_MS = 10_000L
        const val MAX_RECENT_WRITES = 500
    }
}
