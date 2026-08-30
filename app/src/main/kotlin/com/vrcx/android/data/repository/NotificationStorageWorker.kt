package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.util.captureFailure
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Persists ordered inbox changes and repairs the cache if a write is lost.
 *
 * Queue mutation and retry coordination stay together so one lock preserves command ordering.
 */
@Suppress("TooManyFunctions")
internal class NotificationStorageWorker(
    private val notificationDao: NotificationDao,
    private val json: Json,
    private val accountScope: AccountScope,
    private val config: Config,
    private val maxCachedNotifications: Int,
    private val requestResync: suspend (AccountScope.Token) -> Unit,
) {
    class Config(val scope: CoroutineScope, val capacity: Int)

    private sealed interface Command {
        val token: AccountScope.Token
        val completion: CompletableDeferred<Unit>? get() = null

        data class UpsertV1(override val token: AccountScope.Token, val notification: VrcNotification) : Command

        data class UpsertV2(override val token: AccountScope.Token, val notification: NotificationV2) : Command

        data class RefreshV1(
            override val token: AccountScope.Token,
            val notifications: List<VrcNotification>,
            val fullResync: Boolean,
            override val completion: CompletableDeferred<Unit>,
        ) : Command

        data class RefreshV2(
            override val token: AccountScope.Token,
            val notifications: List<NotificationV2>,
            val fullResync: Boolean,
            override val completion: CompletableDeferred<Unit>,
        ) : Command

        data class MarkSeen(override val token: AccountScope.Token, val notificationId: String) : Command

        data class Delete(override val token: AccountScope.Token, val notificationIds: List<String>) : Command

        data class Clear(override val token: AccountScope.Token) : Command
    }

    private sealed interface Work {
        data class Persist(val command: Command) : Work

        data class Resync(val token: AccountScope.Token) : Work
    }

    private val lock = Any()
    private val commands = ArrayDeque<Command>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var pendingResync: AccountScope.Token? = null
    private var commandsBeforeResync: Int? = null
    private var resyncRetryJob: Job? = null
    private var resyncRetryToken: AccountScope.Token? = null
    private var resyncRetryAttempt = 0
    private var activeRecoveryJob: Job? = null

    init {
        require(config.capacity > 0) { "capacity must be positive" }
        config.scope.launch {
            for (ignored in wakeup) {
                while (true) {
                    val work = nextWork() ?: break
                    when (work) {
                        is Work.Persist -> {
                            val failure = captureFailure { persist(work.command) }
                            val completion = work.command.completion
                            if (failure == null) {
                                completion?.complete(Unit)
                            } else if (completion != null) {
                                completion.completeExceptionally(failure)
                            } else {
                                Log.w(TAG, "Notification cache write failed; scheduling a full resync", failure)
                                requestRecovery(work.command.token)
                            }
                        }

                        is Work.Resync -> launchRecovery(work.token)
                    }
                }
            }
        }
    }

    fun upsert(token: AccountScope.Token, notification: VrcNotification) {
        enqueue(Command.UpsertV1(token, notification))
    }

    fun upsert(token: AccountScope.Token, notification: NotificationV2) {
        enqueue(Command.UpsertV2(token, notification))
    }

    fun refresh(
        token: AccountScope.Token,
        notifications: List<VrcNotification>,
        fullResync: Boolean,
    ): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        enqueue(Command.RefreshV1(token, notifications, fullResync, completion))
        return completion
    }

    fun refreshV2(
        token: AccountScope.Token,
        notifications: List<NotificationV2>,
        fullResync: Boolean,
    ): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        enqueue(Command.RefreshV2(token, notifications, fullResync, completion))
        return completion
    }

    fun markSeen(token: AccountScope.Token, notificationId: String) {
        enqueue(Command.MarkSeen(token, notificationId))
    }

    fun delete(token: AccountScope.Token, notificationIds: Collection<String>) {
        if (notificationIds.isNotEmpty()) {
            enqueue(Command.Delete(token, notificationIds.toList()))
        }
    }

    fun clear(token: AccountScope.Token) {
        enqueue(Command.Clear(token))
    }

    private fun enqueue(command: Command) {
        val accepted = synchronized(lock) {
            if (commands.size >= config.capacity) {
                false
            } else {
                commands.addLast(command)
                true
            }
        }
        if (accepted) {
            wakeup.trySend(Unit)
        } else {
            val failure = IllegalStateException("Notification cache queue reached capacity")
            val completion = command.completion
            if (completion != null) {
                completion.completeExceptionally(failure)
            } else if (requestRecovery(command.token)) {
                Log.w(TAG, "Notification cache queue reached capacity; scheduling a full resync")
            }
        }
    }

    private fun requestRecovery(token: AccountScope.Token): Boolean {
        var scheduled = false
        var retry: Job? = null
        if (!accountScope.publishIfCurrent(token) {
                synchronized(lock) {
                    resyncRetryAttempt = 0
                    resyncRetryToken = token
                    retry = resyncRetryJob.also { resyncRetryJob = null }
                    scheduled = queueResyncLocked(token)
                }
            }
        ) {
            return false
        }
        retry?.cancel()
        wakeup.trySend(Unit)
        return scheduled
    }

    private fun scheduleResync(token: AccountScope.Token): Boolean {
        var scheduled = false
        if (!accountScope.publishIfCurrent(token) {
                synchronized(lock) { scheduled = queueResyncLocked(token) }
            }
        ) {
            return false
        }
        wakeup.trySend(Unit)
        return scheduled
    }

    private fun queueResyncLocked(token: AccountScope.Token): Boolean {
        if (pendingResync == token) return false
        pendingResync = token
        // The barrier lets commands already accepted at overflow finish
        // before the authoritative snapshot repairs the dropped write.
        commandsBeforeResync = commands.size
        return true
    }

    private fun nextWork(): Work? = synchronized(lock) {
        val pending = pendingResync
        val recoveryActive = activeRecoveryJob?.isActive == true
        if (pending != null && !recoveryActive && commandsBeforeResync == 0) {
            pendingResync = null
            commandsBeforeResync = null
            return@synchronized Work.Resync(pending)
        }

        if (commands.isNotEmpty()) {
            val command = commands.removeFirst()
            commandsBeforeResync = commandsBeforeResync?.minus(1)?.coerceAtLeast(0)
            return@synchronized Work.Persist(command)
        }

        if (pending != null && !recoveryActive) {
            pendingResync = null
            commandsBeforeResync = null
            return@synchronized Work.Resync(pending)
        }
        null
    }

    private suspend fun persist(command: Command) {
        if (!accountScope.isCurrent(command.token)) return
        val ownerUserId = command.token.ownerUserId
        when (command) {
            is Command.UpsertV1 -> notificationDao.upsertNotifications(
                ownerUserId,
                listOf(command.notification.toEntity(ownerUserId)),
                maxCachedNotifications,
            )

            is Command.UpsertV2 -> notificationDao.upsertNotificationsV2(
                ownerUserId,
                listOf(command.notification.toEntity(ownerUserId, json)),
                maxCachedNotifications,
            )

            is Command.RefreshV1 -> {
                val entities = command.notifications.map { it.toEntity(ownerUserId) }
                if (command.fullResync) {
                    notificationDao.synchronizeNotifications(ownerUserId, entities, maxCachedNotifications)
                } else {
                    notificationDao.upsertNotifications(ownerUserId, entities, maxCachedNotifications)
                }
            }

            is Command.RefreshV2 -> {
                val entities = command.notifications.map { it.toEntity(ownerUserId, json) }
                if (command.fullResync) {
                    notificationDao.synchronizeNotificationsV2(ownerUserId, entities, maxCachedNotifications)
                } else {
                    notificationDao.upsertNotificationsV2(ownerUserId, entities, maxCachedNotifications)
                }
            }

            is Command.MarkSeen -> {
                notificationDao.markSeen(ownerUserId, command.notificationId)
                notificationDao.markSeenV2(ownerUserId, command.notificationId)
            }

            is Command.Delete -> {
                if (command.notificationIds.size == 1) {
                    val notificationId = command.notificationIds.single()
                    notificationDao.deleteNotification(ownerUserId, notificationId)
                    notificationDao.deleteNotificationV2(ownerUserId, notificationId)
                } else {
                    notificationDao.deleteNotifications(ownerUserId, command.notificationIds)
                    notificationDao.deleteNotificationsV2(ownerUserId, command.notificationIds)
                }
            }

            is Command.Clear -> {
                notificationDao.deleteNotificationsForUser(ownerUserId)
                notificationDao.deleteNotificationsV2ForUser(ownerUserId)
            }
        }
    }

    private suspend fun recover(token: AccountScope.Token) {
        if (!accountScope.isCurrent(token)) return
        val failure = captureFailure { requestResync(token) }
        if (failure == null) {
            cancelRetry(token)
            return
        }
        if (scheduleRetry(token)) {
            Log.w(TAG, "Notification cache resync failed; retrying", failure)
        } else if (accountScope.isCurrent(token)) {
            Log.w(TAG, "Notification cache resync retry limit reached; waiting for another write failure", failure)
        }
    }

    private fun launchRecovery(token: AccountScope.Token) {
        val job = synchronized(lock) {
            if (activeRecoveryJob?.isActive == true) {
                queueResyncLocked(token)
                return@synchronized null
            }
            config.scope.launch(start = CoroutineStart.LAZY) { recover(token) }
                .also { activeRecoveryJob = it }
        } ?: return
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeRecoveryJob === job) activeRecoveryJob = null
            }
            wakeup.trySend(Unit)
        }
        job.start()
    }

    private fun scheduleRetry(token: AccountScope.Token): Boolean {
        var scheduled: Pair<Job, Job?>? = null
        accountScope.publishIfCurrent(token) {
            synchronized(lock) {
                if (resyncRetryToken != token) resyncRetryAttempt = 0
                if (resyncRetryAttempt < RESYNC_RETRY_LIMIT) {
                    val attempt = ++resyncRetryAttempt
                    val job = config.scope.launch(start = CoroutineStart.LAZY) {
                        delay(retryDelayMs(attempt))
                        scheduleResync(token)
                    }
                    val previous = resyncRetryJob
                    resyncRetryToken = token
                    resyncRetryJob = job
                    scheduled = job to previous
                }
            }
        }
        return scheduled?.let { (job, previous) ->
            previous?.cancel()
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (resyncRetryJob === job) resyncRetryJob = null
                }
            }
            job.start()
            true
        } ?: false
    }

    private fun cancelRetry(token: AccountScope.Token) {
        var retry: Job? = null
        synchronized(lock) {
            if (resyncRetryToken == token) {
                resyncRetryAttempt = 0
                resyncRetryToken = null
                retry = resyncRetryJob.also { resyncRetryJob = null }
            }
        }
        retry?.cancel()
    }

    private fun retryDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        return minOf(RESYNC_RETRY_BASE_DELAY_MS shl exponent, RESYNC_RETRY_MAX_DELAY_MS)
    }

    private companion object {
        const val TAG = "NotificationStorage"
        const val RESYNC_RETRY_BASE_DELAY_MS = 30_000L
        const val RESYNC_RETRY_MAX_DELAY_MS = 120_000L
        const val RESYNC_RETRY_LIMIT = 3
    }
}
