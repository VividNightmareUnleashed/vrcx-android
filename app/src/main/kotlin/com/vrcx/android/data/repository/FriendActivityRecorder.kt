package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.util.parseInstantMillisOrNull
import com.vrcx.android.di.IoDispatcher
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

internal data class FriendProfileChange(val userId: String, val current: VrcUser, val previous: VrcUser)

internal class FriendActivityRecorder @Inject constructor(
    private val feedRepository: FeedRepository,
    accountScope: AccountScope,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : AccountScoped {
    private val account = accountScope.bindTo(this)
    private val writes = FriendActivityWriteCoordinator(account, ioDispatcher)

    override fun clearRuntimeState() {
        writes.clearRuntimeState()
    }

    fun resetDedupe(token: AccountScope.Token) {
        writes.resetDedupe(token)
    }

    fun markFilteredTransition(token: AccountScope.Token, userId: String) {
        writes.markFilteredTransition(token, userId)
    }

    fun recordOnlineOffline(
        token: AccountScope.Token,
        userId: String,
        displayName: String,
        type: String,
        location: String,
    ) {
        writes.launchRecord(token, userId, "onoff:$userId:$type") { ownerId ->
            val latest = feedRepository.getLatestOnlineOffline(ownerId, userId)
            if (latest != null && latest.type == type &&
                latest.location == location
            ) {
                return@launchRecord
            }
            account.ensureCurrent(token)
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
        token: AccountScope.Token,
        transition: FriendTransition.ChangedLocation,
        location: String,
        previousLocation: String,
    ) {
        val userId = transition.userId
        val worldName = transition.worldName
        writes.launchRecord(token, userId, "gps:$userId:$location") { ownerId ->
            val latest = feedRepository.getLatestGps(ownerId, userId)
            if (latest?.matches(location, worldName, previousLocation) == true) {
                val latestMillis = parseInstantMillisOrNull(latest.createdAt)
                if (latestMillis != null) {
                    val withinWindow =
                        System.currentTimeMillis() - latestMillis < GPS_REVISIT_WINDOW.toMillis()
                    val hopAfterLatest = writes.hasFilteredTransitionAfter(userId, latestMillis)
                    if (withinWindow && !hopAfterLatest) return@launchRecord
                }
                // Prefer a possible duplicate over dropping a real revisit when
                // an old row has an unparseable timestamp.
            }
            account.ensureCurrent(token)
            feedRepository.insertGps(
                FeedGpsEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = transition.displayName,
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

    fun recordStatus(token: AccountScope.Token, change: FriendProfileChange) {
        val current = change.current
        val previous = change.previous
        val userId = change.userId
        val status = current.status
        val statusDescription = current.statusDescription
        writes.launchRecord(token, userId, "status:$userId:$status:$statusDescription") { ownerId ->
            val latest = feedRepository.getLatestStatus(ownerId, userId)
            if (latest != null && latest.status == status &&
                latest.statusDescription == statusDescription
            ) {
                return@launchRecord
            }
            account.ensureCurrent(token)
            feedRepository.insertStatus(
                FeedStatusEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = current.displayName,
                    status = status,
                    statusDescription = statusDescription,
                    previousStatus = previous.status,
                    previousStatusDescription = previous.statusDescription,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordBio(token: AccountScope.Token, change: FriendProfileChange) {
        val current = change.current
        val userId = change.userId
        val bio = current.bio
        writes.launchRecord(token, userId, "bio:$userId:${bio.hashCode()}") { ownerId ->
            val latest = feedRepository.getLatestBio(ownerId, userId)
            if (latest != null && latest.bio == bio) return@launchRecord
            account.ensureCurrent(token)
            feedRepository.insertBio(
                FeedBioEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = current.displayName,
                    bio = bio,
                    previousBio = change.previous.bio,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    fun recordAvatar(token: AccountScope.Token, change: FriendProfileChange) {
        val current = change.current
        val previous = change.previous
        val userId = change.userId
        val imageUrl = current.currentAvatarImageUrl
        val thumbnailUrl = current.currentAvatarThumbnailImageUrl
        writes.launchRecord(token, userId, "avatar:$userId:$thumbnailUrl") { ownerId ->
            val latest = feedRepository.getLatestAvatar(ownerId, userId)
            if (latest != null &&
                latest.currentAvatarImageUrl == imageUrl &&
                latest.currentAvatarThumbnailImageUrl == thumbnailUrl
            ) {
                return@launchRecord
            }
            account.ensureCurrent(token)
            feedRepository.insertAvatar(
                FeedAvatarEntity(
                    ownerUserId = ownerId,
                    userId = userId,
                    displayName = current.displayName,
                    currentAvatarImageUrl = imageUrl,
                    currentAvatarThumbnailImageUrl = thumbnailUrl,
                    previousCurrentAvatarImageUrl = previous.currentAvatarImageUrl,
                    previousCurrentAvatarThumbnailImageUrl = previous.currentAvatarThumbnailImageUrl,
                    createdAt = Instant.now().toString(),
                ),
            )
        }
    }

    private companion object {
        val GPS_REVISIT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}

private fun FeedGpsEntity.matches(location: String, worldName: String, previousLocation: String): Boolean {
    if (this.location != location) return false
    if (this.worldName != worldName) return false
    return this.previousLocation == previousLocation
}
