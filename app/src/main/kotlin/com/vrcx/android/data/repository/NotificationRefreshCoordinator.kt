package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.util.captureFailure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal enum class NotificationRefreshMode {
    INCREMENTAL,
    FULL_RESYNC,
}

private data class SourceRefreshContext(
    val token: AccountScope.Token,
    val inboxRevision: Long,
    val knownIds: Set<String>,
    val mode: NotificationRefreshMode,
)

private data class InboxRefreshSnapshot(
    val revision: Long,
    val knownV1: Set<String>,
    val knownV2: Set<String>,
    val lastFullV1: Long?,
    val lastFullV2: Long?,
)

/** Owns cold-cache restore and concurrent V1/V2 remote reconciliation. */
internal class NotificationRefreshCoordinator(
    private val notificationApi: NotificationApi,
    private val notificationDao: NotificationDao,
    private val json: Json,
    private val account: AccountScope,
    private val state: NotificationInboxState,
    private val storage: NotificationStorageWorker,
) {
    private val refreshMutex = Mutex()

    suspend fun restore() {
        val token = account.current()
        val userId = token.ownerUserId.takeIf { it.isNotEmpty() } ?: return
        val revision = state.currentRevision()
        val mutationRevision = state.currentMutationRevision()
        val cachedV1 = notificationDao.getNotifications(
            userId,
            NotificationRepositoryLimits.MAX_CACHED,
        ).map { it.toModel(json) }
        val cachedV2 = notificationDao.getNotificationsV2(
            userId,
            NotificationRepositoryLimits.MAX_CACHED,
        ).map { it.toModel(json) }
        state.mutate(token, revision, mutationRevision) {
            v1.restore(cachedV1)
            v2.restore(cachedV2)
            publish()
        }
    }

    suspend fun refresh(expectedToken: AccountScope.Token? = null, forceFullResync: Boolean = false) =
        refreshMutex.withLock {
            val token = account.current()
            if (expectedToken != null && token != expectedToken) return@withLock
            if (token.ownerUserId.isEmpty()) return@withLock
            val snapshot = state.read {
                InboxRefreshSnapshot(
                    revision = currentRevision(),
                    knownV1 = v1.ids(),
                    knownV2 = v2.ids(),
                    lastFullV1 = v1.lastFullResyncNanos,
                    lastFullV2 = v2.lastFullResyncNanos,
                )
            }
            val v1Mode = if (forceFullResync) {
                NotificationRefreshMode.FULL_RESYNC
            } else {
                refreshModeFor(snapshot.knownV1, snapshot.lastFullV1)
            }
            val v2Mode = if (forceFullResync) {
                NotificationRefreshMode.FULL_RESYNC
            } else {
                refreshModeFor(snapshot.knownV2, snapshot.lastFullV2)
            }

            val failures = refreshSources(
                v1Context = SourceRefreshContext(token, snapshot.revision, snapshot.knownV1, v1Mode),
                v2Context = SourceRefreshContext(token, snapshot.revision, snapshot.knownV2, v2Mode),
            )
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }

    private suspend fun refreshSources(
        v1Context: SourceRefreshContext,
        v2Context: SourceRefreshContext,
    ): List<Throwable> = coroutineScope {
        val v1Refresh = async {
            captureFailure {
                state.v1.refresh(
                    context = v1Context,
                    fetchPage = { offset, count ->
                        account.ensureCurrent(v1Context.token)
                        notificationApi.getNotifications(n = count, offset = offset)
                    },
                    persist = { notifications, mode ->
                        storage.refresh(
                            v1Context.token,
                            notifications,
                            mode == NotificationRefreshMode.FULL_RESYNC,
                        )
                    },
                )
            }
        }
        val v2Refresh = async {
            captureFailure {
                state.v2.refresh(
                    context = v2Context,
                    fetchPage = { offset, count ->
                        account.ensureCurrent(v2Context.token)
                        notificationApi.getNotificationsV2(n = count, offset = offset)
                    },
                    persist = { notifications, mode ->
                        storage.refreshV2(
                            v2Context.token,
                            notifications,
                            mode == NotificationRefreshMode.FULL_RESYNC,
                        )
                    },
                )
            }
        }
        listOfNotNull(v1Refresh.await(), v2Refresh.await())
    }

    private suspend fun <T> RemoteNotificationSource<T>.refresh(
        context: SourceRefreshContext,
        fetchPage: suspend (offset: Int, count: Int) -> List<T>,
        persist: (persisted: List<T>, mode: NotificationRefreshMode) -> CompletableDeferred<Unit>,
    ) {
        val source = this
        val fetched = loadNewest(
            knownIds = context.knownIds,
            stopAtOverlap = context.mode == NotificationRefreshMode.INCREMENTAL,
            idOf = source.idOf,
            fetchPage = fetchPage,
        )
        var persistence: CompletableDeferred<Unit>? = null
        val applied = state.mutate(context.token, context.inboxRevision) {
            val fullResync = context.mode == NotificationRefreshMode.FULL_RESYNC
            val persisted = source.commit(fetched, context.knownIds, fullResync, removedNotificationIds)
            publish()
            persistence = persist(persisted, context.mode)
        }
        if (!applied) return
        requireNotNull(persistence).await()
        if (context.mode == NotificationRefreshMode.FULL_RESYNC) {
            state.mutate(context.token, context.inboxRevision) {
                source.lastFullResyncNanos = System.nanoTime()
            }
        }
    }

    private fun refreshModeFor(knownIds: Set<String>, lastFullResyncNanos: Long?): NotificationRefreshMode = when {
        knownIds.isEmpty() -> NotificationRefreshMode.FULL_RESYNC

        lastFullResyncNanos == null -> NotificationRefreshMode.FULL_RESYNC

        System.nanoTime() - lastFullResyncNanos >= FULL_RESYNC_INTERVAL_NANOS ->
            NotificationRefreshMode.FULL_RESYNC

        else -> NotificationRefreshMode.INCREMENTAL
    }

    private suspend fun <T> loadNewest(
        knownIds: Set<String>,
        stopAtOverlap: Boolean,
        idOf: (T) -> String,
        fetchPage: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        val collected = linkedMapOf<String, T>()
        BulkPaginator.fetchAll(
            pageSize = NotificationRepositoryLimits.PAGE_SIZE,
            maxPages = NotificationRepositoryLimits.MAX_REMOTE_PAGES,
            delayBetweenPagesMs = NotificationRepositoryLimits.PAGE_DELAY_MS,
            stopWhen = { page, _ -> stopAtOverlap && page.any { idOf(it) in knownIds } },
            fetcher = fetchPage,
        ).forEach { item ->
            val id = idOf(item)
            if (id.isNotBlank()) collected.putIfAbsent(id, item)
        }
        return collected.values.toList()
    }

    private companion object {
        val FULL_RESYNC_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
