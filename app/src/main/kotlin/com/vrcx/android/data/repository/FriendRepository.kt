package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.disable
import com.vrcx.android.data.db.dao.enable
import com.vrcx.android.data.db.dao.isEnabled
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.model.friendStateOf
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.worldIdOrNull
import com.vrcx.android.data.util.runIgnoringFailure
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepository @Inject internal constructor(
    private val friendApi: FriendApi,
    private val userRepository: UserRepository,
    private val favoriteRepository: FavoriteRepository,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendLogSynchronizer: FriendLogSynchronizer,
    private val activityRecorder: FriendActivityRecorder,
    private val json: Json,
    accountScope: AccountScope,
) : AccountScoped {
    private data class ActiveFriendsLoad(
        val token: AccountScope.Token,
        val deferred: Deferred<Unit>,
    )

    private val account = accountScope.bindTo(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val friendsRevision = AtomicLong(0)
    // Revision each friend was last mutated at, so a full-list load can tell
    // which entries a pipeline frame overtook while the sweeps were running.
    private val friendRevisions = ConcurrentHashMap<String, Long>()
    private val pendingOfflineJobs = ConcurrentHashMap<String, Job>()
    /**
     * Friends whose offline frame is still inside its confirmation window. Kept
     * here rather than on [FriendContext]: nothing outside this class reads it,
     * and as a per-friend flag on a shared type it could disagree with the jobs
     * above without anything noticing.
     */
    internal val pendingOfflineIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Confirmation window before a friend-offline frame is committed; overridable in tests. */
    internal var offlineDelayMs = 5000L

    private val _favoriteFriendIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the friends the account has favourited, for the screens that badge them. */
    val favoriteFriendIds: StateFlow<Set<String>> = _favoriteFriendIds.asStateFlow()

    private val _notifyEnabledIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the friends whose presence notifications are switched on. */
    val notifyEnabledIds: StateFlow<Set<String>> = _notifyEnabledIds.asStateFlow()

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
            }
        }
    }

    /**
     * Runs under the scope lock, which is also the lock every publish here takes
     * — so a writer racing the account change either published before this and
     * is cleared by it, or sees the new generation and is skipped.
     */
    override fun clearRuntimeState() {
        friendsRevision.incrementAndGet()
        pendingOfflineJobs.values.forEach(Job::cancel)
        pendingOfflineJobs.clear()
        friendRevisions.clear()
        pendingOfflineIds.clear()
        activityRecorder.reset()
        _favoriteFriendIds.value = emptySet()
        _notifyEnabledIds.value = emptySet()
        _friends.value = emptyMap()
    }

    suspend fun loadFriendsList() {
        val token = account.current()
        if (token.ownerUserId.isEmpty()) return
        val deferred = friendsLoadMutex.withLock {
            activeFriendsLoad
                ?.takeIf { it.token == token && it.deferred.isActive }
                ?.deferred
                ?: scope.async { loadFriendsList(token) }.also {
                    activeFriendsLoad = ActiveFriendsLoad(token, it)
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

    private suspend fun loadFriendsList(token: AccountScope.Token) {
        val ownerId = token.ownerUserId
        val revision = friendsRevision.get()
        activityRecorder.reset()
        // Fetch online and offline friends concurrently — each sweep also
        // sleeps between pages, so serial fetches roughly double login latency.
        // stopOnShortPage is off: the friends endpoints hand back partial pages
        // while more data remains, and a snapshot that ends early looks exactly
        // like a mass unfriend to the friend-log reconciliation below.
        val (onlineFriends, offlineFriends) = coroutineScope {
            val online = async {
                BulkPaginator.fetchAll(pageSize = 100, stopOnShortPage = false) { offset, count ->
                    friendApi.getFriends(n = count, offset = offset, offline = false)
                }
            }
            val offline = async {
                BulkPaginator.fetchAll(pageSize = 100, stopOnShortPage = false) { offset, count ->
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
        val committed = friendsMutex.withLock {
            val merged = mergeWithLiveFriends(friendMap, revision)
            val published = account.publishIfCurrent(token) {
                _friends.value = merged
                friendsRevision.incrementAndGet()
            }
            if (!published) return
            merged
        }
        userRepository.cacheUsers(committed.values.mapNotNull { it.ref })
        friendLogSynchronizer.synchronize(ownerId, committed)

        if (!account.isCurrent(token)) return
        runIgnoringFailure { favoriteRepository.loadFavorites(type = "friend") }

        if (!account.isCurrent(token)) return
        runIgnoringFailure {
            val enabledIds = friendNotifyDao.getEnabledFriendIdsSnapshot(ownerId).toSet()
            account.publishIfCurrent(token) { _notifyEnabledIds.value = enabledIds }
        }
    }

    /**
     * Reconciles a freshly fetched friend list against the entries pipeline
     * frames touched while the sweeps were in flight. A frame is always newer
     * than the fetch that started before it, so it wins for that friend — but
     * only for that friend, and the rest of the fetched list still lands.
     */
    private fun mergeWithLiveFriends(
        fetched: Map<String, FriendContext>,
        revision: Long,
    ): Map<String, FriendContext> {
        val live = _friends.value
        val merged = LinkedHashMap<String, FriendContext>(fetched.size)
        for ((userId, context) in fetched) {
            if (changedSince(userId, revision)) {
                // Absent from the live map means a friend-delete frame removed
                // them mid-sweep; don't resurrect them from a stale snapshot.
                live[userId]?.let { merged[userId] = it }
            } else {
                merged[userId] = context
            }
        }
        for ((userId, context) in live) {
            if (userId !in fetched && changedSince(userId, revision)) merged[userId] = context
        }
        return merged
    }

    private fun changedSince(userId: String, revision: Long): Boolean =
        (friendRevisions[userId] ?: 0L) > revision

    suspend fun toggleFriendNotify(friendUserId: String): Boolean {
        val ownerId = account.ownerUserId
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
        return newEnabled
    }

    fun observeNotifyEnabledIds(ownerUserId: String): Flow<Set<String>> {
        return friendNotifyDao.getEnabledFriendIds(ownerUserId).map { it.toSet() }
    }

    /** The friend-log rows this repository's synchronizer writes, newest first. */
    fun friendLogHistory(ownerUserId: String, limit: Int): Flow<List<FriendLogHistoryEntity>> =
        friendLogSynchronizer.history(ownerUserId, limit)

    suspend fun handleEvent(event: PipelineEvent) {
        // Resolve the account once per frame and thread it through, so no
        // handler has to decide for itself which account a frame belongs to.
        val ownerId = account.ownerUserId
        when (event) {
            is PipelineEvent.FriendOnline -> handleFriendOnline(ownerId, event)
            is PipelineEvent.FriendOffline -> handleFriendOffline(ownerId, event)
            is PipelineEvent.FriendActive -> handleFriendActive(event)
            is PipelineEvent.FriendUpdate -> handleFriendUpdate(ownerId, event)
            is PipelineEvent.FriendLocation -> handleFriendLocation(ownerId, event)
            is PipelineEvent.FriendAdd -> handleFriendAdd(ownerId, event)
            is PipelineEvent.FriendDelete -> handleFriendDelete(ownerId, event)
            else -> {}
        }
    }

    private suspend fun handleFriendOnline(ownerId: String, event: PipelineEvent.FriendOnline) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        val user = tryDecodeUser(content["user"])
        val displayName = user?.displayName ?: _friends.value[userId]?.name ?: userId
        val location = content.string("location") ?: ""
        val travelingToLocation = content.string("travelingToLocation")
        val platform = content.string("platform")
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
            )
        }
        if (user != null) userRepository.cacheUser(user)
        activityRecorder.recordOnlineOffline(ownerId, userId, displayName, "online", location)
        _friendTransitions.tryEmit(FriendTransition.CameOnline(userId, displayName))
    }

    private suspend fun handleFriendOffline(ownerId: String, event: PipelineEvent.FriendOffline) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val payloadUser = tryDecodeUser(content["user"])
        if (payloadUser != null) userRepository.cacheUser(payloadUser)
        val displayName = payloadUser?.displayName ?: _friends.value[userId]?.name ?: userId
        val token = account.current()
        // Confirmation delay before marking offline
        pendingOfflineIds.add(userId)
        updateFriend(userId) { ctx ->
            ctx.copy(
                ref = (payloadUser ?: ctx.ref),
                name = payloadUser?.displayName ?: ctx.name,
            )
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(offlineDelayMs)
            if (!account.isCurrent(token)) return@launch
            // Test-and-clear under the friend map lock: an online / active /
            // location frame that arrived during the window drops the id first
            // and then republishes its own state, so whichever order the two
            // land in, the surviving entry is the later frame's.
            var confirmed = false
            updateFriend(userId, token) { ctx ->
                confirmed = pendingOfflineIds.remove(userId)
                if (!confirmed) return@updateFriend ctx
                ctx.copy(
                    state = FriendState.OFFLINE,
                    ref = ctx.ref?.copy(
                        location = "offline",
                        travelingToLocation = "offline",
                        travelingToWorld = "offline",
                        travelingToInstance = "offline",
                        instanceId = "offline",
                    ),
                )
            }
            if (confirmed) {
                // Confirmed offline: mark a filtered hop so a later return to
                // the same world isn't mistaken for a re-emit. Stamp here
                // rather than at event arrival so transient flickers that
                // get rescinded during the confirmation delay don't produce phantom
                // hops.
                activityRecorder.markFilteredTransition(userId)
                activityRecorder.recordOnlineOffline(ownerId, userId, displayName, "offline", "")
                _friendTransitions.emit(FriendTransition.CameOffline(userId, displayName))
            }
            // Two-arg remove: a second friend-offline frame may already have
            // registered its own job for this user, and that one is still live.
            pendingOfflineJobs.remove(userId, coroutineContext[Job])
        }
        pendingOfflineJobs.put(userId, job)?.cancel()
        job.start()
    }

    // No ownerId parameter: friend-active writes no owner-scoped row, and the
    // recorder's filtered-transition marker is keyed by friend alone.
    private suspend fun handleFriendActive(event: PipelineEvent.FriendActive) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        val user = tryDecodeUser(content["user"])
        val platform = content.string("platform")
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
            )
        }
        if (user != null) userRepository.cacheUser(user)
        // FriendActive = friend present but not in a world (VRChat web,
        // menu, Quest social, etc.). Record as a filtered hop so a later
        // return to a previously-visited world is treated as a real
        // revisit rather than a pipeline re-emit.
        activityRecorder.markFilteredTransition(userId)
    }

    private suspend fun handleFriendUpdate(ownerId: String, event: PipelineEvent.FriendUpdate) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"]) ?: return

        // friend-update carries profile fields (bio, status, avatar); its user
        // object does not describe where the friend is, and routinely reports
        // "offline" for location. Presence stays with whatever the online /
        // active / location handlers last computed, so this event can't drop a
        // friend out of their world group.
        val previous = updateFriend(userId) { ctx ->
            ctx.copy(ref = ctx.ref?.let { user.withPresenceOf(it) } ?: user, name = user.displayName)
        } ?: return
        userRepository.cacheUser(user)
        if (ownerId.isNotEmpty()) {
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
                ownerId = ownerId,
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
            activityRecorder.recordBio(ownerId, userId, user.displayName, user.bio, prevRef.bio)
        }
        // Avatar change
        if (user.currentAvatarThumbnailImageUrl != prevRef.currentAvatarThumbnailImageUrl
            && user.currentAvatarThumbnailImageUrl.isNotEmpty()) {
            activityRecorder.recordAvatar(
                ownerId = ownerId,
                userId = userId,
                displayName = user.displayName,
                imageUrl = user.currentAvatarImageUrl,
                thumbnailUrl = user.currentAvatarThumbnailImageUrl,
                previousImageUrl = prevRef.currentAvatarImageUrl,
                previousThumbnailUrl = prevRef.currentAvatarThumbnailImageUrl,
            )
        }
    }

    private suspend fun handleFriendLocation(ownerId: String, event: PipelineEvent.FriendLocation) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        val location = content.string("location")
        val user = tryDecodeUser(content["user"])
        val worldName = content.obj("world")?.string("name")
            ?: content.string("worldName")
            ?: ""
        val travelingToLocation = content.string("travelingToLocation")
        val instanceId = parseInstanceId(location)
        val travelingToWorld = worldIdOrNull(travelingToLocation)
        val travelingToInstance = parseInstanceId(travelingToLocation)

        val previous = updateFriend(userId) { ctx ->
            ctx.copy(
                state = friendStateOf(location),
                ref = (user ?: ctx.ref)?.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    instanceId = instanceId,
                    state = "online",
                ),
                name = user?.displayName ?: ctx.name,
            )
        } ?: return
        if (user != null) userRepository.cacheUser(user)

        val previousLocation = previous.ref?.location ?: ""
        val displayName = user?.displayName ?: previous.name

        // "traveling" is a transit state, not a destination: a feed row and a
        // notification for it would be followed by a second pair when the
        // friend actually lands, and the locations screen filters it out.
        val isFilteredDestination = !isTrackableLocation(location.orEmpty())
        if (isFilteredDestination && location != previousLocation) {
            // Remember that this friend briefly passed through an
            // un-persisted state; the activity recorder uses this to tell an honest
            // revisit apart from a pipeline re-emit.
            activityRecorder.markFilteredTransition(userId)
        }

        // Only write GPS feed for actual world locations
        if (!isFilteredDestination && location != previousLocation) {
            activityRecorder.recordGps(ownerId, userId, displayName, location!!, worldName, previousLocation)
            _friendTransitions.tryEmit(FriendTransition.ChangedLocation(userId, displayName, worldName))
        }
    }

    private suspend fun handleFriendAdd(ownerId: String, event: PipelineEvent.FriendAdd) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = tryDecodeUser(content["user"])
        updateFriend(userId) {
            FriendContext(
                id = userId,
                name = user?.displayName ?: userId,
                state = FriendState.OFFLINE,
                ref = user,
            )
        }
        if (ownerId.isNotEmpty()) friendLogSynchronizer.recordAdded(ownerId, userId, user)
    }

    private suspend fun handleFriendDelete(ownerId: String, event: PipelineEvent.FriendDelete) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        cancelPendingOffline(userId)
        if (ownerId.isNotEmpty()) friendLogSynchronizer.recordRemoved(ownerId, userId)
        val token = account.current()
        friendsMutex.withLock {
            val current = _friends.value.toMutableMap()
            current.remove(userId)
            account.publishIfCurrent(token) {
                _friends.value = current
                friendRevisions[userId] = friendsRevision.incrementAndGet()
            }
        }
    }

    /**
     * Applies [update] to a friend under the map lock and returns the entry as
     * it was before, or null if the account moved on. [token] defaults to the
     * account at call time, so an update that was waiting on the lock when the
     * account changed is dropped instead of republishing the outgoing map.
     */
    private suspend fun updateFriend(
        userId: String,
        token: AccountScope.Token = account.current(),
        update: (FriendContext) -> FriendContext,
    ): FriendContext? = friendsMutex.withLock {
        val current = _friends.value.toMutableMap()
        val existing = current[userId]
            ?: FriendContext(id = userId, name = userId, state = FriendState.OFFLINE)
        current[userId] = update(existing)
        val published = account.publishIfCurrent(token) {
            _friends.value = current
            friendRevisions[userId] = friendsRevision.incrementAndGet()
        }
        if (published) existing else null
    }

    private fun cancelPendingOffline(userId: String) {
        pendingOfflineIds.remove(userId)
        pendingOfflineJobs.remove(userId)?.cancel()
    }

    private fun tryDecodeUser(element: JsonElement?): VrcUser? {
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
    internal fun resolveFriendUserId(content: JsonObject): String? {
        return content.string("userId") ?: content.string("userid")
    }

    /**
     * VRChat sends several of these fields as whichever shape it feels like —
     * `[]` for an absent string, a bare string where an object is documented,
     * an explicit null. Reading them through cast-or-null accessors degrades a
     * shape change to a missing field instead of throwing out of the handler.
     */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** Carries the presence [other] already resolved onto a fresh profile payload. */
    private fun VrcUser.withPresenceOf(other: VrcUser): VrcUser = copy(
        location = other.location,
        travelingToLocation = other.travelingToLocation,
        travelingToWorld = other.travelingToWorld,
        travelingToInstance = other.travelingToInstance,
        instanceId = other.instanceId,
        platform = other.platform,
        state = other.state,
    )

    /** Extracts instanceId from a VRChat location string (format: "worldId:instanceId"). */
    private fun parseInstanceId(location: String?): String? {
        if (location.isNullOrEmpty() || location == "offline" || location == "private") return null
        val colonIndex = location.indexOf(':')
        return if (colonIndex >= 0) location.substring(colonIndex + 1) else null
    }

}
