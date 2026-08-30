package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.util.parseInstantMillisOrNull
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.IoDispatcher
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class FriendProfileChange(val userId: String, val current: VrcUser, val previous: VrcUser)

internal class FriendActivityRecorder @Inject constructor(
    private val feedRepository: FeedRepository,
    accountScope: AccountScope,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : AccountScoped {
    private val account = accountScope.bindTo(this)
    private val lifetimeJob = SupervisorJob()
    private val scope = CoroutineScope(lifetimeJob + ioDispatcher)
    private val lifecycleLock = Any()
    private var accountJob: Job = SupervisorJob(lifetimeJob)

    // Each job awaits the tail it replaced, preserving pipeline order for one friend without blocking other friends.
    private val userWriteTails = HashMap<String, Job>()
    private val recentWrites = ConcurrentHashMap<String, Long>()
    private val logger = Logger.getLogger(FriendActivityRecorder::class.java.name)

    /**
     * A filtered offline/private hop after the latest GPS row proves that a
     * return to the same location is a revisit rather than a pipeline re-emit.
     */
    private val lastFilteredTransitionAt = ConcurrentHashMap<String, Long>()

    override fun clearRuntimeState() {
        val previousJob = synchronized(lifecycleLock) {
            val previous = accountJob
            accountJob = SupervisorJob(lifetimeJob)
            userWriteTails.clear()
            clearDedupeMarkers()
            previous
        }
        previousJob.cancel()
    }

    fun resetDedupe(token: AccountScope.Token) {
        account.publishIfCurrent(token) {
            synchronized(lifecycleLock) { clearDedupeMarkers() }
        }
    }

    private fun clearDedupeMarkers() {
        recentWrites.clear()
        lastFilteredTransitionAt.clear()
    }

    fun markFilteredTransition(token: AccountScope.Token, userId: String) {
        account.publishIfCurrent(token) {
            lastFilteredTransitionAt[userId] = System.currentTimeMillis()
        }
    }

    fun recordOnlineOffline(
        token: AccountScope.Token,
        userId: String,
        displayName: String,
        type: String,
        location: String,
    ) {
        launchRecord(token, userId, "onoff:$userId:$type") { ownerId ->
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
        launchRecord(token, userId, "gps:$userId:$location") { ownerId ->
            val latest = feedRepository.getLatestGps(ownerId, userId)
            if (latest?.matches(location, worldName, previousLocation) == true) {
                val latestMillis = parseInstantMillisOrNull(latest.createdAt)
                if (latestMillis != null) {
                    val withinWindow =
                        System.currentTimeMillis() - latestMillis < GPS_REVISIT_WINDOW.toMillis()
                    val hopAfterLatest = (lastFilteredTransitionAt[userId] ?: 0L) > latestMillis
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
        launchRecord(token, userId, "status:$userId:$status:$statusDescription") { ownerId ->
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
        launchRecord(token, userId, "bio:$userId:${bio.hashCode()}") { ownerId ->
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
        launchRecord(token, userId, "avatar:$userId:$thumbnailUrl") { ownerId ->
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

    private fun launchRecord(
        token: AccountScope.Token,
        userId: String,
        key: String,
        write: suspend (ownerId: String) -> Unit,
    ) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        val job = synchronized(lifecycleLock) {
            val parent = accountJob
            if (!parent.isActive) return
            val predecessor = userWriteTails[userId]
            scope.launch(parent, start = CoroutineStart.LAZY) {
                predecessor?.join()
                account.ensureCurrent(token)
                val reservation = synchronized(lifecycleLock) {
                    if (parent !== accountJob || !parent.isActive) null else reserveWrite(key)
                } ?: return@launch

                var writeSucceeded = false
                try {
                    val failure = runCatchingCancellable { write(token.ownerUserId) }.exceptionOrNull()
                    if (failure == null) {
                        writeSucceeded = true
                    } else {
                        logger.log(Level.WARNING, "Friend activity write failed", failure)
                    }
                } finally {
                    if (!writeSucceeded) rollbackReservation(key, reservation)
                }
            }.also { userWriteTails[userId] = it }
        }
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (userWriteTails[userId] === job) userWriteTails.remove(userId)
            }
        }
        job.start()
    }

    private fun reserveWrite(key: String): Long? {
        val now = System.nanoTime() / NANOS_PER_MILLISECOND
        var reservation: Long? = null
        recentWrites.compute(key) { _, lastWrite ->
            if (lastWrite != null && now - lastWrite < DEDUP_WINDOW_MS) {
                lastWrite
            } else {
                reservation = now
                now
            }
        }
        if (reservation != null && recentWrites.size > MAX_RECENT_WRITES) {
            recentWrites.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        return reservation
    }

    private fun rollbackReservation(key: String, reservation: Long) {
        recentWrites.remove(key, reservation)
    }

    private fun FeedGpsEntity.matches(location: String, worldName: String, previousLocation: String): Boolean {
        if (this.location != location) return false
        if (this.worldName != worldName) return false
        return this.previousLocation == previousLocation
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEDUP_WINDOW_MS = 10_000L
        const val MAX_RECENT_WRITES = 500
        val GPS_REVISIT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
