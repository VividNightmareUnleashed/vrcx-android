package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.util.parseInstantMillisOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** The five kinds of friend-activity feed row, keyed by the persisted [id]. */
enum class FeedEntryType(val id: String) {
    GPS("gps"),
    STATUS("status"),
    BIO("bio"),
    AVATAR("avatar"),
    ONLINE("online"),
    OFFLINE("offline");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): FeedEntryType = byId[id] ?: GPS
    }
}

/**
 * One friend-activity row, merged from the five per-type feed tables into a
 * single model. Carries the raw payload fields; display formatting (headline,
 * detail, one-line label) lives in FeedPresentation so the Feed, Dashboard, and
 * Activity History screens share one mapper instead of three drifting copies.
 */
data class FeedEntry(
    val id: Long,
    val type: FeedEntryType,
    val userId: String,
    val displayName: String,
    val createdAt: String,
    val worldName: String = "",
    val location: String = "",
    val previousLocation: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val previousStatus: String = "",
    val previousStatusDescription: String = "",
    val bio: String = "",
    val previousBio: String = "",
    val avatarName: String = "",
    val thumbnailUrl: String = "",
) {
    /** [createdAt] parsed once so range filters compare longs, not re-parse per keystroke. */
    val createdAtEpochMs: Long = parseInstantMillisOrNull(createdAt) ?: Long.MAX_VALUE

    /** World id parsed from [location] (blank for non-location rows), for scope filtering. */
    val worldId: String = parseWorldId(location)
}

/**
 * A merged feed page. [sourceSaturated] is true when any of the five source
 * tables returned a full page, i.e. more history may exist beyond the fetch
 * limit — the Feed screen uses it to decide whether "Load more" can grow.
 */
data class UnifiedFeed(
    val entries: List<FeedEntry>,
    val sourceSaturated: Boolean,
)

private const val DEFAULT_FEED_LIMIT = 1000
private const val PRUNE_INTERVAL = 50

@Singleton
class FeedRepository @Inject constructor(
    private val feedDao: FeedDao,
    private val preferences: VrcxPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached so the highest-frequency write path (GPS/status/online-offline
    // pipeline events) doesn't read DataStore on every insert; one collector
    // keeps it fresh.
    @Volatile private var cachedFeedLimit: Int = DEFAULT_FEED_LIMIT
    private val insertsSincePrune = AtomicInteger(0)

    init {
        scope.launch {
            preferences.maxFeedSize.collect { cachedFeedLimit = it.coerceAtLeast(1) }
        }
    }

    /**
     * The unified, newest-first friend-activity feed, merging all five source
     * tables (each capped at [limit]) into one list of [FeedEntry]. This is the
     * single merge + entity→model mapping shared by the Feed, Dashboard, and
     * Activity History screens; consumers only filter and slice.
     */
    fun getUnifiedFeed(userId: String, limit: Int): Flow<UnifiedFeed> = combine(
        feedDao.getGpsFeed(userId, limit),
        feedDao.getStatusFeed(userId, limit),
        feedDao.getBioFeed(userId, limit),
        feedDao.getAvatarFeed(userId, limit),
        feedDao.getOnlineOfflineFeed(userId, limit),
    ) { gps, status, bio, avatar, onlineOffline ->
        val entries = ArrayList<FeedEntry>(
            gps.size + status.size + bio.size + avatar.size + onlineOffline.size,
        )
        gps.forEach { entries += it.toFeedEntry() }
        status.forEach { entries += it.toFeedEntry() }
        bio.forEach { entries += it.toFeedEntry() }
        avatar.forEach { entries += it.toFeedEntry() }
        onlineOffline.forEach { entries += it.toFeedEntry() }
        entries.sortByDescending { it.createdAt }
        UnifiedFeed(
            entries = entries,
            sourceSaturated = listOf(gps, status, bio, avatar, onlineOffline).any { it.size >= limit },
        )
    }

    // Single-source reader still used by the Friends Locations screen for
    // offline-context lookups; the other four sources are consumed only through
    // getUnifiedFeed above.
    fun getGpsFeed(userId: String, limit: Int): Flow<List<FeedGpsEntity>> = feedDao.getGpsFeed(userId, limit)

    suspend fun insertGps(entry: FeedGpsEntity) { feedDao.insertGps(entry); maybePrune(entry.ownerUserId) }
    suspend fun insertStatus(entry: FeedStatusEntity) { feedDao.insertStatus(entry); maybePrune(entry.ownerUserId) }
    suspend fun insertBio(entry: FeedBioEntity) { feedDao.insertBio(entry); maybePrune(entry.ownerUserId) }
    suspend fun insertAvatar(entry: FeedAvatarEntity) { feedDao.insertAvatar(entry); maybePrune(entry.ownerUserId) }
    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity) { feedDao.insertOnlineOffline(entry); maybePrune(entry.ownerUserId) }

    suspend fun getLatestGps(ownerUserId: String, userId: String) = feedDao.getLatestGps(ownerUserId, userId)
    suspend fun getLatestStatus(ownerUserId: String, userId: String) = feedDao.getLatestStatus(ownerUserId, userId)
    suspend fun getLatestBio(ownerUserId: String, userId: String) = feedDao.getLatestBio(ownerUserId, userId)
    suspend fun getLatestAvatar(ownerUserId: String, userId: String) = feedDao.getLatestAvatar(ownerUserId, userId)
    suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String) = feedDao.getLatestOnlineOffline(ownerUserId, userId)

    /**
     * Prune every [PRUNE_INTERVAL] inserts rather than on every write. The feed
     * reads are all LIMIT-bounded, so a small overage between prunes is never
     * visible in the UI — this only bounds on-disk growth.
     */
    private suspend fun maybePrune(ownerUserId: String) {
        if (insertsSincePrune.incrementAndGet() < PRUNE_INTERVAL) return
        insertsSincePrune.set(0)
        val limit = cachedFeedLimit
        feedDao.pruneGps(ownerUserId, limit)
        feedDao.pruneStatus(ownerUserId, limit)
        feedDao.pruneBio(ownerUserId, limit)
        feedDao.pruneAvatar(ownerUserId, limit)
        feedDao.pruneOnlineOffline(ownerUserId, limit)
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

    private fun FeedStatusEntity.toFeedEntry() = FeedEntry(
        id = id,
        type = FeedEntryType.STATUS,
        userId = userId,
        displayName = displayName,
        createdAt = createdAt,
        status = status,
        statusDescription = statusDescription,
        previousStatus = previousStatus,
        previousStatusDescription = previousStatusDescription,
    )

    private fun FeedBioEntity.toFeedEntry() = FeedEntry(
        id = id,
        type = FeedEntryType.BIO,
        userId = userId,
        displayName = displayName,
        createdAt = createdAt,
        bio = bio,
        previousBio = previousBio,
    )

    private fun FeedAvatarEntity.toFeedEntry() = FeedEntry(
        id = id,
        type = FeedEntryType.AVATAR,
        userId = userId,
        displayName = displayName,
        createdAt = createdAt,
        avatarName = avatarName,
        thumbnailUrl = currentAvatarThumbnailImageUrl,
    )

    private fun FeedOnlineOfflineEntity.toFeedEntry() = FeedEntry(
        id = id,
        type = FeedEntryType.fromId(type),
        userId = userId,
        displayName = displayName,
        createdAt = createdAt,
        worldName = worldName,
        location = location,
    )
}
