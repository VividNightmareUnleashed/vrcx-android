package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.model.friendStateOf
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.worldIdOrNull
import com.vrcx.android.data.websocket.PipelineEvent
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Applies realtime friend frames while the repository's captured account token remains current. */
internal class FriendEventProcessor @Inject constructor(
    private val userRepository: UserRepository,
    private val friendLogSynchronizer: FriendLogSynchronizer,
    private val activityRecorder: FriendActivityRecorder,
    private val json: Json,
) {
    suspend fun handle(state: FriendRuntimeState, event: PipelineEvent, token: AccountScope.Token) {
        when (event) {
            is PipelineEvent.FriendOnline -> handleOnline(state, token, event)
            is PipelineEvent.FriendOffline -> handleOffline(state, token, event)
            is PipelineEvent.FriendActive -> handleActive(state, token, event)
            is PipelineEvent.FriendUpdate -> handleUpdate(state, token, event)
            is PipelineEvent.FriendLocation -> handleLocation(state, token, event)
            is PipelineEvent.FriendAdd -> handleAdd(state, token, event)
            is PipelineEvent.FriendDelete -> handleDelete(state, token, event)
            else -> Unit
        }
    }

    private suspend fun handleOnline(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendOnline,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val account = state.account
        if (!account.publishIfCurrent(token) { state.cancelPendingOffline(userId) }) return
        val user = json.decodeUser(content["user"])
        val displayName = user?.displayName ?: state.friends.value[userId]?.name ?: userId
        val location = content.string("location").orEmpty()
        val travelingToLocation = content.string("travelingToLocation")
        val platform = content.string("platform")
        val instanceId = parseInstanceId(location)
        val travelingToWorld = worldIdOrNull(travelingToLocation)
        val travelingToInstance = parseInstanceId(travelingToLocation)
        val updated = state.updateFriend(userId, token) { context ->
            context.copy(
                state = FriendState.ONLINE,
                ref = (user ?: context.ref)?.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    instanceId = instanceId,
                    platform = platform,
                    state = "online",
                ),
                name = user?.displayName ?: context.name,
            )
        } != null
        val current = updated && (user == null || account.publishIfCurrent(token) { userRepository.cacheUser(user) })
        if (current) {
            activityRecorder.recordOnlineOffline(token, userId, displayName, "online", location)
            state.emitTransition(token, FriendTransition.CameOnline(userId, displayName))
        }
    }

    private suspend fun handleOffline(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendOffline,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val account = state.account
        val payloadUser = json.decodeUser(content["user"])
        val displayName = payloadUser?.displayName ?: state.friends.value[userId]?.name ?: userId
        state.updateFriend(
            userId = userId,
            token = token,
            onPublish = { state.pendingOfflineIds.add(userId) },
        ) { context ->
            context.copy(
                ref = payloadUser ?: context.ref,
                name = payloadUser?.displayName ?: context.name,
            )
        } ?: return
        if (payloadUser != null && !account.publishIfCurrent(token) { userRepository.cacheUser(payloadUser) }) return
        state.scheduleOfflineConfirmation(token, userId) {
            // Stamp only a confirmed transition so a rescinded flicker cannot create a phantom hop.
            activityRecorder.markFilteredTransition(token, userId)
            activityRecorder.recordOnlineOffline(token, userId, displayName, "offline", "")
            state.emitTransition(token, FriendTransition.CameOffline(userId, displayName))
        }
    }

    private suspend fun handleActive(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendActive,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val account = state.account
        if (!account.publishIfCurrent(token) { state.cancelPendingOffline(userId) }) return
        val user = json.decodeUser(content["user"])
        val platform = content.string("platform")
        val updated = state.updateFriend(userId, token) { context ->
            context.copy(
                state = FriendState.ACTIVE,
                ref = (user ?: context.ref)?.copy(
                    location = "offline",
                    travelingToLocation = "offline",
                    travelingToWorld = "offline",
                    travelingToInstance = "offline",
                    instanceId = "offline",
                    platform = platform,
                ),
                name = user?.displayName ?: context.name,
            )
        } != null
        val current = updated && (user == null || account.publishIfCurrent(token) { userRepository.cacheUser(user) })
        if (current) {
            // Active is a filtered presence hop, so a later return to the same world is a real revisit.
            activityRecorder.markFilteredTransition(token, userId)
        }
    }

    private suspend fun handleUpdate(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendUpdate,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = json.decodeUser(content["user"]) ?: return
        val account = state.account

        // Profile-update payloads do not own presence; retain the last presence frame's fields.
        val previous = state.updateFriend(userId, token) { context ->
            context.copy(ref = context.ref?.let { user.withPresenceOf(it) } ?: user, name = user.displayName)
        } ?: return
        if (!account.publishIfCurrent(token) { userRepository.cacheUser(user) }) return
        account.ensureCurrent(token)
        friendLogSynchronizer.synchronizeUpdatedFriend(
            ownerId = token.ownerUserId,
            previous = previous,
            userId = userId,
            displayName = user.displayName,
            tags = user.tags,
        )
        account.ensureCurrent(token)

        previous.ref?.let { previousUser ->
            recordProfileChanges(token, FriendProfileChange(userId, user, previousUser), state)
        }
    }

    private suspend fun recordProfileChanges(
        token: AccountScope.Token,
        change: FriendProfileChange,
        state: FriendRuntimeState,
    ) {
        val current = change.current
        val previous = change.previous
        if (current.status != previous.status) {
            state.emitTransition(
                token,
                FriendTransition.ChangedStatus(change.userId, current.displayName, current.status),
            )
        }
        if (change.hasRecordableStatusChange()) activityRecorder.recordStatus(token, change)
        if (current.bio != previous.bio && current.bio.isNotEmpty() && previous.bio.isNotEmpty()) {
            activityRecorder.recordBio(token, change)
        }
        if (current.currentAvatarThumbnailImageUrl != previous.currentAvatarThumbnailImageUrl &&
            current.currentAvatarThumbnailImageUrl.isNotEmpty()
        ) {
            activityRecorder.recordAvatar(token, change)
        }
    }

    private suspend fun handleLocation(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendLocation,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val account = state.account
        if (!account.publishIfCurrent(token) { state.cancelPendingOffline(userId) }) return
        val location = content.string("location")
        val user = json.decodeUser(content["user"])
        val worldName = content.obj("world")?.string("name")
            ?: content.string("worldName").orEmpty()
        val travelingToLocation = content.string("travelingToLocation")
        val instanceId = parseInstanceId(location)
        val travelingToWorld = worldIdOrNull(travelingToLocation)
        val travelingToInstance = parseInstanceId(travelingToLocation)
        val previous = state.updateFriend(userId, token) { context ->
            context.copy(
                state = friendStateOf(location),
                ref = (user ?: context.ref)?.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    instanceId = instanceId,
                    state = "online",
                ),
                name = user?.displayName ?: context.name,
            )
        }
        if (previous != null) {
            val current = user == null || account.publishIfCurrent(token) { userRepository.cacheUser(user) }
            if (current) {
                val previousLocation = previous.ref?.location.orEmpty()
                val displayName = user?.displayName ?: previous.name
                recordLocationChange(
                    state,
                    FriendLocationChange(token, userId, displayName, worldName, location, previousLocation),
                )
            }
        }
    }

    private suspend fun recordLocationChange(state: FriendRuntimeState, change: FriendLocationChange) {
        val destination = change.location?.takeIf(::isTrackableLocation)
        if (destination == null) {
            if (change.location != change.previousLocation) {
                activityRecorder.markFilteredTransition(change.token, change.userId)
            }
        } else if (destination != change.previousLocation) {
            val transition = FriendTransition.ChangedLocation(change.userId, change.displayName, change.worldName)
            activityRecorder.recordGps(change.token, transition, destination, change.previousLocation)
            state.emitTransition(change.token, transition)
        }
    }

    private suspend fun handleAdd(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendAdd,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val user = json.decodeUser(content["user"])
        state.updateFriend(userId, token) {
            FriendContext(
                id = userId,
                name = user?.displayName ?: userId,
                state = FriendState.OFFLINE,
                ref = user,
            )
        } ?: return
        state.account.ensureCurrent(token)
        friendLogSynchronizer.recordAdded(token.ownerUserId, userId, user)
        state.account.ensureCurrent(token)
    }

    private suspend fun handleDelete(
        state: FriendRuntimeState,
        token: AccountScope.Token,
        event: PipelineEvent.FriendDelete,
    ) {
        val content = event.content as? JsonObject ?: return
        val userId = resolveFriendUserId(content) ?: return
        val account = state.account
        if (!account.publishIfCurrent(token) { state.cancelPendingOffline(userId) }) return
        account.ensureCurrent(token)
        friendLogSynchronizer.recordRemoved(token.ownerUserId, userId)
        account.ensureCurrent(token)
        state.removeFriend(userId, token)
    }
}

