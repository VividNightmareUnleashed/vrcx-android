package com.vrcx.android.data.repository

import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.util.parseInstantMillisOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class FriendActivityRecorder @Inject constructor(
    private val feedRepository: FeedRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentWrites = ConcurrentHashMap<String, Long>()

    /**
     * A filtered offline/private hop after the latest GPS row proves that a
     * return to the same location is a revisit rather than a pipeline re-emit.
     */
    private val lastFilteredTransitionAt = ConcurrentHashMap<String, Long>()

    fun reset() {
        recentWrites.clear()
        lastFilteredTransitionAt.clear()
    }

    fun markFilteredTransition(userId: String) {
        lastFilteredTransitionAt[userId] = System.currentTimeMillis()
    }

    fun recordOnlineOffline(
        ownerId: String,
        userId: String,
        displayName: String,
        type: String,
        location: String,
    ) {
        if (ownerId.isEmpty() || !shouldWrite("onoff:$userId:$type")) return
        scope.launch {
            val latest = feedRepository.getLatestOnlineOffline(ownerId, userId)
            if (latest != null && latest.type == type && latest.location == location) return@launch
            feedRepository.insertOnlineOffline(
                FeedOnlineOfflineEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    type = type,
                    location = location,
                    worldName = "",
                    time = "",
                    groupName = "",
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordGps(
        ownerId: String,
        userId: String,
        displayName: String,
        location: String,
        worldName: String,
        previousLocation: String,
    ) {
        if (ownerId.isEmpty() || !shouldWrite("gps:$userId:$location")) return
        scope.launch {
            val latest = feedRepository.getLatestGps(ownerId, userId)
            if (latest != null &&
                latest.location == location &&
                latest.worldName == worldName &&
                latest.previousLocation == previousLocation
            ) {
                val latestMillis = parseInstantMillisOrNull(latest.createdAt)
                if (latestMillis != null) {
                    val withinWindow = System.currentTimeMillis() - latestMillis < GPS_REVISIT_WINDOW.toMillis()
                    val hopAfterLatest = (lastFilteredTransitionAt[userId] ?: 0L) > latestMillis
                    if (withinWindow && !hopAfterLatest) return@launch
                }
                // Prefer a possible duplicate over dropping a real revisit when
                // an old row has an unparseable timestamp.
            }
            feedRepository.insertGps(
                FeedGpsEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    location = location,
                    worldName = worldName,
                    previousLocation = previousLocation,
                    time = "",
                    groupName = "",
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordStatus(
        ownerId: String,
        userId: String,
        displayName: String,
        status: String,
        statusDescription: String,
        previousStatus: String,
        previousStatusDescription: String,
    ) {
        if (ownerId.isEmpty() || !shouldWrite("status:$userId:$status:$statusDescription")) return
        scope.launch {
            val latest = feedRepository.getLatestStatus(ownerId, userId)
            if (latest != null && latest.status == status && latest.statusDescription == statusDescription) return@launch
            feedRepository.insertStatus(
                FeedStatusEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    status = status,
                    statusDescription = statusDescription,
                    previousStatus = previousStatus,
                    previousStatusDescription = previousStatusDescription,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordBio(
        ownerId: String,
        userId: String,
        displayName: String,
        bio: String,
        previousBio: String,
    ) {
        if (ownerId.isEmpty() || !shouldWrite("bio:$userId:${bio.hashCode()}")) return
        scope.launch {
            val latest = feedRepository.getLatestBio(ownerId, userId)
            if (latest != null && latest.bio == bio) return@launch
            feedRepository.insertBio(
                FeedBioEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    bio = bio,
                    previousBio = previousBio,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordAvatar(
        ownerId: String,
        userId: String,
        displayName: String,
        imageUrl: String,
        thumbnailUrl: String,
        previousImageUrl: String,
        previousThumbnailUrl: String,
    ) {
        if (ownerId.isEmpty() || !shouldWrite("avatar:$userId:$thumbnailUrl")) return
        scope.launch {
            val latest = feedRepository.getLatestAvatar(ownerId, userId)
            if (latest != null &&
                latest.currentAvatarImageUrl == imageUrl &&
                latest.currentAvatarThumbnailImageUrl == thumbnailUrl
            ) {
                return@launch
            }
            feedRepository.insertAvatar(
                FeedAvatarEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = displayName,
                    currentAvatarImageUrl = imageUrl,
                    currentAvatarThumbnailImageUrl = thumbnailUrl,
                    previousCurrentAvatarImageUrl = previousImageUrl,
                    previousCurrentAvatarThumbnailImageUrl = previousThumbnailUrl,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    private fun shouldWrite(key: String): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        recentWrites.compute(key) { _, lastWrite ->
            if (lastWrite != null && now - lastWrite < DEDUP_WINDOW_MS) {
                allowed = false
                lastWrite
            } else {
                allowed = true
                now
            }
        }
        if (allowed && recentWrites.size > MAX_RECENT_WRITES) {
            recentWrites.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        return allowed
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 10_000L
        const val MAX_RECENT_WRITES = 500
        val GPS_REVISIT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
