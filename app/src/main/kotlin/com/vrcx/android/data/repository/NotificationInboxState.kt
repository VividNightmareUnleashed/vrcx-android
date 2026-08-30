package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.util.parseInstantMillisOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object NotificationRepositoryLimits {
    const val PAGE_SIZE = 100
    const val MAX_REMOTE_PAGES = 50
    const val PAGE_DELAY_MS = 150L
    const val MAX_CACHED = PAGE_SIZE * MAX_REMOTE_PAGES
    const val MAX_LOCAL = 100
    const val DEFAULT_STORAGE_CAPACITY = 64
}

/** Owns the four in-memory inbox collections and their atomic account guard. */
internal class NotificationInboxState(private val account: AccountScope) {
    private val lock = Any()
    private val inboxRevision = AtomicLong(0)
    private val mutationRevision = AtomicLong(0)

    val removedNotificationIds = linkedSetOf<String>()
    val v1 = RemoteNotificationSource(
        maxCached = NotificationRepositoryLimits.MAX_CACHED,
        idOf = VrcNotification::id,
        createdAtOf = VrcNotification::createdAt,
        toUnified = VrcNotification::toUnified,
        withSeen = { notification -> notification.copy(seen = true) },
        merge = NotificationSourceMergers::mergeV1,
    )
    val v2 = RemoteNotificationSource(
        maxCached = NotificationRepositoryLimits.MAX_CACHED,
        idOf = NotificationV2::id,
        createdAtOf = NotificationV2::createdAt,
        toUnified = NotificationV2::toUnified,
        withSeen = { notification -> notification.copy(seen = true) },
        merge = NotificationSourceMergers::mergeV2,
    )
    var local: List<UnifiedNotification> = emptyList()

    private val published = MutableStateFlow<List<UnifiedNotification>>(emptyList())
    val unifiedNotifications: StateFlow<List<UnifiedNotification>> = published.asStateFlow()

    fun currentRevision(): Long = inboxRevision.get()

    fun currentMutationRevision(): Long = mutationRevision.get()

    fun <T> read(block: NotificationInboxState.() -> T): T = synchronized(lock) { block() }

    fun mutate(
        token: AccountScope.Token,
        revision: Long,
        expectedMutationRevision: Long? = null,
        block: NotificationInboxState.() -> Unit,
    ): Boolean {
        var applied = false
        account.publishIfCurrent(token) {
            synchronized(lock) {
                if (revision != inboxRevision.get()) return@publishIfCurrent
                if (expectedMutationRevision != null && expectedMutationRevision != mutationRevision.get()) {
                    return@publishIfCurrent
                }
                block()
                mutationRevision.incrementAndGet()
                applied = true
            }
        }
        return applied
    }

    fun clearRuntimeState() {
        synchronized(lock) {
            inboxRevision.incrementAndGet()
            mutationRevision.incrementAndGet()
            resetCollections()
            publish()
        }
    }

    /** Invalidates refreshes for this inbox without changing the owning account. */
    fun clearInboxLocked() {
        inboxRevision.incrementAndGet()
        resetCollections()
        publish()
    }

    fun remove(notificationIds: Collection<String>, token: AccountScope.Token, storage: NotificationStorageWorker) {
        if (notificationIds.isEmpty()) return
        val removed = mutate(token, currentRevision()) { removeLocked(notificationIds) }
        if (removed) storage.delete(token, notificationIds)
    }

    fun removeLocked(notificationIds: Collection<String>) {
        val idSet = notificationIds.toSet()
        removedNotificationIds.addAll(idSet)
        while (removedNotificationIds.size > NotificationRepositoryLimits.MAX_CACHED) {
            removedNotificationIds.remove(removedNotificationIds.first())
        }
        v1.drop(idSet)
        v2.drop(idSet)
        local = local.filter { it.id !in idSet }
        publish()
    }

    fun publish() {
        published.value = (v1.unified() + v2.unified() + local)
            .sortedByDescending { it.createdAtEpochMs }
    }

    private fun resetCollections() {
        removedNotificationIds.clear()
        v1.reset()
        v2.reset()
        local = emptyList()
    }
}