private data class FriendLocationChange(
    val token: AccountScope.Token,
    val userId: String,
    val displayName: String,
    val worldName: String,
    val location: String?,
    val previousLocation: String,
)

/** Accepts both user-id spellings observed in VRChat pipeline payloads. */
internal fun resolveFriendUserId(content: JsonObject): String? = content.string("userId") ?: content.string("userid")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun Json.decodeUser(element: JsonElement?): VrcUser? = try {
    element?.let { decodeFromJsonElement(VrcUser.serializer(), it) }
} catch (_: Exception) {
    null
}

private fun FriendProfileChange.hasRecordableStatusChange(): Boolean = current.status != "offline" &&
    previous.status != "offline" &&
    (current.status != previous.status || current.statusDescription != previous.statusDescription)

private fun VrcUser.withPresenceOf(other: VrcUser): VrcUser = copy(
    location = other.location,
    travelingToLocation = other.travelingToLocation,
    travelingToWorld = other.travelingToWorld,
    travelingToInstance = other.travelingToInstance,
    instanceId = other.instanceId,
    platform = other.platform,
    state = other.state,
)

private fun parseInstanceId(location: String?): String? {
    if (location.isNullOrEmpty() || location == "offline" || location == "private") return null
    val colonIndex = location.indexOf(':')
    return if (colonIndex >= 0) location.substring(colonIndex + 1) else null
}
