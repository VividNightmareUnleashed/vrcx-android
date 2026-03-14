package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepository @Inject constructor(
    private val friendApi: FriendApi,
    private val userRepository: UserRepository,
    private val feedRepository: FeedRepository,
    private val json: Json,
) {
    var ownerUserId: String = ""
    private val scope = CoroutineScope(Dispatchers.IO)
    private val OFFLINE_DELAY_MS = 5000L

    private val _friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
    val friends: StateFlow<Map<String, FriendContext>> = _friends.asStateFlow()

    val onlineFriendCount = _friends.map { m -> m.values.count { it.state == FriendState.ONLINE } }
    val offlineFriendCount = _friends.map { m -> m.values.count { it.state == FriendState.OFFLINE } }

    suspend fun loadFriendsList() {
        // Fetch online friends
        val onlineFriends = BulkPaginator.fetchAll(pageSize = 100) { offset, count ->
            friendApi.getFriends(n = count, offset = offset, offline = false)
        }
        // Fetch offline friends
        val offlineFriends = BulkPaginator.fetchAll(pageSize = 100) { offset, count ->
            friendApi.getFriends(n = count, offset = offset, offline = true)
        }

        val friendMap = mutableMapOf<String, FriendContext>()
        for (user in onlineFriends) {
            userRepository.cacheUser(user)
            friendMap[user.id] = FriendContext(
                id = user.id,
                name = user.displayName,
                state = if (user.location.isNullOrEmpty() || user.location == "offline") FriendState.ACTIVE else FriendState.ONLINE,
                ref = user,
            )
        }
        for (user in offlineFriends) {
            userRepository.cacheUser(user)
            if (!friendMap.containsKey(user.id)) {
                friendMap[user.id] = FriendContext(
                    id = user.id,
                    name = user.displayName,
                    state = FriendState.OFFLINE,
                    ref = user,
                )
            }
        }
        _friends.value = friendMap
    }

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.FriendOnline -> handleFriendOnline(event)
            is PipelineEvent.FriendOffline -> handleFriendOffline(event)
            is PipelineEvent.FriendActive -> handleFriendActive(event)
            is PipelineEvent.FriendUpdate -> handleFriendUpdate(event)
            is PipelineEvent.FriendLocation -> handleFriendLocation(event)
            is PipelineEvent.FriendAdd -> handleFriendAdd(event)
            is PipelineEvent.FriendDelete -> handleFriendDelete(event)
            else -> {}
        }
    }

    private fun handleFriendOnline(event: PipelineEvent.FriendOnline) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val user = tryDecodeUser(content["user"])
        val displayName = user?.displayName ?: _friends.value[userId]?.name ?: userId
        val location = content["location"]?.jsonPrimitive?.content ?: ""
        updateFriend(userId) { ctx ->
            ctx.copy(
                state = FriendState.ONLINE,
                ref = user ?: ctx.ref,
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)
        writeFeedOnlineOffline(userId, displayName, "online", location)
    }

    private fun handleFriendOffline(event: PipelineEvent.FriendOffline) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val displayName = _friends.value[userId]?.name ?: userId
        // 5s delay before marking offline
        updateFriend(userId) { it.copy(pendingOffline = true) }
        scope.launch {
            delay(OFFLINE_DELAY_MS)
            val current = _friends.value[userId]
            if (current?.pendingOffline == true) {
                updateFriend(userId) { it.copy(state = FriendState.OFFLINE, pendingOffline = false) }
                writeFeedOnlineOffline(userId, displayName, "offline", "")
            }
        }
    }

    private fun handleFriendActive(event: PipelineEvent.FriendActive) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val user = tryDecodeUser(content["user"])
        updateFriend(userId) { ctx ->
            ctx.copy(
                state = FriendState.ACTIVE,
                ref = user ?: ctx.ref,
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)
    }

    private fun handleFriendUpdate(event: PipelineEvent.FriendUpdate) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val user = tryDecodeUser(content["user"])
        if (user != null) {
            userRepository.cacheUser(user)
            updateFriend(userId) { it.copy(ref = user, name = user.displayName) }
        }
    }

    private fun handleFriendLocation(event: PipelineEvent.FriendLocation) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val location = content["location"]?.jsonPrimitive?.content
        val user = tryDecodeUser(content["user"])
        val displayName = user?.displayName ?: _friends.value[userId]?.name ?: userId
        val previousLocation = _friends.value[userId]?.ref?.location ?: ""
        val worldName = content["world"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: content["worldName"]?.jsonPrimitive?.content
            ?: ""

        updateFriend(userId) { ctx ->
            val newState = when {
                location.isNullOrEmpty() || location == "offline" -> FriendState.OFFLINE
                location == "private" -> FriendState.ACTIVE
                else -> FriendState.ONLINE
            }
            ctx.copy(
                state = newState,
                ref = user ?: ctx.ref,
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)

        // Only write GPS feed for actual world locations, not "private" or "offline"
        if (!location.isNullOrEmpty() && location != "offline" && location != "private" && location != previousLocation) {
            writeFeedGps(userId, displayName, location, worldName, previousLocation)
        }
    }

    private fun handleFriendAdd(event: PipelineEvent.FriendAdd) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val user = tryDecodeUser(content["user"])
        val current = _friends.value.toMutableMap()
        current[userId] = FriendContext(
            id = userId,
            name = user?.displayName ?: userId,
            state = FriendState.OFFLINE,
            ref = user,
        )
        _friends.value = current
    }

    private fun handleFriendDelete(event: PipelineEvent.FriendDelete) {
        val content = event.content?.jsonObject ?: return
        val userId = content["userId"]?.jsonPrimitive?.content ?: return
        val current = _friends.value.toMutableMap()
        current.remove(userId)
        _friends.value = current
    }

    private fun updateFriend(userId: String, update: (FriendContext) -> FriendContext) {
        val current = _friends.value.toMutableMap()
        val existing = current[userId] ?: FriendContext(
            id = userId, name = userId, state = FriendState.OFFLINE,
        )
        current[userId] = update(existing)
        _friends.value = current
    }

    private fun tryDecodeUser(element: kotlinx.serialization.json.JsonElement?): VrcUser? {
        return try {
            element?.let { json.decodeFromJsonElement(VrcUser.serializer(), it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeFeedOnlineOffline(userId: String, displayName: String, type: String, location: String) {
        if (ownerUserId.isEmpty()) return
        scope.launch {
            feedRepository.insertOnlineOffline(
                FeedOnlineOfflineEntity(
                    ownerUserId = ownerUserId,
                    userId = userId,
                    displayName = displayName,
                    type = type,
                    location = location,
                    worldName = "",
                    time = "",
                    groupName = "",
                    createdAt = java.time.Instant.now().toString(),
                )
            )
        }
    }

    private fun writeFeedGps(userId: String, displayName: String, location: String, worldName: String, previousLocation: String) {
        if (ownerUserId.isEmpty()) return
        scope.launch {
            feedRepository.insertGps(
                FeedGpsEntity(
                    ownerUserId = ownerUserId,
                    userId = userId,
                    displayName = displayName,
                    location = location,
                    worldName = worldName,
                    previousLocation = previousLocation,
                    time = "",
                    groupName = "",
                    createdAt = java.time.Instant.now().toString(),
                )
            )
        }
    }
}
