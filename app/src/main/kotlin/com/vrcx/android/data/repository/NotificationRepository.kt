package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.resolvePresenceLocation
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.data.util.parseInstantMillisOrNull
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private enum class RefreshMode {
    INCREMENTAL,
    FULL_RESYNC,
}

@Singleton
class NotificationRepository @Inject constructor(
    private val notificationApi: NotificationApi,
    private val authRepository: AuthRepository,
    private val notificationDao: NotificationDao,
    private val json: Json,
    accountScope: AccountScope,
) : AccountScoped {
    companion object {
        private const val TAG = "NotificationRepository"
        private const val PAGE_SIZE = 100
        private const val MAX_REMOTE_PAGES = 50
        private const val PAGE_DELAY_MS = 150L
        private const val MAX_CACHED_NOTIFICATIONS = PAGE_SIZE * MAX_REMOTE_PAGES
        private const val MAX_LOCAL_NOTIFICATIONS = 100
        private val FULL_RESYNC_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5)
    }

    private val account = accountScope.bindTo(this)
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val refreshMutex = Mutex()
    private val stateLock = Any()

    // Bumped when the inbox itself is emptied (a clear-notification frame), so an
    // in-flight refresh can tell "these pages are stale" from "the account changed".
    private val inboxRevision = AtomicLong(0)
    private val removedNotificationIds = linkedSetOf<String>()

    private val v1 = RemoteSource(
        maxCached = MAX_CACHED_NOTIFICATIONS,
        idOf = VrcNotification::id,
        createdAtOf = VrcNotification::createdAt,
        toUnified = VrcNotification::toUnified,
        withSeen = { notification -> notification.copy(seen = true) },
        merge = ::mergeV1,
    )
    private val v2 = RemoteSource(
        maxCached = MAX_CACHED_NOTIFICATIONS,
        idOf = NotificationV2::id,
        createdAtOf = NotificationV2::createdAt,
        toUnified = NotificationV2::toUnified,
        withSeen = { notification -> notification.copy(seen = true) },
        merge = ::mergeV2,
    )
    private var local: List<UnifiedNotification> = emptyList()

    private val _unifiedNotifications = MutableStateFlow<List<UnifiedNotification>>(emptyList())
    val unifiedNotifications: StateFlow<List<UnifiedNotification>> = _unifiedNotifications.asStateFlow()

    init {
        storageScope.launch {
            for (command in storageCommands) {
                captureFailure(command)?.let { Log.w(TAG, "Notification cache write failed", it) }
            }
        }
    }

    suspend fun restoreNotifications() {
        val token = account.current()
        val userId = token.ownerUserId.takeIf { it.isNotEmpty() } ?: return
        val revision = inboxRevision.get()
        val cachedV1 = notificationDao.getNotifications(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel(json) }
        val cachedV2 = notificationDao.getNotificationsV2(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel(json) }
        mutateInbox(token, revision) {
            v1.restore(cachedV1)
            v2.restore(cachedV2)
            publishUnified()
        }
    }

    override fun clearRuntimeState() {
        synchronized(stateLock) {
            removedNotificationIds.clear()
            v1.reset()
            v2.reset()
            local = emptyList()
            publishUnified()
        }
    }

    /** Stops at the first cached overlap, or reconciles a full snapshot when a source is due one. */
    suspend fun loadNotifications() = refreshMutex.withLock {
        val token = account.current()
        val userId = token.ownerUserId.takeIf { it.isNotEmpty() } ?: return@withLock
        val (knownV1, knownV2, revision) = synchronized(stateLock) {
            Triple(v1.ids(), v2.ids(), inboxRevision.get())
        }
        val v1Mode = refreshModeFor(knownV1, v1.lastFullResyncNanos)
        val v2Mode = refreshModeFor(knownV2, v2.lastFullResyncNanos)

        val failures = coroutineScope {
            val v1Refresh = async {
                captureFailure {
                    refresh(
                        source = v1,
                        token = token,
                        revision = revision,
                        knownIds = knownV1,
                        mode = v1Mode,
                        fetchPage = { offset, count ->
                            notificationApi.getNotifications(n = count, offset = offset)
                        },
                        persist = { persisted, mode ->
                            val entities = persisted.map { it.toEntity(userId) }
                            when (mode) {
                                RefreshMode.INCREMENTAL ->
                                    notificationDao.upsertNotifications(userId, entities, MAX_CACHED_NOTIFICATIONS)
                                RefreshMode.FULL_RESYNC ->
                                    notificationDao.synchronizeNotifications(userId, entities, MAX_CACHED_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
            val v2Refresh = async {
                captureFailure {
                    refresh(
                        source = v2,
                        token = token,
                        revision = revision,
                        knownIds = knownV2,
                        mode = v2Mode,
                        fetchPage = { offset, count ->
                            notificationApi.getNotificationsV2(n = count, offset = offset)
                        },
                        persist = { persisted, mode ->
                            val entities = persisted.map { it.toEntity(userId, json) }
                            when (mode) {
                                RefreshMode.INCREMENTAL ->
                                    notificationDao.upsertNotificationsV2(userId, entities, MAX_CACHED_NOTIFICATIONS)
                                RefreshMode.FULL_RESYNC ->
                                    notificationDao.synchronizeNotificationsV2(userId, entities, MAX_CACHED_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
            listOfNotNull(v1Refresh.await(), v2Refresh.await())
        }

        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    /**
     * An incremental refresh stops at the first id it already holds and only merges,
     * so it can never drop a notification that was accepted, declined or hidden on
     * another client while this one was closed. Reconcile whenever a source has no
     * cache at all, and again once the last reconciliation has aged out — the full
     * sweep pages until the server runs out, so it stays off the fast path.
     */
    private fun refreshModeFor(knownIds: Set<String>, lastFullResyncNanos: Long?): RefreshMode = when {
        knownIds.isEmpty() -> RefreshMode.FULL_RESYNC
        lastFullResyncNanos == null -> RefreshMode.FULL_RESYNC
        System.nanoTime() - lastFullResyncNanos >= FULL_RESYNC_INTERVAL_NANOS -> RefreshMode.FULL_RESYNC
        else -> RefreshMode.INCREMENTAL
    }

    /**
     * Applies [mutate] to the four in-memory collections only while [token] and
     * [revision] both still describe the inbox on screen, and reports whether it
     * ran. Taking the account lock outside the state lock is what makes the
     * mutate-and-publish atomic against a teardown.
     */
    private fun mutateInbox(
        token: AccountScope.Token,
        revision: Long,
        mutate: () -> Unit,
    ): Boolean {
        var applied = false
        account.publishIfCurrent(token) {
            synchronized(stateLock) {
                if (revision != inboxRevision.get()) return@publishIfCurrent
                mutate()
                applied = true
            }
        }
        return applied
    }

    /**
     * Sweeps [source] newest-first, folds the result in and persists what
     * survived — the whole of a refresh apart from which endpoint it reads and
     * which DAO methods carry it, which is all [fetchPage] and [persist] supply.
     */
    private suspend fun <T> refresh(
        source: RemoteSource<T>,
        token: AccountScope.Token,
        revision: Long,
        knownIds: Set<String>,
        mode: RefreshMode,
        fetchPage: suspend (offset: Int, count: Int) -> List<T>,
        persist: suspend (persisted: List<T>, mode: RefreshMode) -> Unit,
    ) {
        val fetched = loadNewest(
            knownIds = knownIds,
            stopAtOverlap = mode == RefreshMode.INCREMENTAL,
            idOf = source.idOf,
            fetchPage = fetchPage,
        )
        var persisted: List<T> = emptyList()
        val applied = mutateInbox(token, revision) {
            persisted = source.commit(fetched, knownIds, mode, removedNotificationIds)
            publishUnified()
        }
        if (!applied) return
        persist(persisted, mode)
        if (mode == RefreshMode.FULL_RESYNC) source.lastFullResyncNanos = System.nanoTime()
    }

    private fun mergeV1(
        current: List<VrcNotification>,
        fetched: List<VrcNotification>,
    ): List<VrcNotification> {
        val merged = current.associateByTo(linkedMapOf()) { it.id }
        fetched.forEach { notification ->
            val wasSeen = merged[notification.id]?.seen == true
            merged[notification.id] = notification.copy(seen = notification.seen || wasSeen)
        }
        return merged.values.toList()
    }

    private fun mergeV2(
        current: List<NotificationV2>,
        fetched: List<NotificationV2>,
    ): List<NotificationV2> {
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

    /**
     * Newest-first sweep of a notification source. [MAX_REMOTE_PAGES] is deliberately
     * half the paginator's default ceiling — the inbox is capped at
     * [MAX_CACHED_NOTIFICATIONS] anyway, so paging further would only be discarded.
     */
    private suspend fun <T> loadNewest(
        knownIds: Set<String>,
        stopAtOverlap: Boolean,
        idOf: (T) -> String,
        fetchPage: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        val collected = linkedMapOf<String, T>()
        BulkPaginator.fetchAll(
            pageSize = PAGE_SIZE,
            maxPages = MAX_REMOTE_PAGES,
            delayBetweenPagesMs = PAGE_DELAY_MS,
            stopWhen = { page, _ -> stopAtOverlap && page.any { idOf(it) in knownIds } },
            fetcher = fetchPage,
        ).forEach { item ->
            val id = idOf(item)
            if (id.isNotBlank()) collected.putIfAbsent(id, item)
        }
        return collected.values.toList()
    }

    fun handleEvent(event: PipelineEvent) {
        val token = accountToken() ?: return
        mutateInbox(token, inboxRevision.get()) {
            val userId = token.ownerUserId
            when (event) {
                is PipelineEvent.Notification -> handleV1Notification(event, userId)
                is PipelineEvent.NotificationV2 -> handleV2Notification(event, userId)
                is PipelineEvent.NotificationV2Delete -> handleV2Delete(event, token)
                is PipelineEvent.NotificationV2Update -> handleV2Update(event, userId)
                is PipelineEvent.SeeNotification -> event.content?.asString()
                    ?.let { markSeen(it, userId) }
                is PipelineEvent.HideNotification -> event.content?.asString()
                    ?.let { removeFromLists(it, token) }
                is PipelineEvent.ResponseNotification -> event.content?.asObject()
                    ?.get("notificationId")
                    ?.asString()
                    ?.let { removeFromLists(it, token) }
                is PipelineEvent.InstanceClosed -> handleInstanceClosed(event)
                PipelineEvent.ClearNotification -> handleClearNotification(userId)
                else -> Unit
            }
        }
    }

    private fun handleClearNotification(userId: String) {
        // Bump the inbox revision, not the account generation: this empties the
        // inbox but the signed-in account is unchanged, and a refresh in flight
        // must be discarded rather than allowed to re-add what was just cleared.
        inboxRevision.incrementAndGet()
        removedNotificationIds.clear()
        v1.reset()
        v2.reset()
        local = emptyList()
        publishUnified()
        storageCommands.trySend {
            notificationDao.deleteNotificationsForUser(userId)
            notificationDao.deleteNotificationsV2ForUser(userId)
        }
    }

    private fun handleV1Notification(event: PipelineEvent.Notification, userId: String) {
        val notification = runCatching {
            event.content?.let { json.decodeFromJsonElement(VrcNotification.serializer(), it) }
        }.getOrNull() ?: return
        removedNotificationIds.remove(notification.id)
        v1.add(listOf(notification))
        publishUnified()
        persistNotificationAsync(notification, userId)
    }

    private fun handleV2Notification(event: PipelineEvent.NotificationV2, userId: String) {
        val notification = runCatching {
            event.content?.let { json.decodeFromJsonElement(NotificationV2.serializer(), it) }
        }.getOrNull() ?: return
        removedNotificationIds.remove(notification.id)
        v2.add(listOf(notification))
        publishUnified()
        persistNotificationV2Async(notification, userId)
    }

    private fun handleV2Delete(event: PipelineEvent.NotificationV2Delete, token: AccountScope.Token) {
        val ids = runCatching {
            event.content?.jsonObject?.get("ids")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
        }.getOrDefault(emptyList())
        removeFromLists(ids, token)
    }

    private fun handleV2Update(event: PipelineEvent.NotificationV2Update, userId: String) {
        val obj = event.content?.asObject() ?: return
        val id = obj["id"]?.asString() ?: return
        val updatesJson = obj["updates"]?.asObject() ?: return
        val existing = v2.items.firstOrNull { it.id == id } ?: return
        val existingObject = json.encodeToJsonElement(NotificationV2.serializer(), existing).jsonObject
        val updated = runCatching {
            json.decodeFromJsonElement(
                NotificationV2.serializer(),
                buildJsonObject {
                    existingObject.forEach { (key, value) -> put(key, value) }
                    updatesJson.forEach { (key, value) -> put(key, value) }
                    put("id", JsonPrimitive(id))
                },
            )
        }.getOrNull() ?: return
        v2.replace(id, updated)
        publishUnified()
        persistNotificationV2Async(updated, userId)
    }

    private fun handleInstanceClosed(event: PipelineEvent.InstanceClosed) {
        val location = event.content?.asObject()?.get("instanceLocation")?.asString().orEmpty()
        val now = Instant.now()
        val notification = UnifiedNotification(
            id = "local:instance.closed:$now",
            type = "instance.closed",
            senderUserId = "",
            senderUsername = "System",
            message = location.ifBlank { "A queued instance closed" },
            title = "Instance Closed",
            createdAt = now.toString(),
            seen = false,
            source = NotificationSource.LOCAL,
            responses = emptyList(),
        )
        local = (listOf(notification) + local).take(MAX_LOCAL_NOTIFICATIONS)
        publishUnified()
    }

    private fun markSeen(id: String, userId: String) {
        v1.markSeen(id)
        v2.markSeen(id)
        local = local.map { if (it.id == id) it.copy(seen = true) else it }
        publishUnified()
        markSeenInStorageAsync(id, userId)
    }

    suspend fun sendInviteToUser(userId: String, messageSlot: Int? = null) {
        notificationApi.sendInvite(userId, createInvitePayload(messageSlot))
    }

    suspend fun sendInviteResponse(notification: UnifiedNotification, responseSlot: Int) {
        require(notification.source == NotificationSource.V1) { "Saved invite responses require a V1 notification" }
        val token = accountToken() ?: return
        notificationApi.sendInviteResponse(
            notificationId = notification.id,
            body = InviteResponseRequest(responseSlot = responseSlot),
        )
        if (!account.isCurrent(token)) return
        notificationApi.hideNotification(notification.id)
        removeFromLists(notification.id, token)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        val token = accountToken() ?: return
        when {
            notification.source == NotificationSource.V2 -> {
                val primaryResponse = notification.responses.firstOrNull() ?: return
                respondToNotification(notification, primaryResponse.type, token)
            }
            notification.kind == NotificationKind.FRIEND_REQUEST -> {
                notificationApi.acceptFriendRequest(notification.id)
                removeFromLists(notification.id, token)
            }
            notification.kind == NotificationKind.REQUEST_INVITE -> {
                sendInviteToUser(notification.senderUserId)
                if (!account.isCurrent(token)) return
                notificationApi.hideNotification(notification.id)
                removeFromLists(notification.id, token)
            }
        }
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        val token = accountToken() ?: return
        respondToNotification(notification, responseType, token)
    }

    private suspend fun respondToNotification(
        notification: UnifiedNotification,
        responseType: String,
        token: AccountScope.Token,
    ) {
        if (notification.source != NotificationSource.V2) return
        val response = notification.responses.firstOrNull { it.type == responseType } ?: return
        if (!account.isCurrent(token)) return
        notificationApi.sendNotificationResponse(
            notification.id,
            NotificationResponse(
                responseType = response.type,
                responseData = response.data,
            ),
        )
        removeFromLists(notification.id, token)
    }

    suspend fun hide(notification: UnifiedNotification) {
        val token = accountToken() ?: return
        when (notification.source) {
            NotificationSource.V1 -> notificationApi.hideNotification(notification.id)
            NotificationSource.V2 -> notificationApi.hideNotificationV2(notification.id)
            NotificationSource.LOCAL -> Unit
        }
        removeFromLists(notification.id, token)
    }

    private suspend fun createInvitePayload(messageSlot: Int?): InviteRequest {
        return InviteRequest(instanceId = resolveInviteLocation(), messageSlot = messageSlot)
    }

    private suspend fun resolveInviteLocation(): String {
        val currentUser = authRepository.currentUser ?: error("Current user is not available")
        val currentLocation = resolvePresenceLocation(currentUser)
        if (!isTrackableLocation(currentLocation)) error("You must be in a world to send invites")
        return currentLocation
    }

    private fun persistNotificationAsync(notification: VrcNotification, ownerUserId: String) {
        enqueueStorage {
            notificationDao.upsertNotifications(
                ownerUserId,
                listOf(notification.toEntity(ownerUserId)),
                MAX_CACHED_NOTIFICATIONS,
            )
        }
    }

    private fun persistNotificationV2Async(notification: NotificationV2, ownerUserId: String) {
        enqueueStorage {
            notificationDao.upsertNotificationsV2(
                ownerUserId,
                listOf(notification.toEntity(ownerUserId, json)),
                MAX_CACHED_NOTIFICATIONS,
            )
        }
    }

    private fun markSeenInStorageAsync(notificationId: String, ownerUserId: String) {
        enqueueStorage {
            notificationDao.markSeen(ownerUserId, notificationId)
            notificationDao.markSeenV2(ownerUserId, notificationId)
        }
    }

    private fun deleteFromStorageAsync(notificationIds: Collection<String>, ownerUserId: String) {
        if (notificationIds.isEmpty()) return
        val persistedIds = notificationIds.toList()
        enqueueStorage {
            if (persistedIds.size == 1) {
                notificationDao.deleteNotification(ownerUserId, persistedIds.first())
                notificationDao.deleteNotificationV2(ownerUserId, persistedIds.first())
            } else {
                notificationDao.deleteNotifications(ownerUserId, persistedIds)
                notificationDao.deleteNotificationsV2(ownerUserId, persistedIds)
            }
        }
    }

    private fun enqueueStorage(command: suspend () -> Unit) {
        storageCommands.trySend(command)
    }

    private fun removeFromLists(notificationId: String, token: AccountScope.Token) =
        removeFromLists(listOf(notificationId), token)

    private fun removeFromLists(
        notificationIds: Collection<String>,
        token: AccountScope.Token,
    ) {
        if (notificationIds.isEmpty()) return
        account.publishIfCurrent(token) {
            synchronized(stateLock) {
                val idSet = notificationIds.toSet()
                removedNotificationIds.addAll(idSet)
                while (removedNotificationIds.size > MAX_CACHED_NOTIFICATIONS) {
                    removedNotificationIds.remove(removedNotificationIds.first())
                }
                v1.drop(idSet)
                v2.drop(idSet)
                local = local.filter { it.id !in idSet }
                publishUnified()
            }
        }
        deleteFromStorageAsync(notificationIds, token.ownerUserId)
    }

    private fun publishUnified() {
        _unifiedNotifications.value = (v1.unified() + v2.unified() + local)
            .sortedByDescending { it.createdAtEpochMs }
    }

    /** The signed-in account, or null when there is none to attribute work to. */
    private fun accountToken(): AccountScope.Token? =
        account.current().takeIf { it.ownerUserId.isNotEmpty() }

    // VRChat sends several of these fields as an object in one frame and a scalar
    // in the next, so read them through casts that yield null rather than through
    // the kotlinx accessors, which throw.
    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.content

}

private fun notificationSortKey(createdAt: String): Long =
    parseInstantMillisOrNull(createdAt) ?: Long.MIN_VALUE

/**
 * One remote notification source's cached page.
 *
 * V1 and V2 differ in their payload type, in what counts as the fresher copy of
 * an id ([merge]) and in which DAO methods carry them. Everything else — the
 * cached list, the resync clock, the cap, the reconciliation and the mapping to
 * the published model — is the same for both and lives here once, so a change
 * to any of it cannot land on one source and miss the other.
 */
private class RemoteSource<T>(
    private val maxCached: Int,
    val idOf: (T) -> String,
    private val createdAtOf: (T) -> String,
    private val toUnified: (T) -> UnifiedNotification,
    private val withSeen: (T) -> T,
    private val merge: (current: List<T>, fetched: List<T>) -> List<T>,
) {
    var items: List<T> = emptyList()
        private set

    /** When this source was last reconciled, or null if never during this session. */
    @Volatile
    var lastFullResyncNanos: Long? = null

    // Mapping a payload to UnifiedNotification parses its timestamp and
    // classifies its type, and the whole list is republished on every mutation
    // — so carry the mapped entries across publishes and only map what changed.
    private var unifiedByItem: Map<T, UnifiedNotification> = emptyMap()

    fun ids(): MutableSet<String> = items.mapTo(mutableSetOf(), idOf)

    fun reset() {
        items = emptyList()
        lastFullResyncNanos = null
    }

    fun restore(cached: List<T>) { items = cached }

    fun add(fetched: List<T>) { items = capped(merge(items, fetched)) }

    fun drop(ids: Set<String>) { items = items.filterNot { idOf(it) in ids } }

    fun markSeen(id: String) { items = items.map { if (idOf(it) == id) withSeen(it) else it } }

    fun replace(id: String, updated: T) { items = items.map { if (idOf(it) == id) updated else it } }

    /**
     * Folds a fetched page in and returns the rows the caller should persist.
     *
     * A FULL_RESYNC also drops entries the server no longer reports: [merge]
     * only ever unions, so without this an entry handled on another client
     * survives in the published list even once the DAO has reconciled. Only ids
     * that were already cached when the fetch started are eligible, so a
     * notification that arrived over the pipeline mid-fetch is never
     * reconciled away.
     */
    fun commit(
        fetched: List<T>,
        knownIds: Set<String>,
        mode: RefreshMode,
        suppressedIds: Set<String>,
    ): List<T> {
        val visible = fetched.filterNot { idOf(it) in suppressedIds }
        val retained = if (mode == RefreshMode.FULL_RESYNC) {
            val fetchedIds = visible.mapTo(mutableSetOf(), idOf)
            items.filterNot { idOf(it) in knownIds && idOf(it) !in fetchedIds }
        } else {
            items
        }
        items = capped(merge(retained, visible))
        if (mode == RefreshMode.FULL_RESYNC) return items
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

    private fun capped(all: List<T>): List<T> =
        if (all.size <= maxCached) all
        else all.sortedByDescending { notificationSortKey(createdAtOf(it)) }.take(maxCached)
}
