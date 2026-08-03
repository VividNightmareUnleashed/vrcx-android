package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.resolvePresenceLocation
import com.vrcx.android.data.util.parseInstantMillisOrNull
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
) {
    companion object {
        private const val TAG = "NotificationRepository"
        private const val PAGE_SIZE = 100
        private const val MAX_REMOTE_PAGES = 50
        private const val PAGE_DELAY_MS = 150L
        private const val MAX_CACHED_NOTIFICATIONS = PAGE_SIZE * MAX_REMOTE_PAGES
        private const val MAX_LOCAL_NOTIFICATIONS = 100
    }

    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    private val accountGeneration = AtomicLong(0)
    private val removedNotificationIds = linkedSetOf<String>()

    private data class AccountSnapshot(val userId: String, val generation: Long)

    private var remoteV1: List<VrcNotification> = emptyList()
    private var remoteV2: List<NotificationV2> = emptyList()
    private var local: List<UnifiedNotification> = emptyList()

    private val _unifiedNotifications = MutableStateFlow<List<UnifiedNotification>>(emptyList())
    val unifiedNotifications: StateFlow<List<UnifiedNotification>> = _unifiedNotifications.asStateFlow()

    init {
        storageScope.launch {
            for (command in storageCommands) {
                try {
                    command()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.w(TAG, "Notification cache write failed", error)
                }
            }
        }
    }

    suspend fun restoreNotifications() {
        val userId = currentUserId() ?: return
        val generation = accountGeneration.get()
        val v1 = notificationDao.getNotifications(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel(json) }
        val v2 = notificationDao.getNotificationsV2(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel(json) }
        synchronized(stateLock) {
            if (!isCurrentAccount(userId, generation)) return
            remoteV1 = v1
            remoteV2 = v2
            publishUnified()
        }
    }

    fun clearRuntimeState() {
        accountGeneration.incrementAndGet()
        synchronized(stateLock) {
            removedNotificationIds.clear()
            remoteV1 = emptyList()
            remoteV2 = emptyList()
            local = emptyList()
            publishUnified()
        }
    }

    /** Stops at the first cached overlap, or reconciles a full snapshot when a source has no cache. */
    suspend fun loadNotifications() = refreshMutex.withLock {
        val userId = currentUserId() ?: return@withLock
        val generation = accountGeneration.get()
        val (knownV1, knownV2) = synchronized(stateLock) {
            remoteV1.mapTo(mutableSetOf()) { it.id } to remoteV2.mapTo(mutableSetOf()) { it.id }
        }
        val v1Mode = if (knownV1.isEmpty()) RefreshMode.FULL_RESYNC else RefreshMode.INCREMENTAL
        val v2Mode = if (knownV2.isEmpty()) RefreshMode.FULL_RESYNC else RefreshMode.INCREMENTAL

        val failures = coroutineScope {
            val v1 = async {
                captureFailure {
                    val fetched = loadNewestV1(
                        knownIds = knownV1,
                        stopAtOverlap = v1Mode == RefreshMode.INCREMENTAL,
                    )
                    commitV1(userId, generation, fetched, v1Mode)
                }
            }
            val v2 = async {
                captureFailure {
                    val fetched = loadNewestV2(
                        knownIds = knownV2,
                        stopAtOverlap = v2Mode == RefreshMode.INCREMENTAL,
                    )
                    commitV2(userId, generation, fetched, v2Mode)
                }
            }
            listOfNotNull(v1.await(), v2.await())
        }

        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure
    }

    private suspend fun commitV1(
        userId: String,
        generation: Long,
        fetched: List<VrcNotification>,
        mode: RefreshMode,
    ) {
        val persisted = synchronized(stateLock) {
            if (!isCurrentAccount(userId, generation)) return
            val visibleFetched = fetched.filterNot { it.id in removedNotificationIds }
            remoteV1 = limitV1(mergeV1(remoteV1, visibleFetched))
            publishUnified()
            val committedById = remoteV1.associateBy { it.id }
            if (mode == RefreshMode.FULL_RESYNC) remoteV1
            else visibleFetched.mapNotNull { committedById[it.id] }
        }
        if (!isCurrentAccount(userId, generation)) return
        val entities = persisted.map { it.toEntity(userId) }
        when (mode) {
            RefreshMode.INCREMENTAL -> notificationDao.upsertNotifications(
                userId,
                entities,
                MAX_CACHED_NOTIFICATIONS,
            )
            RefreshMode.FULL_RESYNC -> notificationDao.synchronizeNotifications(
                userId,
                entities,
                MAX_CACHED_NOTIFICATIONS,
            )
        }
    }

    private suspend fun commitV2(
        userId: String,
        generation: Long,
        fetched: List<NotificationV2>,
        mode: RefreshMode,
    ) {
        val persisted = synchronized(stateLock) {
            if (!isCurrentAccount(userId, generation)) return
            val visibleFetched = fetched.filterNot { it.id in removedNotificationIds }
            remoteV2 = limitV2(mergeV2(remoteV2, visibleFetched))
            publishUnified()
            val committedById = remoteV2.associateBy { it.id }
            if (mode == RefreshMode.FULL_RESYNC) remoteV2
            else visibleFetched.mapNotNull { committedById[it.id] }
        }
        if (!isCurrentAccount(userId, generation)) return
        val entities = persisted.map { it.toEntity(userId, json) }
        when (mode) {
            RefreshMode.INCREMENTAL -> notificationDao.upsertNotificationsV2(
                userId,
                entities,
                MAX_CACHED_NOTIFICATIONS,
            )
            RefreshMode.FULL_RESYNC -> notificationDao.synchronizeNotificationsV2(
                userId,
                entities,
                MAX_CACHED_NOTIFICATIONS,
            )
        }
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

    private suspend fun loadNewestV1(
        knownIds: Set<String>,
        stopAtOverlap: Boolean,
    ): List<VrcNotification> = loadNewest(
        knownIds = knownIds,
        stopAtOverlap = stopAtOverlap,
        idOf = VrcNotification::id,
        fetchPage = { offset -> notificationApi.getNotifications(n = PAGE_SIZE, offset = offset) },
    )

    private suspend fun loadNewestV2(
        knownIds: Set<String>,
        stopAtOverlap: Boolean,
    ): List<NotificationV2> = loadNewest(
        knownIds = knownIds,
        stopAtOverlap = stopAtOverlap,
        idOf = NotificationV2::id,
        fetchPage = { offset -> notificationApi.getNotificationsV2(n = PAGE_SIZE, offset = offset) },
    )

    private suspend fun <T> loadNewest(
        knownIds: Set<String>,
        stopAtOverlap: Boolean,
        idOf: (T) -> String,
        fetchPage: suspend (offset: Int) -> List<T>,
    ): List<T> {
        val collected = linkedMapOf<String, T>()
        var offset = 0
        repeat(MAX_REMOTE_PAGES) {
            val page = fetchPage(offset)
            page.forEach { item ->
                val id = idOf(item)
                if (id.isNotBlank()) collected.putIfAbsent(id, item)
            }
            val overlaps = stopAtOverlap && page.any { idOf(it) in knownIds }
            if (page.isEmpty() || page.size < PAGE_SIZE || overlaps) return collected.values.toList()
            offset += page.size
            delay(PAGE_DELAY_MS)
        }
        return collected.values.toList()
    }

    fun handleEvent(event: PipelineEvent) {
        val account = accountSnapshot() ?: return
        synchronized(stateLock) {
            if (!account.isCurrent()) return
            when (event) {
                is PipelineEvent.Notification -> handleV1Notification(event, account)
                is PipelineEvent.NotificationV2 -> handleV2Notification(event, account)
                is PipelineEvent.NotificationV2Delete -> handleV2Delete(event, account)
                is PipelineEvent.NotificationV2Update -> handleV2Update(event, account)
                is PipelineEvent.SeeNotification -> event.content?.jsonPrimitive?.content
                    ?.let { markSeen(it, account) }
                is PipelineEvent.HideNotification -> event.content?.jsonPrimitive?.content
                    ?.let { removeFromLists(it, account) }
                is PipelineEvent.ResponseNotification -> event.content?.jsonObject
                    ?.get("notificationId")
                    ?.jsonPrimitive
                    ?.content
                    ?.let { removeFromLists(it, account) }
                is PipelineEvent.InstanceClosed -> handleInstanceClosed(event)
                PipelineEvent.ClearNotification -> handleClearNotification(account)
                else -> Unit
            }
        }
    }

    private fun handleClearNotification(account: AccountSnapshot) {
        accountGeneration.incrementAndGet()
        removedNotificationIds.clear()
        remoteV1 = emptyList()
        remoteV2 = emptyList()
        local = emptyList()
        publishUnified()
        storageCommands.trySend {
            notificationDao.deleteNotificationsForUser(account.userId)
            notificationDao.deleteNotificationsV2ForUser(account.userId)
        }
    }

    private fun handleV1Notification(event: PipelineEvent.Notification, account: AccountSnapshot) {
        val notification = runCatching {
            event.content?.let { json.decodeFromJsonElement(VrcNotification.serializer(), it) }
        }.getOrNull() ?: return
        removedNotificationIds.remove(notification.id)
        remoteV1 = limitV1(mergeV1(remoteV1, listOf(notification)))
        publishUnified()
        persistNotificationAsync(notification, account.userId)
    }

    private fun handleV2Notification(event: PipelineEvent.NotificationV2, account: AccountSnapshot) {
        val notification = runCatching {
            event.content?.let { json.decodeFromJsonElement(NotificationV2.serializer(), it) }
        }.getOrNull() ?: return
        removedNotificationIds.remove(notification.id)
        remoteV2 = limitV2(mergeV2(remoteV2, listOf(notification)))
        publishUnified()
        persistNotificationV2Async(notification, account.userId)
    }

    private fun handleV2Delete(event: PipelineEvent.NotificationV2Delete, account: AccountSnapshot) {
        val ids = runCatching {
            event.content?.jsonObject?.get("ids")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
        }.getOrDefault(emptyList())
        removeFromLists(ids, account)
    }

    private fun handleV2Update(event: PipelineEvent.NotificationV2Update, account: AccountSnapshot) {
        val obj = event.content?.jsonObject ?: return
        val id = obj["id"]?.jsonPrimitive?.content ?: return
        val updatesJson = obj["updates"]?.jsonObject ?: return
        val existing = remoteV2.firstOrNull { it.id == id } ?: return
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
        remoteV2 = remoteV2.map { if (it.id == id) updated else it }
        publishUnified()
        persistNotificationV2Async(updated, account.userId)
    }

    private fun handleInstanceClosed(event: PipelineEvent.InstanceClosed) {
        val location = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content.orEmpty()
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

    private fun markSeen(id: String, account: AccountSnapshot) {
        remoteV1 = remoteV1.map { if (it.id == id) it.copy(seen = true) else it }
        remoteV2 = remoteV2.map { if (it.id == id) it.copy(seen = true) else it }
        local = local.map { if (it.id == id) it.copy(seen = true) else it }
        publishUnified()
        markSeenInStorageAsync(id, account.userId)
    }

    suspend fun sendInviteToUser(userId: String, messageSlot: Int? = null) {
        notificationApi.sendInvite(userId, createInvitePayload(messageSlot))
    }

    suspend fun sendInviteResponse(notification: UnifiedNotification, responseSlot: Int) {
        require(notification.source == NotificationSource.V1) { "Saved invite responses require a V1 notification" }
        val account = accountSnapshot() ?: return
        notificationApi.sendInviteResponse(
            notificationId = notification.id,
            body = InviteResponseRequest(responseSlot = responseSlot),
        )
        if (!account.isCurrent()) return
        notificationApi.hideNotification(notification.id)
        removeFromLists(notification.id, account)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        val account = accountSnapshot() ?: return
        when {
            notification.source == NotificationSource.V2 -> {
                val primaryResponse = notification.responses.firstOrNull() ?: return
                respondToNotification(notification, primaryResponse.type, account)
            }
            notification.kind == NotificationKind.FRIEND_REQUEST -> {
                notificationApi.acceptFriendRequest(notification.id)
                removeFromLists(notification.id, account)
            }
            notification.kind == NotificationKind.REQUEST_INVITE -> {
                sendInviteToUser(notification.senderUserId)
                if (!account.isCurrent()) return
                notificationApi.hideNotification(notification.id)
                removeFromLists(notification.id, account)
            }
        }
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        val account = accountSnapshot() ?: return
        respondToNotification(notification, responseType, account)
    }

    private suspend fun respondToNotification(
        notification: UnifiedNotification,
        responseType: String,
        account: AccountSnapshot,
    ) {
        if (notification.source != NotificationSource.V2) return
        val response = notification.responses.firstOrNull { it.type == responseType } ?: return
        if (!account.isCurrent()) return
        notificationApi.sendNotificationResponse(
            notification.id,
            NotificationResponse(
                responseType = response.type,
                responseData = response.data,
            ),
        )
        removeFromLists(notification.id, account)
    }

    suspend fun hide(notification: UnifiedNotification) {
        val account = accountSnapshot() ?: return
        when (notification.source) {
            NotificationSource.V1 -> notificationApi.hideNotification(notification.id)
            NotificationSource.V2 -> notificationApi.hideNotificationV2(notification.id)
            NotificationSource.LOCAL -> Unit
        }
        removeFromLists(notification.id, account)
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

    private fun removeFromLists(notificationId: String, account: AccountSnapshot) =
        removeFromLists(listOf(notificationId), account)

    private fun removeFromLists(
        notificationIds: Collection<String>,
        account: AccountSnapshot,
    ) {
        if (notificationIds.isEmpty()) return
        synchronized(stateLock) {
            if (account.isCurrent()) {
                val idSet = notificationIds.toSet()
                removedNotificationIds.addAll(idSet)
                while (removedNotificationIds.size > MAX_CACHED_NOTIFICATIONS) {
                    removedNotificationIds.remove(removedNotificationIds.first())
                }
                remoteV1 = remoteV1.filter { it.id !in idSet }
                remoteV2 = remoteV2.filter { it.id !in idSet }
                local = local.filter { it.id !in idSet }
                publishUnified()
            }
        }
        deleteFromStorageAsync(notificationIds, account.userId)
    }

    private fun publishUnified() {
        _unifiedNotifications.value = (
            remoteV1.map { it.toUnified() } +
                remoteV2.map { it.toUnified() } +
                local
            ).sortedByDescending { it.createdAtEpochMs }
    }

    private fun limitV1(notifications: List<VrcNotification>): List<VrcNotification> =
        if (notifications.size <= MAX_CACHED_NOTIFICATIONS) notifications
        else notifications.sortedByDescending { notificationSortKey(it.createdAt) }
            .take(MAX_CACHED_NOTIFICATIONS)

    private fun limitV2(notifications: List<NotificationV2>): List<NotificationV2> =
        if (notifications.size <= MAX_CACHED_NOTIFICATIONS) notifications
        else notifications.sortedByDescending { notificationSortKey(it.createdAt) }
            .take(MAX_CACHED_NOTIFICATIONS)

    private fun currentUserId(): String? = authRepository.currentUser?.id?.takeIf { it.isNotBlank() }

    private fun accountSnapshot(): AccountSnapshot? {
        val generation = accountGeneration.get()
        val userId = currentUserId() ?: return null
        return AccountSnapshot(userId, generation).takeIf { it.isCurrent() }
    }

    private fun AccountSnapshot.isCurrent(): Boolean = isCurrentAccount(userId, generation)

    private fun isCurrentAccount(userId: String, generation: Long): Boolean =
        generation == accountGeneration.get() && userId == currentUserId()

    private fun notificationSortKey(createdAt: String): Long =
        parseInstantMillisOrNull(createdAt) ?: Long.MIN_VALUE

}
