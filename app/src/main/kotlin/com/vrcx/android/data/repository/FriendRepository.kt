package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.disable
import com.vrcx.android.data.db.dao.enable
import com.vrcx.android.data.db.dao.isEnabled
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.model.worldIdOrNull
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepository @Inject internal constructor(
    private val friendApi: FriendApi,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val favoriteRepository: FavoriteRepository,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendLogSynchronizer: FriendLogSynchronizer,
    private val activityRecorder: FriendActivityRecorder,
    private val json: Json,
) {
    private data class ActiveFriendsLoad(
        val ownerId: String,
        val generation: Long,
        val deferred: Deferred<Unit>,
    )

    var ownerUserId: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountGeneration = AtomicLong(0)
    private val friendsRevision = AtomicLong(0)
    private val pendingOfflineJobs = ConcurrentHashMap<String, Job>()
    private val OFFLINE_DELAY_MS = 5000L
    private val _favoriteFriendIds = MutableStateFlow<Set<String>>(emptySet())
    private val _notifyEnabledIds = MutableStateFlow<Set<String>>(emptySet())

    private val friendsMutex = Mutex()
    private val friendsLoadMutex = Mutex()
    private var activeFriendsLoad: ActiveFriendsLoad? = null
    private val _friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
    val friends: StateFlow<Map<String, FriendContext>> = _friends.asStateFlow()

    // Domain transitions the foreground service maps to system notifications.
    // Emitting them here (where userId/displayName and the location/status
    // comparisons are already computed) means the service never re-parses the
    // raw pipeline payload.
    private val _friendTransitions = MutableSharedFlow<FriendTransition>(extraBufferCapacity = 64)
    val friendTransitions: SharedFlow<FriendTransition> = _friendTransitions.asSharedFlow()

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

    fun clearRuntimeState() {
        accountGeneration.incrementAndGet()
        friendsRevision.incrementAndGet()
        pendingOfflineJobs.values.forEach(Job::cancel)
        pendingOfflineJobs.clear()
        ownerUserId = ""
        activityRecorder.reset()
        _favoriteFriendIds.value = emptySet()
        _notifyEnabledIds.value = emptySet()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            friendsMutex.withLock { _friends.value = emptyMap() }
        }
    }

    suspend fun loadFriendsList() {
        val ownerId = ensureOwnerUserId()
        if (ownerId.isEmpty()) return
        val generation = accountGeneration.get()
        val deferred = friendsLoadMutex.withLock {
            activeFriendsLoad
                ?.takeIf { it.ownerId == ownerId && it.generation == generation && it.deferred.isActive }
                ?.deferred
                ?: scope.async { loadFriendsList(ownerId, generation) }.also {
                    activeFriendsLoad = ActiveFriendsLoad(ownerId, generation, it)
                }
        }
        try {
            deferred.await()
        } finally {
            // A cancelled waiter must not detach the still-running shared load;
            // later callers should continue to coalesce onto it.
            if (deferred.isCompleted) {
                withContext(NonCancellable) {
                    friendsLoadMutex.withLock {
                        if (activeFriendsLoad?.deferred === deferred) activeFriendsLoad = null
                    }
                }
            }
        }
    }

    private suspend fun loadFriendsList(ownerId: String, generation: Long) {
        val revision = friendsRevision.get()
        activityRecorder.reset()
        // Fetch online and offline friends concurrently — each sweep also
        // sleeps between pages, so serial fetches roughly double login latency.
        val (onlineFriends, offlineFriends) = coroutineScope {
            val online = async {
                BulkPaginator.fetchAll(pageSize = 100) { offset, count ->
                    friendApi.getFriends(n = count, offset = offset, offline = false)
                }
            }
            val offline = async {
                BulkPaginator.fetchAll(pageSize = 100) { offset, count ->
                    friendApi.getFriends(n = count, offset = offset, offline = true)
                }
            }
            online.await() to offline.await()
        }

        val friendMap = mutableMapOf<String, FriendContext>()
        for (user in onlineFriends) {
            friendMap[user.id] = FriendContext(
                id = user.id,
                name = user.displayName,
                state = if (user.location.isNullOrEmpty() || user.location == "offline") FriendState.ACTIVE else FriendState.ONLINE,
                ref = user,
            )
        }
        for (user in offlineFriends) {
            if (!friendMap.containsKey(user.id)) {
                friendMap[user.id] = FriendContext(
                    id = user.id,
                    name = user.displayName,
                    state = FriendState.OFFLINE,
                    ref = user,
                )
            }
        }
        friendsMutex.withLock {
            if (ownerId != ownerUserId || generation != accountGeneration.get() || revision != friendsRevision.get()) return
            userRepository.cacheUsers(friendMap.values.mapNotNull { it.ref })
            _friends.value = decorateFriendMap(friendMap)
            friendsRevision.incrementAndGet()
        }
        if (!isCurrentAccount(ownerId, generation)) return
        friendLogSynchronizer.synchronize(ownerId, friendMap)

        if (!isCurrentAccount(ownerId, generation)) return
        try {
            favoriteRepository.loadFavorites(type = "friend")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        if (!isCurrentAccount(ownerId, generation)) return
        try {
            val enabledIds = friendNotifyDao.getEnabledFriendIdsSnapshot(ownerId).toSet()
            friendsMutex.withLock {
                if (!isCurrentAccount(ownerId, generation)) return
                _notifyEnabledIds.value = enabledIds
                _friends.value = decorateFriendMap(_friends.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun toggleFriendNotify(friendUserId: String): Boolean {
        val ownerId = ownerUserId
        if (ownerId.isEmpty()) return false
        val newEnabled = !friendNotifyDao.isEnabled(ownerId, friendUserId)
        if (newEnabled) {
            friendNotifyDao.enable(ownerId, friendUserId)
        } else {
            friendNotifyDao.disable(ownerId, friendUserId)
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
        cancelPendingOffline(userId)
        val user = tryDecodeUser(content["user"])
        val displayName = user?.displayName ?: _friends.value[userId]?.name ?: userId
        val location = content["location"]?.jsonPrimitive?.content ?: ""
        val travelingToLocation = content["travelingToLocation"]?.jsonPrimitive?.content
        val platform = content["platform"]?.jsonPrimitive?.content
        val instanceId = parseInstanceId(location)
        val travelingToWorld = worldIdOrNull(travelingToLocation)
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
        activityRecorder.recordOnlineOffline(ownerUserId, userId, displayName, "online", location)
        _friendTransitions.tryEmit(FriendTransition.CameOnline(userId, displayName))
    }

    private suspend fun handleFriendOffline(event: PipelineEvent.FriendOffline) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val payloadUser = tryDecodeUser(content["user"])
        if (payloadUser != null) userRepository.cacheUser(payloadUser)
        val displayName = payloadUser?.displayName ?: _friends.value[userId]?.name ?: userId
        val generation = accountGeneration.get()
        val ownerId = ownerUserId
        // 5s delay before marking offline
        updateFriend(userId) { ctx ->
            ctx.copy(
                pendingOffline = true,
                ref = (payloadUser ?: ctx.ref),
                name = payloadUser?.displayName ?: ctx.name,
            )
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(OFFLINE_DELAY_MS)
            if (generation != accountGeneration.get() || ownerId != ownerUserId) return@launch
            val previous = updateFriendIfCurrent(userId, generation) { ctx ->
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
            if (previous?.pendingOffline == true) {
                // Confirmed offline: mark a filtered hop so a later return to
                // the same world isn't mistaken for a re-emit. Stamp here
                // rather than at event arrival so transient flickers that
                // get rescinded during OFFLINE_DELAY_MS don't produce phantom
                // hops.
                activityRecorder.markFilteredTransition(userId)
                activityRecorder.recordOnlineOffline(ownerId, userId, displayName, "offline", "")
                _friendTransitions.emit(FriendTransition.CameOffline(userId, displayName))
            }
            pendingOfflineJobs.remove(userId)
        }
        pendingOfflineJobs.put(userId, job)?.cancel()
        job.start()
    }

    private suspend fun handleFriendActive(event: PipelineEvent.FriendActive) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
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
        // FriendActive = friend present but not in a world (VRChat web,
        // menu, Quest social, etc.). Record as a filtered hop so a later
        // return to a previously-visited world is treated as a real
        // revisit rather than a pipeline re-emit.
        activityRecorder.markFilteredTransition(userId)
    }

    private suspend fun handleFriendUpdate(event: PipelineEvent.FriendUpdate) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"]) ?: return

        val previous = updateFriend(userId) { it.copy(ref = user, name = user.displayName) }
        userRepository.cacheUser(user)
        ensureOwnerUserId().takeIf { it.isNotEmpty() }?.let { ownerId ->
            friendLogSynchronizer.synchronizeUpdatedFriend(
                ownerId = ownerId,
                previous = previous,
                userId = userId,
                displayName = user.displayName,
                tags = user.tags,
            )
        }

        val prevRef = previous.ref ?: return
        // Notify on any online-status change (join-me / ask-me / busy). This is a
        // looser rule than the feed-status write below, matching the service's
        // previous status-change notification behavior.
        if (user.status != prevRef.status) {
            _friendTransitions.tryEmit(FriendTransition.ChangedStatus(userId, user.displayName, user.status))
        }
        // Status change (skip offline transitions — handled by online/offline events)
        if ((user.status != prevRef.status || user.statusDescription != prevRef.statusDescription)
            && user.status != "offline" && prevRef.status != "offline") {
            activityRecorder.recordStatus(
                ownerId = ownerUserId,
                userId = userId,
                displayName = user.displayName,
                status = user.status,
                statusDescription = user.statusDescription,
                previousStatus = prevRef.status,
                previousStatusDescription = prevRef.statusDescription,
            )
        }
        // Bio change (skip if either is empty — initial load artifact)
        if (user.bio != prevRef.bio && user.bio.isNotEmpty() && prevRef.bio.isNotEmpty()) {
            activityRecorder.recordBio(ownerUserId, userId, user.displayName, user.bio, prevRef.bio)
        }
        // Avatar change
        if (user.currentAvatarThumbnailImageUrl != prevRef.currentAvatarThumbnailImageUrl
            && user.currentAvatarThumbnailImageUrl.isNotEmpty()) {
            activityRecorder.recordAvatar(
                ownerId = ownerUserId,
                userId = userId,
                displayName = user.displayName,
                imageUrl = user.currentAvatarImageUrl,
                thumbnailUrl = user.currentAvatarThumbnailImageUrl,
                previousImageUrl = prevRef.currentAvatarImageUrl,
                previousThumbnailUrl = prevRef.currentAvatarThumbnailImageUrl,
            )
        }
    }

    private suspend fun handleFriendLocation(event: PipelineEvent.FriendLocation) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        val location = content["location"]?.jsonPrimitive?.content
        val user = tryDecodeUser(content["user"])
        val worldName = content["world"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: content["worldName"]?.jsonPrimitive?.content
            ?: ""
        val travelingToLocation = content["travelingToLocation"]?.jsonPrimitive?.content
        val instanceId = parseInstanceId(location)
        val travelingToWorld = worldIdOrNull(travelingToLocation)
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

        val isFilteredDestination = location.isNullOrEmpty() ||
            location == "offline" || location == "private"
        if (isFilteredDestination && location != previousLocation) {
            // Remember that this friend briefly passed through an
            // un-persisted state; the activity recorder uses this to tell an honest
            // revisit apart from a pipeline re-emit.
            activityRecorder.markFilteredTransition(userId)
        }

        // Only write GPS feed for actual world locations, not "private" or "offline"
        if (!isFilteredDestination && location != previousLocation) {
            activityRecorder.recordGps(ownerUserId, userId, displayName, location!!, worldName, previousLocation)
            _friendTransitions.tryEmit(FriendTransition.ChangedLocation(userId, displayName, worldName))
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
        ensureOwnerUserId().takeIf { it.isNotEmpty() }?.let { ownerId ->
            friendLogSynchronizer.recordAdded(ownerId, userId, user)
        }
    }

    private suspend fun handleFriendDelete(event: PipelineEvent.FriendDelete) {
        val content = event.content?.jsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        ensureOwnerUserId().takeIf { it.isNotEmpty() }?.let { ownerId ->
            friendLogSynchronizer.recordRemoved(ownerId, userId)
        }
        friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            current.remove(userId)
            _friends.value = current
            friendsRevision.incrementAndGet()
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
            friendsRevision.incrementAndGet()
            existing
        }
    }

    private suspend fun updateFriendIfCurrent(
        userId: String,
        generation: Long,
        update: (FriendContext) -> FriendContext,
    ): FriendContext? = friendsMutex.withLock {
        if (generation != accountGeneration.get()) return@withLock null
        val current = _friends.value.toMutableMap()
        val existing = current[userId] ?: FriendContext(id = userId, name = userId, state = FriendState.OFFLINE)
        current[userId] = decorateFriendContext(update(existing))
        _friends.value = current
        friendsRevision.incrementAndGet()
        existing
    }

    private fun cancelPendingOffline(userId: String) {
        pendingOfflineJobs.remove(userId)?.cancel()
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
        val currentOwnerId = authRepository.currentUser?.id.orEmpty()
        if (currentOwnerId.isNotEmpty() && currentOwnerId != ownerUserId) {
            ownerUserId = currentOwnerId
        }
        return ownerUserId
    }

    private fun isCurrentAccount(ownerId: String, generation: Long): Boolean =
        ownerId == ownerUserId && generation == accountGeneration.get()
}
