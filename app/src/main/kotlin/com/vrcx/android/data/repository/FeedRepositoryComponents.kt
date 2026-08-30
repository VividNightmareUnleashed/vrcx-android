package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.UnifiedFeedRow
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.VrcxPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

private const val PRUNE_INTERVAL = 50
private const val FEED_SHARING_TIMEOUT_MS = 5_000L

internal class FeedRepositoryComponents(
    feedDao: FeedDao,
    preferences: VrcxPreferences,
    ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val feedLimit = preferences.maxFeedSize
        .map { it.coerceAtLeast(1) }
        .stateIn(scope, SharingStarted.Eagerly, PreferenceDefaults.MAX_FEED_SIZE)

    val streams = FeedStreamCache(feedDao, feedLimit, scope)
    val writer = FeedEntryWriter(feedDao, feedLimit)
    val latest = FeedLatestEntryReader(feedDao)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FeedStreamCache(
    private val feedDao: FeedDao,
    private val feedLimit: StateFlow<Int>,
    private val scope: CoroutineScope,
) : FeedStreams {
    // Room performs one union per account no matter how many screens observe it.
    private val sharedFeeds = HashMap<String, Flow<List<FeedEntry>>>()

    override fun getUnifiedFeed(userId: String): Flow<List<FeedEntry>> =
        synchronized(sharedFeeds) { sharedFeeds.getOrPut(userId) { mergedFeed(userId) } }

    override fun getGpsFeed(userId: String, limit: Int): Flow<List<FeedEntry>> =
        feedDao.getGpsFeed(userId, limit).map { rows -> rows.map(FeedGpsEntity::toFeedEntry) }

    override fun getAllGpsFeed(userId: String): Flow<List<FeedEntry>> =
        feedDao.getAllGpsFeed(userId).map { rows -> rows.map(FeedGpsEntity::toFeedEntry) }

    fun clear() {
        synchronized(sharedFeeds) { sharedFeeds.clear() }
    }

    private fun mergedFeed(userId: String): Flow<List<FeedEntry>> = feedLimit
        .flatMapLatest { limit -> feedDao.getUnifiedFeed(userId, limit) }
        // Instant text has variable-width fractions, so raw-string ordering is incorrect.
        .map { rows -> rows.toSortedFeedEntries() }
        .shareIn(scope, SharingStarted.WhileSubscribed(FEED_SHARING_TIMEOUT_MS), replay = 1)
}

internal class FeedEntryWriter(private val feedDao: FeedDao, private val feedLimit: StateFlow<Int>) : FeedEntryWrites {
    private val insertsSincePrune = AtomicInteger(0)

    override suspend fun insertGps(entry: FeedGpsEntity) {
        feedDao.insertGps(entry)
        maybePrune(entry.ownerUserId)
    }

    override suspend fun insertStatus(entry: FeedStatusEntity) {
        feedDao.insertStatus(entry)
        maybePrune(entry.ownerUserId)
    }

    override suspend fun insertBio(entry: FeedBioEntity) {
        feedDao.insertBio(entry)
        maybePrune(entry.ownerUserId)
    }

    override suspend fun insertAvatar(entry: FeedAvatarEntity) {
        feedDao.insertAvatar(entry)
        maybePrune(entry.ownerUserId)
    }

    override suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity) {
        feedDao.insertOnlineOffline(entry)
        maybePrune(entry.ownerUserId)
    }

    private suspend fun maybePrune(ownerUserId: String) {
        // Reads are limit-bounded; batching only permits a small invisible on-disk overage.
        if (insertsSincePrune.incrementAndGet() < PRUNE_INTERVAL) return
        insertsSincePrune.set(0)
        val limit = feedLimit.value
        feedDao.pruneGps(ownerUserId, limit)
        feedDao.pruneStatus(ownerUserId, limit)
        feedDao.pruneBio(ownerUserId, limit)
        feedDao.pruneAvatar(ownerUserId, limit)
        feedDao.pruneOnlineOffline(ownerUserId, limit)
    }
}

internal class FeedLatestEntryReader(private val feedDao: FeedDao) : FeedLatestEntries {
    override suspend fun getLatestGps(ownerUserId: String, userId: String): FeedGpsEntity? =
        feedDao.getLatestGps(ownerUserId, userId)

    override suspend fun getLatestStatus(ownerUserId: String, userId: String): FeedStatusEntity? =
        feedDao.getLatestStatus(ownerUserId, userId)

    override suspend fun getLatestBio(ownerUserId: String, userId: String): FeedBioEntity? =
        feedDao.getLatestBio(ownerUserId, userId)

    override suspend fun getLatestAvatar(ownerUserId: String, userId: String): FeedAvatarEntity? =
        feedDao.getLatestAvatar(ownerUserId, userId)

    override suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String): FeedOnlineOfflineEntity? =
        feedDao.getLatestOnlineOffline(ownerUserId, userId)
}

private fun List<UnifiedFeedRow>.toSortedFeedEntries(): List<FeedEntry> =
    mapTo(ArrayList(size), UnifiedFeedRow::toFeedEntry).apply {
        sortByDescending(FeedEntry::createdAtEpochMs)
    }

private fun FeedGpsEntity.toFeedEntry() = FeedEntry(
    id = id,
    type = FeedEntryType.GPS,
    userId = userId,
    displayName = displayName,
    createdAt = createdAt,
    worldName = worldName,
    location = location,
    previousLocation = previousLocation,
)

private fun UnifiedFeedRow.toFeedEntry() = FeedEntry(
    id = id,
    type = when (source) {
        "gps" -> FeedEntryType.GPS
        "status" -> FeedEntryType.STATUS
        "bio" -> FeedEntryType.BIO
        "avatar" -> FeedEntryType.AVATAR
        else -> FeedEntryType.fromId(type)
    },
    userId = userId,
    displayName = displayName,
    createdAt = createdAt,
    worldName = worldName,
    location = location,
    previousLocation = previousLocation,
    status = status,
    statusDescription = statusDescription,
    previousStatus = previousStatus,
    previousStatusDescription = previousStatusDescription,
    bio = bio,
    previousBio = previousBio,
    avatarName = avatarName,
    thumbnailUrl = thumbnailUrl,
)