internal fun AccountScope.notificationToken(): AccountScope.Token? = current().takeIf { it.ownerUserId.isNotEmpty() }

private object NotificationSourceMergers {
    fun mergeV1(current: List<VrcNotification>, fetched: List<VrcNotification>): List<VrcNotification> {
        val merged = current.associateByTo(linkedMapOf()) { it.id }
        fetched.forEach { notification ->
            val wasSeen = merged[notification.id]?.seen == true
            merged[notification.id] = notification.copy(seen = notification.seen || wasSeen)
        }
        return merged.values.toList()
    }

    fun mergeV2(current: List<NotificationV2>, fetched: List<NotificationV2>): List<NotificationV2> {
        val merged = current.associateByTo(linkedMapOf()) { it.id }
        fetched.forEach { notification ->
            val existing = merged[notification.id]
            val freshest = freshestV2(existing, notification)
            merged[notification.id] = freshest.copy(seen = freshest.seen || existing?.seen == true)
        }
        return merged.values.toList()
    }

    private fun freshestV2(existing: NotificationV2?, fetched: NotificationV2): NotificationV2 {
        if (existing == null) return fetched
        if (existing.version != fetched.version) return if (existing.version > fetched.version) existing else fetched
        val existingUpdated = notificationSortKey(existing.updatedAt)
        val fetchedUpdated = notificationSortKey(fetched.updatedAt)
        return if (existingUpdated > fetchedUpdated) existing else fetched
    }
}

/** Shared cache/reconciliation mechanics for one remote notification schema. */
internal class RemoteNotificationSource<T>(
    private val maxCached: Int,
    val idOf: (T) -> String,
    private val createdAtOf: (T) -> String,
    private val toUnified: (T) -> UnifiedNotification,
    private val withSeen: (T) -> T,
    private val merge: (current: List<T>, fetched: List<T>) -> List<T>,
) {
    var items: List<T> = emptyList()
        private set

    @Volatile
    var lastFullResyncNanos: Long? = null

    private var unifiedByItem: Map<T, UnifiedNotification> = emptyMap()

    fun ids(): MutableSet<String> = items.mapTo(mutableSetOf(), idOf)

    fun reset() {
        items = emptyList()
        lastFullResyncNanos = null
    }

    fun restore(cached: List<T>) {
        items = cached
    }

    fun add(fetched: List<T>) {
        items = capped(merge(items, fetched))
    }

    fun drop(ids: Set<String>) {
        items = items.filterNot { idOf(it) in ids }
    }

    fun markSeen(id: String) {
        items = items.map { if (idOf(it) == id) withSeen(it) else it }
    }

    fun replace(id: String, updated: T) {
        items = items.map { if (idOf(it) == id) updated else it }
    }

    fun commit(fetched: List<T>, knownIds: Set<String>, fullResync: Boolean, suppressedIds: Set<String>): List<T> {
        val visible = fetched.filterNot { idOf(it) in suppressedIds }
        val retained = if (fullResync) {
            val fetchedIds = visible.mapTo(mutableSetOf(), idOf)
            items.filterNot { idOf(it) in knownIds && idOf(it) !in fetchedIds }
        } else {
            items
        }
        items = capped(merge(retained, visible))
        if (fullResync) return items
        val committedById = items.associateBy(idOf)
        return visible.mapNotNull { committedById[idOf(it)] }
    }

    fun unified(): List<UnifiedNotification> {
        val previous = unifiedByItem
        val next = HashMap<T, UnifiedNotification>(items.size)
        val mapped = items.map { item ->
            (previous[item] ?: toUnified(item)).also { next[item] = it }
        }
        unifiedByItem = next
        return mapped
    }

    private fun capped(all: List<T>): List<T> = if (all.size <= maxCached) {
        all
    } else {
        all.sortedByDescending { notificationSortKey(createdAtOf(it)) }.take(maxCached)
    }
}

private fun notificationSortKey(createdAt: String): Long = parseInstantMillisOrNull(createdAt) ?: Long.MIN_VALUE
