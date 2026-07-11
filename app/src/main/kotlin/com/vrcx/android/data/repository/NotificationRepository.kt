package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

data class UnifiedNotification(
    val id: String,
    val type: String,
    val senderUserId: String,
    val senderUsername: String,
    val message: String,
    val title: String,
    val createdAt: String,
    val seen: Boolean,
    val isV2: Boolean,
    val responses: List<NotificationAction>,
)

private data class InviteContext(
    val location: String,
)

@Singleton
class NotificationRepository @Inject constructor(
    private val notificationApi: NotificationApi,
    private val authRepository: AuthRepository,
    private val notificationDao: NotificationDao,
    private val json: Json,
) {
    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_REMOTE_PAGES = 50
        private const val MAX_CACHED_NOTIFICATIONS = PAGE_SIZE * MAX_REMOTE_PAGES
        private const val MAX_LOCAL_NOTIFICATIONS = 100
    }

    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val stateLock = Any()
    private val accountGeneration = AtomicLong(0)
    private val removedNotificationIds = linkedSetOf<String>()

    init {
        storageScope.launch {
            for (command in storageCommands) runCatching { command() }
        }
    }

    private val _notifications = MutableStateFlow<List<VrcNotification>>(emptyList())
    val notifications: StateFlow<List<VrcNotification>> = _notifications.asStateFlow()

    private val _notificationsV2 = MutableStateFlow<List<NotificationV2>>(emptyList())
    val notificationsV2: StateFlow<List<NotificationV2>> = _notificationsV2.asStateFlow()

    private val _localNotifications = MutableStateFlow<List<UnifiedNotification>>(emptyList())
    val localNotifications: StateFlow<List<UnifiedNotification>> = _localNotifications.asStateFlow()

    val unseenCount = MutableStateFlow(0)

    val unifiedNotifications = combine(_notifications, _notificationsV2, _localNotifications) { v1, v2, local ->
        val fromV1 = v1.map { n ->
            UnifiedNotification(
                id = n.id,
                type = n.type,
                senderUserId = n.senderUserId,
                senderUsername = n.senderUsername,
                message = n.message,
                title = "",
                createdAt = n.createdAt,
                seen = n.seen,
                isV2 = false,
                responses = emptyList(),
            )
        }
        val fromV2 = v2.map { n ->
            UnifiedNotification(
                id = n.id,
                type = n.type,
                senderUserId = n.senderUserId,
                senderUsername = n.senderUsername,
                message = n.message,
                title = n.title,
                createdAt = n.createdAt,
                seen = n.seen,
                isV2 = true,
                responses = n.responses,
            )
        }
        (fromV1 + fromV2 + local).sortedByDescending { notificationSortKey(it.createdAt) }
    }

    suspend fun restoreNotifications() {
        val userId = currentUserId() ?: return
        val generation = accountGeneration.get()
        val v1 = notificationDao.getNotifications(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel() }
        val v2 = notificationDao.getNotificationsV2(userId, MAX_CACHED_NOTIFICATIONS).map { it.toModel() }
        synchronized(stateLock) {
            if (generation != accountGeneration.get() || userId != currentUserId()) return
            _notifications.value = v1.sortedByDescending { notificationSortKey(it.createdAt) }
            _notificationsV2.value = v2.sortedByDescending { notificationSortKey(it.createdAt) }
            recalculateUnseenCount()
        }
    }

    fun resetRuntimeState() = clearRuntimeState()

    fun clearRuntimeState() {
        accountGeneration.incrementAndGet()
        synchronized(stateLock) {
            removedNotificationIds.clear()
            _notifications.value = emptyList()
            _notificationsV2.value = emptyList()
            _localNotifications.value = emptyList()
            unseenCount.value = 0
        }
    }

    suspend fun loadNotifications() {
        val userId = currentUserId() ?: return
        val generation = accountGeneration.get()
        var firstFailure: Throwable? = null

        runCatching {
            val remoteV1 = loadAllV1Notifications()
            val committed = synchronized(stateLock) {
                if (generation != accountGeneration.get() || userId != currentUserId()) null else {
                    (remoteV1 + _notifications.value)
                        .filterNot { it.id in removedNotificationIds }
                        .associateBy { it.id }.values
                        .sortedByDescending { notificationSortKey(it.createdAt) }
                        .also { _notifications.value = it }
                }
            } ?: return@runCatching
            notificationDao.replaceNotifications(userId, committed.map { it.toEntity(userId) })
        }.onFailure { error ->
            firstFailure = error
        }

        runCatching {
            val remoteV2 = loadAllV2Notifications()
            val committed = synchronized(stateLock) {
                if (generation != accountGeneration.get() || userId != currentUserId()) null else {
                    (remoteV2 + _notificationsV2.value)
                        .filterNot { it.id in removedNotificationIds }
                        .associateBy { it.id }.values
                        .sortedByDescending { notificationSortKey(it.createdAt) }
                        .also { _notificationsV2.value = it }
                }
            } ?: return@runCatching
            notificationDao.replaceNotificationsV2(userId, committed.map { it.toEntity(userId) })
        }.onFailure { error ->
            if (firstFailure == null) {
                firstFailure = error
            } else {
                firstFailure?.addSuppressed(error)
            }
        }

        recalculateUnseenCount()
        firstFailure?.let { throw it }
    }

    fun handleEvent(event: PipelineEvent) {
        synchronized(stateLock) { when (event) {
            is PipelineEvent.Notification -> handleV1Notification(event)
            is PipelineEvent.NotificationV2 -> handleV2Notification(event)
            is PipelineEvent.NotificationV2Delete -> handleV2Delete(event)
            is PipelineEvent.NotificationV2Update -> handleV2Update(event)
            is PipelineEvent.SeeNotification -> handleSee(event)
            is PipelineEvent.HideNotification -> handleHide(event)
            is PipelineEvent.ResponseNotification -> handleResponse(event)
            is PipelineEvent.InstanceClosed -> handleInstanceClosed(event)
            PipelineEvent.ClearNotification -> handleClearNotification()
            else -> {}
        } }
    }

    private fun handleClearNotification() {
        val userId = currentUserId() ?: return
        accountGeneration.incrementAndGet()
        removedNotificationIds.clear()
        _notifications.value = emptyList()
        _notificationsV2.value = emptyList()
        _localNotifications.value = emptyList()
        unseenCount.value = 0
        storageCommands.trySend {
            notificationDao.deleteNotificationsForUser(userId)
            notificationDao.deleteNotificationsV2ForUser(userId)
        }
    }

    private fun handleV1Notification(event: PipelineEvent.Notification) {
        val notif = try {
            event.content?.let { json.decodeFromJsonElement(VrcNotification.serializer(), it) }
        } catch (_: Exception) {
            null
        } ?: return
        _notifications.value = (_notifications.value.filter { it.id != notif.id } + notif)
            .sortedByDescending { notificationSortKey(it.createdAt) }
        persistNotificationAsync(notif)
        recalculateUnseenCount()
    }

    private fun handleV2Notification(event: PipelineEvent.NotificationV2) {
        val notif = try {
            event.content?.let { json.decodeFromJsonElement(NotificationV2.serializer(), it) }
        } catch (_: Exception) {
            null
        } ?: return
        val updated = _notificationsV2.value.toMutableList()
        val existing = updated.indexOfFirst { it.id == notif.id }
        if (existing >= 0) {
            updated[existing] = notif
        } else {
            updated += notif
        }
        _notificationsV2.value = updated.sortedByDescending { notificationSortKey(it.createdAt) }
        persistNotificationV2Async(notif)
        recalculateUnseenCount()
    }

    private fun handleV2Delete(event: PipelineEvent.NotificationV2Delete) {
        val ids = try {
            event.content?.jsonObject?.get("ids")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        removeFromLists(ids)
    }

    private fun handleV2Update(event: PipelineEvent.NotificationV2Update) {
        val obj = event.content?.jsonObject ?: return
        val id = obj["id"]?.jsonPrimitive?.content ?: return
        val updatesJson = obj["updates"]?.jsonObject ?: return
        val existing = _notificationsV2.value.firstOrNull { it.id == id }
        val existingObject = existing?.let {
            json.encodeToJsonElement(NotificationV2.serializer(), it).jsonObject
        } ?: return
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
        _notificationsV2.value = (_notificationsV2.value.filter { it.id != id } + updated)
            .sortedByDescending { notificationSortKey(it.createdAt) }
        persistNotificationV2Async(updated)
        recalculateUnseenCount()
    }

    private fun handleSee(event: PipelineEvent.SeeNotification) {
        val id = event.content?.jsonPrimitive?.content ?: return
        markSeen(id)
    }

    private fun handleHide(event: PipelineEvent.HideNotification) {
        val id = event.content?.jsonPrimitive?.content ?: return
        removeFromLists(id)
    }

    private fun handleResponse(event: PipelineEvent.ResponseNotification) {
        val id = event.content?.jsonObject?.get("notificationId")?.jsonPrimitive?.content ?: return
        removeFromLists(id)
    }

    private fun handleInstanceClosed(event: PipelineEvent.InstanceClosed) {
        val location = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content.orEmpty()
        val notification = UnifiedNotification(
            id = "local:instance.closed:${Instant.now()}",
            type = "instance.closed",
            senderUserId = "",
            senderUsername = "System",
            message = location.ifBlank { "A queued instance closed" },
            title = "Instance Closed",
            createdAt = Instant.now().toString(),
            seen = false,
            isV2 = false,
            responses = emptyList(),
        )
        _localNotifications.value = (listOf(notification) + _localNotifications.value)
            .take(MAX_LOCAL_NOTIFICATIONS)
        recalculateUnseenCount()
    }

    private fun markSeen(id: String) {
        _notifications.value = _notifications.value.map {
            if (it.id == id) it.copy(seen = true) else it
        }
        _notificationsV2.value = _notificationsV2.value.map {
            if (it.id == id) it.copy(seen = true) else it
        }
        _localNotifications.value = _localNotifications.value.map {
            if (it.id == id) it.copy(seen = true) else it
        }
        markSeenInStorageAsync(id)
        recalculateUnseenCount()
    }

    private fun recalculateUnseenCount() {
        unseenCount.value = _notifications.value.count { !it.seen } +
            _notificationsV2.value.count { !it.seen } +
            _localNotifications.value.count { !it.seen }
    }

    suspend fun acceptFriendRequest(notificationId: String) {
        notificationApi.acceptFriendRequest(notificationId)
        removeFromLists(notificationId)
    }

    suspend fun hideNotification(notificationId: String) {
        notificationApi.hideNotification(notificationId)
        removeFromLists(notificationId)
    }

    suspend fun seeNotification(notificationId: String) {
        notificationApi.seeNotification(notificationId)
        markSeen(notificationId)
    }

    suspend fun sendInviteToUser(userId: String, messageSlot: Int? = null) {
        notificationApi.sendInvite(userId, createInvitePayload(messageSlot))
    }

    suspend fun sendInviteResponse(notificationId: String, responseSlot: Int) {
        notificationApi.sendInviteResponse(
            notificationId = notificationId,
            body = InviteResponseRequest(responseSlot = responseSlot),
        )
        notificationApi.hideNotification(notificationId)
        removeFromLists(notificationId)
    }

    suspend fun acceptInvite(notificationId: String, isV2: Boolean) {
        val notification = if (isV2) {
            _notificationsV2.value.firstOrNull { it.id == notificationId }?.let { n ->
                UnifiedNotification(
                    id = n.id,
                    type = n.type,
                    senderUserId = n.senderUserId,
                    senderUsername = n.senderUsername,
                    message = n.message,
                    title = n.title,
                    createdAt = n.createdAt,
                    seen = n.seen,
                    isV2 = true,
                    responses = n.responses,
                )
            }
        } else {
            _notifications.value.firstOrNull { it.id == notificationId }?.let { n ->
                UnifiedNotification(
                    id = n.id,
                    type = n.type,
                    senderUserId = n.senderUserId,
                    senderUsername = n.senderUsername,
                    message = n.message,
                    title = "",
                    createdAt = n.createdAt,
                    seen = n.seen,
                    isV2 = false,
                    responses = emptyList(),
                )
            }
        } ?: return
        performPrimaryAction(notification)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        when {
            notification.isV2 -> {
                val primaryResponse = notification.responses.firstOrNull() ?: return
                respondToNotification(notification, primaryResponse.type)
            }
            notification.type == "friendRequest" -> acceptFriendRequest(notification.id)
            notification.type == "requestInvite" -> {
                acceptRequestInvite(notification)
                removeFromLists(notification.id)
            }
        }
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        if (!notification.isV2) return
        val response = notification.responses.firstOrNull { it.type == responseType } ?: return
        notificationApi.sendNotificationResponse(
            notification.id,
            NotificationResponse(
                responseType = response.type,
                responseData = response.data,
            ),
        )
        removeFromLists(notification.id)
    }

    suspend fun declineInvite(notificationId: String, isV2: Boolean) {
        if (isV2) {
            notificationApi.hideNotificationV2(notificationId)
        } else {
            notificationApi.hideNotification(notificationId)
        }
        removeFromLists(notificationId)
    }

    suspend fun hideUnified(notificationId: String, isV2: Boolean) {
        if (notificationId.startsWith("local:")) {
            removeFromLists(notificationId)
            return
        }
        if (isV2) {
            notificationApi.hideNotificationV2(notificationId)
        } else {
            notificationApi.hideNotification(notificationId)
        }
        removeFromLists(notificationId)
    }

    private suspend fun acceptRequestInvite(notification: UnifiedNotification) {
        sendInviteToUser(notification.senderUserId)
        notificationApi.hideNotification(notification.id)
    }

    private suspend fun createInvitePayload(messageSlot: Int?): InviteRequest {
        val context = resolveInviteContext()
        return InviteRequest(
            instanceId = context.location,
            messageSlot = messageSlot,
        )
    }

    private suspend fun resolveInviteContext(): InviteContext {
        val currentUser = authRepository.currentUser
            ?: error("Current user is not available")
        val currentLocation = when (currentUser.location) {
            "traveling" -> currentUser.travelingToLocation
            else -> currentUser.location
        } ?: error("You must be in a world to send invites")

        if (
            currentLocation.isBlank() ||
            currentLocation == "offline" ||
            currentLocation == "private" ||
            currentLocation == "traveling"
        ) {
            error("You must be in a world to send invites")
        }

        return InviteContext(
            location = currentLocation,
        )
    }

    private suspend fun loadAllV1Notifications(): List<VrcNotification> {
        val collected = linkedMapOf<String, VrcNotification>()
        var offset = 0
        var page = 0
        while (page < MAX_REMOTE_PAGES) {
            val batch = notificationApi.getNotifications(n = PAGE_SIZE, offset = offset)
            batch.forEach { notification ->
                if (notification.id.isNotBlank() && notification.id !in collected) {
                    collected[notification.id] = notification
                }
            }
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
            page++
        }
        return collected.values.sortedByDescending { it.createdAt }
    }

    private suspend fun loadAllV2Notifications(): List<NotificationV2> {
        val collected = linkedMapOf<String, NotificationV2>()
        var offset = 0
        var page = 0
        while (page < MAX_REMOTE_PAGES) {
            val batch = notificationApi.getNotificationsV2(n = PAGE_SIZE, offset = offset)
            batch.forEach { notification ->
                if (notification.id.isNotBlank() && notification.id !in collected) {
                    collected[notification.id] = notification
                }
            }
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
            page++
        }
        return collected.values.sortedByDescending { it.createdAt }
    }

    private fun persistNotificationAsync(notification: VrcNotification) {
        val userId = currentUserId() ?: return
        storageCommands.trySend { notificationDao.insertNotification(notification.toEntity(userId)) }
    }

    private fun persistNotificationV2Async(notification: NotificationV2) {
        val userId = currentUserId() ?: return
        storageCommands.trySend { notificationDao.insertNotificationV2(notification.toEntity(userId)) }
    }

    private fun markSeenInStorageAsync(notificationId: String) {
        if (notificationId.startsWith("local:")) return
        val userId = currentUserId() ?: return
        storageCommands.trySend {
            notificationDao.markSeen(userId, notificationId)
            notificationDao.markSeenV2(userId, notificationId)
        }
    }

    private fun deleteFromStorageAsync(notificationIds: Collection<String>) {
        val persistedIds = notificationIds.filter { !it.startsWith("local:") }
        if (persistedIds.isEmpty()) return
        val userId = currentUserId() ?: return
        storageCommands.trySend {
            if (persistedIds.size == 1) {
                val notificationId = persistedIds.first()
                notificationDao.deleteNotification(userId, notificationId)
                notificationDao.deleteNotificationV2(userId, notificationId)
            } else {
                notificationDao.deleteNotifications(userId, persistedIds)
                notificationDao.deleteNotificationsV2(userId, persistedIds)
            }
        }
    }

    private fun removeFromLists(notificationId: String) {
        removeFromLists(listOf(notificationId))
    }

    private fun removeFromLists(notificationIds: Collection<String>) {
        if (notificationIds.isEmpty()) return
        val idSet = notificationIds.toSet()
        removedNotificationIds.addAll(idSet)
        while (removedNotificationIds.size > MAX_CACHED_NOTIFICATIONS) {
            removedNotificationIds.remove(removedNotificationIds.first())
        }
        _notifications.value = _notifications.value.filter { it.id !in idSet }
        _notificationsV2.value = _notificationsV2.value.filter { it.id !in idSet }
        _localNotifications.value = _localNotifications.value.filter { it.id !in idSet }
        deleteFromStorageAsync(idSet)
        recalculateUnseenCount()
    }

    private fun currentUserId(): String? = authRepository.currentUser?.id?.takeIf { it.isNotBlank() }

    private fun notificationSortKey(createdAt: String): Long =
        runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)

    private fun VrcNotification.toEntity(ownerUserId: String) = NotificationEntity(
        id = id,
        ownerUserId = ownerUserId,
        type = type,
        senderUserId = senderUserId,
        senderUsername = senderUsername,
        receiverUserId = receiverUserId,
        message = message,
        details = details?.toString().orEmpty(),
        seen = seen,
        createdAt = createdAt,
    )

    private fun NotificationEntity.toModel() = VrcNotification(
        createdAt = createdAt,
        details = details.toJsonElementOrNull(),
        id = id,
        message = message,
        receiverUserId = receiverUserId,
        seen = seen,
        senderUserId = senderUserId,
        senderUsername = senderUsername,
        type = type,
    )

    private fun NotificationV2.toEntity(ownerUserId: String) = NotificationV2Entity(
        id = id,
        ownerUserId = ownerUserId,
        version = version,
        type = type,
        category = category,
        isSystem = isSystem,
        ignoreDND = ignoreDND,
        senderUserId = senderUserId,
        senderUsername = senderUsername,
        receiverUserId = receiverUserId,
        relatedNotificationsId = relatedNotificationsId,
        title = title,
        message = message,
        seen = seen,
        responsesJson = json.encodeToString(ListSerializer(NotificationAction.serializer()), responses),
        responseDataJson = responseData?.toString().orEmpty(),
        expiresAt = expiresAt,
        expiryAfterSeen = expiryAfterSeen,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun NotificationV2Entity.toModel() = NotificationV2(
        id = id,
        version = version,
        type = type,
        category = category,
        isSystem = isSystem,
        ignoreDND = ignoreDND,
        senderUserId = senderUserId,
        senderUsername = senderUsername,
        receiverUserId = receiverUserId,
        relatedNotificationsId = relatedNotificationsId,
        title = title,
        message = message,
        seen = seen,
        responses = responsesJson.toNotificationActions(),
        responseData = responseDataJson.toJsonElementOrNull(),
        expiresAt = expiresAt,
        expiryAfterSeen = expiryAfterSeen,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun String.toJsonElementOrNull(): JsonElement? {
        if (isBlank()) return null
        return runCatching { json.parseToJsonElement(this) }.getOrNull()
    }

    private fun String.toNotificationActions(): List<NotificationAction> {
        if (isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NotificationAction.serializer()), this)
        }.getOrDefault(emptyList())
    }
}
