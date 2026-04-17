package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepository @Inject constructor(
    private val friendApi: FriendApi,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val feedRepository: FeedRepository,
    private val favoriteRepository: FavoriteRepository,
    private val friendLogDao: FriendLogDao,
    private val friendNotifyDao: FriendNotifyDao,
    private val json: Json,
) {
    private data class FriendLogSnapshot(
        val compositeId: String,
        val userId: String,
        val displayName: String,
        val trustLevel: String,
        val friendNumber: Int,
    )

    var ownerUserId: String = ""
    private val scope = CoroutineScope(Dispatchers.IO)
    private val OFFLINE_DELAY_MS = 5000L
    private val DEDUP_WINDOW_MS = 10_000L
    private val recentFeedWrites = ConcurrentHashMap<String, Long>()
    private val _favoriteFriendIds = MutableStateFlow<Set<String>>(emptySet())
    private val _notifyEnabledIds = MutableStateFlow<Set<String>>(emptySet())

    private val friendsMutex = Mutex()
    private val _friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
    val friends: StateFlow<Map<String, FriendContext>> = _friends.asStateFlow()

    private val _confirmedOfflineEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val confirmedOfflineEvents: SharedFlow<Pair<String, String>> = _confirmedOfflineEvents.asSharedFlow()

    val onlineFriendCount = _friends.map { m -> m.values.count { it.state == FriendState.ONLINE } }
    val offlineFriendCount = _friends.map { m -> m.values.count { it.state == FriendState.OFFLINE } }

    init {
        scope.launch {
            favoriteRepository.favorites.collect { favorites ->
                _favoriteFriendIds.value = favorites
                    .filter { it.type == "friend" }
                    .map { it.favoriteId }
                    .toSet()
                applyFavoriteFlags()
            }
        }
    }

    suspend fun loadFriendsList() {
        ensureOwnerUserId()
        recentFeedWrites.clear()
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
        _friends.value = decorateFriendMap(friendMap)
        syncFriendLog(friendMap)

        try {
            favoriteRepository.loadFavorites(type = "friend")
        } catch (_: Exception) {}

        // Load notification-enabled friend IDs
        try {
            if (ownerUserId.isNotEmpty()) {
                val enabledIds = friendNotifyDao.getEnabledFriendIdsSnapshot(ownerUserId)
                _notifyEnabledIds.value = enabledIds.toSet()
                _friends.value = decorateFriendMap(_friends.value)
            }
        } catch (_: Exception) {}
    }

    suspend fun toggleFriendNotify(friendUserId: String): Boolean {
        if (ownerUserId.isEmpty()) return false
        val compositeId = "$ownerUserId:$friendUserId"
        val existing = friendNotifyDao.get(compositeId)
        val newEnabled: Boolean
        if (existing != null) {
            friendNotifyDao.delete(compositeId)
            newEnabled = false
        } else {
            friendNotifyDao.insert(FriendNotifyEntity(
                compositeId = compositeId,
                ownerUserId = ownerUserId,
                friendUserId = friendUserId,
            ))
            newEnabled = true
        }
        _notifyEnabledIds.value = if (newEnabled) {
            _notifyEnabledIds.value + friendUserId
        } else {
            _notifyEnabledIds.value - friendUserId
        }
        friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            current[friendUserId]?.let { current[friendUserId] = it.copy(notifyEnabled = newEnabled) }
            _friends.value = current
        }
        return newEnabled
    }

    fun observeNotifyEnabledIds(ownerUserId: String): Flow<Set<String>> {
        return friendNotifyDao.getEnabledFriendIds(ownerUserId).map { it.toSet() }
    }

    suspend fun handleEvent(event: PipelineEvent) {
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

    private suspend fun handleFriendOnline(event: PipelineEvent.FriendOnline) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"])
        val displayName = user?.displayName ?: _friends.value[userId]?.name ?: userId
        val location = content["location"]?.jsonPrimitive?.content ?: ""
        val travelingToLocation = content["travelingToLocation"]?.jsonPrimitive?.content
        val platform = content["platform"]?.jsonPrimitive?.content
        val instanceId = parseInstanceId(location)
        val travelingToWorld = travelingToLocation?.substringBefore(":")?.takeIf { it.startsWith("wrld_") }
        val travelingToInstance = parseInstanceId(travelingToLocation)
        updateFriend(userId) { ctx ->
            ctx.copy(
                state = FriendState.ONLINE,
                ref = (user ?: ctx.ref)?.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    instanceId = instanceId,
                    platform = platform,
                    state = "online",
                ),
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)
        writeFeedOnlineOffline(userId, displayName, "online", location)
    }

    private suspend fun handleFriendOffline(event: PipelineEvent.FriendOffline) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val payloadUser = tryDecodeUser(content["user"])
        if (payloadUser != null) userRepository.cacheUser(payloadUser)
        val displayName = payloadUser?.displayName ?: _friends.value[userId]?.name ?: userId
        // 5s delay before marking offline
        updateFriend(userId) { ctx ->
            ctx.copy(
                pendingOffline = true,
                ref = (payloadUser ?: ctx.ref),
                name = payloadUser?.displayName ?: ctx.name,
            )
        }
        scope.launch {
            delay(OFFLINE_DELAY_MS)
            val previous = updateFriend(userId) { ctx ->
                if (ctx.pendingOffline) {
                    ctx.copy(
                        state = FriendState.OFFLINE,
                        pendingOffline = false,
                        ref = ctx.ref?.copy(
                            location = "offline",
                            travelingToLocation = "offline",
                            travelingToWorld = "offline",
                            travelingToInstance = "offline",
                            instanceId = "offline",
                        ),
                    )
                } else {
                    ctx
                }
            }
            if (previous.pendingOffline) {
                writeFeedOnlineOffline(userId, displayName, "offline", "")
                _confirmedOfflineEvents.emit(userId to displayName)
            }
        }
    }

    private suspend fun handleFriendActive(event: PipelineEvent.FriendActive) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"])
        val platform = content["platform"]?.jsonPrimitive?.content
        updateFriend(userId) { ctx ->
            ctx.copy(
                state = FriendState.ACTIVE,
                ref = (user ?: ctx.ref)?.copy(
                    location = "offline",
                    travelingToLocation = "offline",
                    travelingToWorld = "offline",
                    travelingToInstance = "offline",
                    instanceId = "offline",
                    platform = platform,
                ),
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)
    }

    private suspend fun handleFriendUpdate(event: PipelineEvent.FriendUpdate) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"]) ?: return

        val previous = updateFriend(userId) { it.copy(ref = user, name = user.displayName) }
        userRepository.cacheUser(user)
        syncFriendLogCurrent(
            previous = previous,
            userId = userId,
            displayName = user.displayName,
            tags = user.tags,
        )

        val prevRef = previous.ref ?: return
        // Status change (skip offline transitions — handled by online/offline events)
        if ((user.status != prevRef.status || user.statusDescription != prevRef.statusDescription)
            && user.status != "offline" && prevRef.status != "offline") {
            writeFeedStatus(userId, user.displayName, user.status, user.statusDescription, prevRef.status, prevRef.statusDescription)
        }
        // Bio change (skip if either is empty — initial load artifact)
        if (user.bio != prevRef.bio && user.bio.isNotEmpty() && prevRef.bio.isNotEmpty()) {
            writeFeedBio(userId, user.displayName, user.bio, prevRef.bio)
        }
        // Avatar change
        if (user.currentAvatarThumbnailImageUrl != prevRef.currentAvatarThumbnailImageUrl
            && user.currentAvatarThumbnailImageUrl.isNotEmpty()) {
            writeFeedAvatar(userId, user.displayName, user.currentAvatarImageUrl, user.currentAvatarThumbnailImageUrl, prevRef.currentAvatarImageUrl, prevRef.currentAvatarThumbnailImageUrl)
        }
    }

    private suspend fun handleFriendLocation(event: PipelineEvent.FriendLocation) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val location = content["location"]?.jsonPrimitive?.content
        val user = tryDecodeUser(content["user"])
        val worldName = content["world"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: content["worldName"]?.jsonPrimitive?.content
            ?: ""
        val travelingToLocation = content["travelingToLocation"]?.jsonPrimitive?.content
        val instanceId = parseInstanceId(location)
        val travelingToWorld = travelingToLocation?.substringBefore(":")?.takeIf { it.startsWith("wrld_") }
        val travelingToInstance = parseInstanceId(travelingToLocation)

        val previous = updateFriend(userId) { ctx ->
            val newState = when {
                location.isNullOrEmpty() || location == "offline" -> FriendState.OFFLINE
                location == "private" -> FriendState.ACTIVE
                else -> FriendState.ONLINE
            }
            ctx.copy(
                state = newState,
                ref = (user ?: ctx.ref)?.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    instanceId = instanceId,
                    state = "online",
                ),
                name = user?.displayName ?: ctx.name,
                pendingOffline = false,
            )
        }
        if (user != null) userRepository.cacheUser(user)

        val previousLocation = previous.ref?.location ?: ""
        val displayName = user?.displayName ?: previous.name

        // Only write GPS feed for actual world locations, not "private" or "offline"
        if (!location.isNullOrEmpty() && location != "offline" && location != "private" && location != previousLocation) {
            writeFeedGps(userId, displayName, location, worldName, previousLocation)
        }
    }

    private suspend fun handleFriendAdd(event: PipelineEvent.FriendAdd) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"])
        updateFriend(userId) {
            FriendContext(
                id = userId,
                name = user?.displayName ?: userId,
                state = FriendState.OFFLINE,
                ref = user,
                isVIP = userId in _favoriteFriendIds.value,
                notifyEnabled = userId in _notifyEnabledIds.value,
            )
        }
        syncFriendAdded(userId, user)
    }

    private suspend fun handleFriendDelete(event: PipelineEvent.FriendDelete) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        syncFriendRemoved(userId)
        friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            current.remove(userId)
            _friends.value = current
        }
    }

    private suspend fun updateFriend(userId: String, update: (FriendContext) -> FriendContext): FriendContext {
        return friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            val existing = current[userId] ?: FriendContext(
                id = userId, name = userId, state = FriendState.OFFLINE,
                isVIP = userId in _favoriteFriendIds.value,
                notifyEnabled = userId in _notifyEnabledIds.value,
            )
            current[userId] = decorateFriendContext(update(existing))
            _friends.value = current
            existing
        }
    }

    private suspend fun applyFavoriteFlags() {
        friendsMutex.withLock {
            _friends.value = decorateFriendMap(_friends.value)
        }
    }

    private fun decorateFriendMap(friendMap: Map<String, FriendContext>): Map<String, FriendContext> {
        return friendMap.mapValues { (_, ctx) -> decorateFriendContext(ctx) }
    }

    private fun decorateFriendContext(ctx: FriendContext): FriendContext {
        return ctx.copy(
            isVIP = ctx.id in _favoriteFriendIds.value,
            notifyEnabled = ctx.id in _notifyEnabledIds.value,
        )
    }

    private fun tryDecodeUser(element: kotlinx.serialization.json.JsonElement?): VrcUser? {
        return try {
            element?.let { json.decodeFromJsonElement(VrcUser.serializer(), it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Some pipeline payloads ("friend-active" historically, occasionally others) use the
     * lowercase key "userid" rather than "userId". Accept both so events aren't silently
     * dropped if VRChat changes which variant a given event carries.
     */
    internal fun resolveFriendUserId(content: kotlinx.serialization.json.JsonObject): String? {
        return content["userId"]?.jsonPrimitive?.content
            ?: content["userid"]?.jsonPrimitive?.content
    }

    /** Extracts instanceId from a VRChat location string (format: "worldId:instanceId"). */
    private fun parseInstanceId(location: String?): String? {
        if (location.isNullOrEmpty() || location == "offline" || location == "private") return null
        val colonIndex = location.indexOf(':')
        return if (colonIndex >= 0) location.substring(colonIndex + 1) else null
    }

    private fun ensureOwnerUserId(): String {
        if (ownerUserId.isNotEmpty()) {
            return ownerUserId
        }
        ownerUserId = authRepository.currentUser?.id.orEmpty()
        return ownerUserId
    }

    private suspend fun syncFriendLog(friendMap: Map<String, FriendContext>) {
        val ownerId = ensureOwnerUserId()
        if (ownerId.isEmpty()) return

        val currentEntries = friendLogDao.getCurrentFriends(ownerId)
        val currentByCompositeId = currentEntries.associateBy { it.odUserId }
        if (currentEntries.isEmpty()) {
            friendMap.values.forEach { ctx ->
                friendLogDao.insertCurrent(
                    createCurrentEntry(
                        ownerId = ownerId,
                        userId = ctx.id,
                        displayName = ctx.ref?.displayName ?: ctx.name,
                        tags = ctx.ref?.tags ?: emptyList(),
                        friendNumber = 0,
                    )
                )
            }
            return
        }

        var nextFriendNumber = (currentEntries.maxOfOrNull { it.friendNumber } ?: 0) + 1
        val seenCompositeIds = mutableSetOf<String>()

        friendMap.values.forEach { ctx ->
            val compositeId = compositeId(ownerId, ctx.id)
            val existing = currentByCompositeId[compositeId]
            val displayName = ctx.ref?.displayName ?: ctx.name
            val trustLevel = trustLevelLabel(ctx.ref?.tags ?: emptyList())
            seenCompositeIds += compositeId

            if (existing == null) {
                val snapshot = FriendLogSnapshot(
                    compositeId = compositeId,
                    userId = ctx.id,
                    displayName = displayName,
                    trustLevel = trustLevel,
                    friendNumber = nextFriendNumber++,
                )
                insertFriendHistory(ownerId, "Friend", snapshot)
                friendLogDao.insertCurrent(snapshot.toCurrentEntry(ownerId))
                return@forEach
            }

            if (existing.odDisplayName != displayName && existing.odDisplayName.isNotBlank()) {
                insertFriendHistory(
                    ownerId = ownerId,
                    type = "DisplayName",
                    snapshot = FriendLogSnapshot(
                        compositeId = compositeId,
                        userId = ctx.id,
                        displayName = displayName,
                        trustLevel = trustLevel,
                        friendNumber = existing.friendNumber,
                    ),
                    previousDisplayName = existing.odDisplayName,
                )
            }

            if (existing.trustLevel != trustLevel &&
                existing.trustLevel.isNotBlank() &&
                trustLevel.isNotBlank()
            ) {
                insertFriendHistory(
                    ownerId = ownerId,
                    type = "TrustLevel",
                    snapshot = FriendLogSnapshot(
                        compositeId = compositeId,
                        userId = ctx.id,
                        displayName = displayName,
                        trustLevel = trustLevel,
                        friendNumber = existing.friendNumber,
                    ),
                    previousTrustLevel = existing.trustLevel,
                )
            }

            if (existing.odDisplayName != displayName || existing.trustLevel != trustLevel) {
                friendLogDao.insertCurrent(
                    createCurrentEntry(
                        ownerId = ownerId,
                        userId = ctx.id,
                        displayName = displayName,
                        tags = ctx.ref?.tags ?: emptyList(),
                        friendNumber = existing.friendNumber,
                    )
                )
            }
        }

        currentEntries
            .filter { it.odUserId !in seenCompositeIds }
            .forEach { missing ->
                insertFriendHistory(
                    ownerId = ownerId,
                    type = "Unfriend",
                    snapshot = FriendLogSnapshot(
                        compositeId = missing.odUserId,
                        userId = missing.odUserId.substringAfter(':', missing.odUserId),
                        displayName = missing.odDisplayName,
                        trustLevel = missing.trustLevel,
                        friendNumber = missing.friendNumber,
                    ),
                )
                friendLogDao.deleteCurrent(missing.odUserId)
            }
    }

    private suspend fun syncFriendCurrent(
        userId: String,
        displayName: String,
        tags: List<String>,
    ) {
        val ownerId = ensureOwnerUserId()
        if (ownerId.isEmpty()) return

        val compositeId = compositeId(ownerId, userId)
        val existing = friendLogDao.getCurrent(compositeId)
        val trustLevel = trustLevelLabel(tags)
        if (existing == null) {
            friendLogDao.insertCurrent(
                createCurrentEntry(
                    ownerId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    tags = tags,
                    friendNumber = (friendLogDao.getMaxFriendNumber(ownerId) ?: 0) + 1,
                )
            )
            return
        }

        if (existing.odDisplayName == displayName && existing.trustLevel == trustLevel) {
            return
        }

        if (existing.odDisplayName != displayName && existing.odDisplayName.isNotBlank()) {
            insertFriendHistory(
                ownerId = ownerId,
                type = "DisplayName",
                snapshot = FriendLogSnapshot(
                    compositeId = compositeId,
                    userId = userId,
                    displayName = displayName,
                    trustLevel = trustLevel,
                    friendNumber = existing.friendNumber,
                ),
                previousDisplayName = existing.odDisplayName,
            )
        }

        if (existing.trustLevel != trustLevel &&
            existing.trustLevel.isNotBlank() &&
            trustLevel.isNotBlank()
        ) {
            insertFriendHistory(
                ownerId = ownerId,
                type = "TrustLevel",
                snapshot = FriendLogSnapshot(
                    compositeId = compositeId,
                    userId = userId,
                    displayName = displayName,
                    trustLevel = trustLevel,
                    friendNumber = existing.friendNumber,
                ),
                previousTrustLevel = existing.trustLevel,
            )
        }

        friendLogDao.insertCurrent(
            createCurrentEntry(
                ownerId = ownerId,
                userId = userId,
                displayName = displayName,
                tags = tags,
                friendNumber = existing.friendNumber,
            )
        )
    }

    private suspend fun syncFriendLogCurrent(
        previous: FriendContext,
        userId: String,
        displayName: String,
        tags: List<String>,
    ) {
        val previousDisplayName = previous.ref?.displayName ?: previous.name
        val previousTrustLevel = trustLevelLabel(previous.ref?.tags ?: emptyList())
        if (previousDisplayName == displayName && previousTrustLevel == trustLevelLabel(tags)) {
            return
        }
        syncFriendCurrent(userId, displayName, tags)
    }

    private suspend fun syncFriendAdded(userId: String, user: VrcUser?) {
        val ownerId = ensureOwnerUserId()
        if (ownerId.isEmpty()) return

        val compositeId = compositeId(ownerId, userId)
        if (friendLogDao.getCurrent(compositeId) != null) {
            return
        }

        val snapshot = FriendLogSnapshot(
            compositeId = compositeId,
            userId = userId,
            displayName = user?.displayName ?: userId,
            trustLevel = trustLevelLabel(user?.tags ?: emptyList()),
            friendNumber = (friendLogDao.getMaxFriendNumber(ownerId) ?: 0) + 1,
        )
        insertFriendHistory(ownerId, "Friend", snapshot)
        friendLogDao.insertCurrent(snapshot.toCurrentEntry(ownerId))
    }

    private suspend fun syncFriendRemoved(userId: String) {
        val ownerId = ensureOwnerUserId()
        if (ownerId.isEmpty()) return

        val compositeId = compositeId(ownerId, userId)
        val existing = friendLogDao.getCurrent(compositeId) ?: return
        insertFriendHistory(
            ownerId = ownerId,
            type = "Unfriend",
            snapshot = FriendLogSnapshot(
                compositeId = compositeId,
                userId = userId,
                displayName = existing.odDisplayName,
                trustLevel = existing.trustLevel,
                friendNumber = existing.friendNumber,
            ),
        )
        friendLogDao.deleteCurrent(compositeId)
    }

    private suspend fun insertFriendHistory(
        ownerId: String,
        type: String,
        snapshot: FriendLogSnapshot,
        previousDisplayName: String = "",
        previousTrustLevel: String = "",
    ) {
        friendLogDao.insertHistory(
            FriendLogHistoryEntity(
                ownerUserId = ownerId,
                type = type,
                odUserId = snapshot.compositeId,
                displayName = snapshot.displayName,
                previousDisplayName = previousDisplayName,
                trustLevel = snapshot.trustLevel,
                previousTrustLevel = previousTrustLevel,
                friendNumber = snapshot.friendNumber,
                createdAt = Instant.now().toString(),
            )
        )
    }

    private fun createCurrentEntry(
        ownerId: String,
        userId: String,
        displayName: String,
        tags: List<String>,
        friendNumber: Int,
    ): FriendLogCurrentEntity {
        return FriendLogCurrentEntity(
            odUserId = compositeId(ownerId, userId),
            ownerUserId = ownerId,
            odDisplayName = displayName,
            trustLevel = trustLevelLabel(tags),
            friendNumber = friendNumber,
        )
    }

    private fun FriendLogSnapshot.toCurrentEntry(ownerId: String): FriendLogCurrentEntity {
        return FriendLogCurrentEntity(
            odUserId = compositeId,
            ownerUserId = ownerId,
            odDisplayName = displayName,
            trustLevel = trustLevel,
            friendNumber = friendNumber,
        )
    }

    private fun compositeId(ownerId: String, userId: String): String = "$ownerId:$userId"

    private fun trustLevelLabel(tags: List<String>): String {
        return when {
            tags.contains("system_trust_legend") || tags.contains("system_trust_veteran") -> "Trusted User"
            tags.contains("system_trust_trusted") -> "Known User"
            tags.contains("system_trust_known") -> "User"
            tags.contains("system_trust_basic") -> "New User"
            else -> "Visitor"
        }
    }

    private fun shouldWriteFeed(key: String): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        recentFeedWrites.compute(key) { _, lastWrite ->
            if (lastWrite != null && now - lastWrite < DEDUP_WINDOW_MS) {
                allowed = false
                lastWrite
            } else {
                allowed = true
                now
            }
        }
        if (allowed && recentFeedWrites.size > 500) {
            recentFeedWrites.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        return allowed
    }

    private fun writeFeedOnlineOffline(userId: String, displayName: String, type: String, location: String) {
        if (ownerUserId.isEmpty()) return
        if (!shouldWriteFeed("onoff:$userId:$type")) return
        scope.launch {
            // DB-level dedup guard: if the most recent row for this friend
            // already records the same online/offline transition we're about
            // to write, drop it. Catches VRChat's occasional repeats that
            // land outside the in-memory DEDUP_WINDOW_MS and survives app
            // restarts / re-logins (when recentFeedWrites gets cleared).
            val latest = feedRepository.getLatestOnlineOffline(ownerUserId, userId)
            if (latest != null && latest.type == type && latest.location == location) return@launch
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
        if (!shouldWriteFeed("gps:$userId:$location")) return
        scope.launch {
            val latest = feedRepository.getLatestGps(ownerUserId, userId)
            if (latest != null && latest.location == location && latest.worldName == worldName) return@launch
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

    private fun writeFeedStatus(userId: String, displayName: String, status: String, statusDescription: String, previousStatus: String, previousStatusDescription: String) {
        if (ownerUserId.isEmpty()) return
        if (!shouldWriteFeed("status:$userId:$status:$statusDescription")) return
        scope.launch {
            val latest = feedRepository.getLatestStatus(ownerUserId, userId)
            if (latest != null && latest.status == status && latest.statusDescription == statusDescription) return@launch
            feedRepository.insertStatus(
                FeedStatusEntity(
                    ownerUserId = ownerUserId,
                    userId = userId,
                    displayName = displayName,
                    status = status,
                    statusDescription = statusDescription,
                    previousStatus = previousStatus,
                    previousStatusDescription = previousStatusDescription,
                    createdAt = java.time.Instant.now().toString(),
                )
            )
        }
    }

    private fun writeFeedBio(userId: String, displayName: String, bio: String, previousBio: String) {
        if (ownerUserId.isEmpty()) return
        if (!shouldWriteFeed("bio:$userId:${bio.hashCode()}")) return
        scope.launch {
            val latest = feedRepository.getLatestBio(ownerUserId, userId)
            if (latest != null && latest.bio == bio) return@launch
            feedRepository.insertBio(
                FeedBioEntity(
                    ownerUserId = ownerUserId,
                    userId = userId,
                    displayName = displayName,
                    bio = bio,
                    previousBio = previousBio,
                    createdAt = java.time.Instant.now().toString(),
                )
            )
        }
    }

    private fun writeFeedAvatar(userId: String, displayName: String, imageUrl: String, thumbnailUrl: String, previousImageUrl: String, previousThumbnailUrl: String) {
        if (ownerUserId.isEmpty()) return
        if (!shouldWriteFeed("avatar:$userId:$thumbnailUrl")) return
        scope.launch {
            val latest = feedRepository.getLatestAvatar(ownerUserId, userId)
            if (latest != null &&
                latest.currentAvatarImageUrl == imageUrl &&
                latest.currentAvatarThumbnailImageUrl == thumbnailUrl
            ) return@launch
            feedRepository.insertAvatar(
                FeedAvatarEntity(
                    ownerUserId = ownerUserId,
                    userId = userId,
                    displayName = displayName,
                    currentAvatarImageUrl = imageUrl,
                    currentAvatarThumbnailImageUrl = thumbnailUrl,
                    previousCurrentAvatarImageUrl = previousImageUrl,
                    previousCurrentAvatarThumbnailImageUrl = previousThumbnailUrl,
                    createdAt = java.time.Instant.now().toString(),
                )
            )
        }
    }
}
