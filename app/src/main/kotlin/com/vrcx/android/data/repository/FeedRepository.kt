package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.UnifiedFeedRow
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.util.parseInstantMillisOrNull
import com.vrcx.android.di.IoDispatcher
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
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

/** The five kinds of friend-activity feed row, keyed by the persisted [id]. */
enum class FeedEntryType(val id: String, val label: String) {
    GPS("gps", "Location"),
    STATUS("status", "Status"),
    BIO("bio", "Bio"),
    AVATAR("avatar", "Avatar"),
    ONLINE("online", "Online"),
    OFFLINE("offline", "Offline"),
    ;

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
    /**
     * [createdAt] parsed once so range filters compare longs, not re-parse per
     * keystroke. Unparseable timestamps deliberately sort behind every valid
     * timestamp, matching [UnifiedNotification].
     */
    val createdAtEpochMs: Long = parseInstantMillisOrNull(createdAt) ?: Long.MIN_VALUE

    /** World id parsed from [location] (blank for non-location rows), for scope filtering. */
    val worldId: String = parseWorldId(location)

    /**
     * Identity across the merged feed. [id] is only unique within its source
     * table, so a GPS row and a status row can share one — which would collide as
     * a lazy-list key.
     */
    val key: String get() = "${type.id}_$id"
}

private const val PRUNE_INTERVAL = 50

/** How long the merged feed stays warm after the last screen showing it goes away. */
private const val FEED_SHARING_TIMEOUT_MS = 5_000L

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepository @Inject constructor(
    private val feedDao: FeedDao,
    preferences: VrcxPreferences,
    accountScope: AccountScope,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : AccountScoped {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Held here so the highest-frequency write path (GPS/status/online-offline
    // pipeline events) doesn't read DataStore on every insert, and so the page
    // the feed screens read and the size the pruner keeps are one number.
    private val feedLimit: StateFlow<Int> = preferences.maxFeedSize
        .map { it.coerceAtLeast(1) }
        .stateIn(scope, SharingStarted.Eagerly, PreferenceDefaults.MAX_FEED_SIZE)

    private val insertsSincePrune = AtomicInteger(0)

    // One live merge per account rather than one per screen. Room re-runs the
    // union on every feed insert, so three screens subscribing independently
    // would re-read and re-merge the same page three times per event.
    private val sharedFeeds = HashMap<String, Flow<List<FeedEntry>>>()

    init {
        accountScope.bindTo(this)
    }

    override fun clearRuntimeState() {
        synchronized(sharedFeeds) { sharedFeeds.clear() }
    }

    /**
     * The unified, newest-first friend-activity feed, merging all five source
     * tables (each capped at the configured history size) into one list of
     * [FeedEntry]. This is the single merge + entity→model mapping shared by the
     * Feed, Dashboard, and Activity History screens; consumers only filter and
     * slice.
     */
    fun getUnifiedFeed(userId: String): Flow<List<FeedEntry>> =
        synchronized(sharedFeeds) { sharedFeeds.getOrPut(userId) { mergedFeed(userId) } }

    private fun mergedFeed(userId: String): Flow<List<FeedEntry>> = feedLimit
        .flatMapLatest { limit -> feedDao.getUnifiedFeed(userId, limit) }
        .map { rows ->
            val entries = rows.mapTo(ArrayList(rows.size)) { it.toFeedEntry() }
            // Sort on the parsed epoch, never the raw string: createdAt is
            // Instant.toString() text whose fractional seconds are variable-width,
            // so a lexicographic sort reorders events within the same second.
            // That is also why the query itself leaves the rows unordered.
            entries.sortByDescending { it.createdAtEpochMs }
            entries
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(FEED_SHARING_TIMEOUT_MS), replay = 1)

    // Single-source reader still used by the Friends Locations screen for
    // offline-context lookups; the other four sources are consumed only through
    // getUnifiedFeed above.
    fun getGpsFeed(userId: String, limit: Int): Flow<List<FeedEntry>> =
        feedDao.getGpsFeed(userId, limit).map { rows -> rows.map { it.toFeedEntry() } }

    /** The account's whole location history, which the Charts screen aggregates. */
    fun getAllGpsFeed(userId: String): Flow<List<FeedEntry>> =
        feedDao.getAllGpsFeed(userId).map { rows -> rows.map { it.toFeedEntry() } }

    suspend fun insertGps(entry: FeedGpsEntity) {
        feedDao.insertGps(entry)
        maybePrune(entry.ownerUserId)
    }
    suspend fun insertStatus(entry: FeedStatusEntity) {
        feedDao.insertStatus(entry)
        maybePrune(entry.ownerUserId)
    }
    suspend fun insertBio(entry: FeedBioEntity) {
        feedDao.insertBio(entry)
        maybePrune(entry.ownerUserId)
    }
    suspend fun insertAvatar(entry: FeedAvatarEntity) {
        feedDao.insertAvatar(entry)
        maybePrune(entry.ownerUserId)
    }
    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity) {
        feedDao.insertOnlineOffline(entry)
        maybePrune(entry.ownerUserId)
    }

    suspend fun getLatestGps(ownerUserId: String, userId: String) = feedDao.getLatestGps(ownerUserId, userId)
    suspend fun getLatestStatus(ownerUserId: String, userId: String) = feedDao.getLatestStatus(ownerUserId, userId)
    suspend fun getLatestBio(ownerUserId: String, userId: String) = feedDao.getLatestBio(ownerUserId, userId)
    suspend fun getLatestAvatar(ownerUserId: String, userId: String) = feedDao.getLatestAvatar(ownerUserId, userId)
    suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String) =
        feedDao.getLatestOnlineOffline(ownerUserId, userId)

    /**
     * Prune every [PRUNE_INTERVAL] inserts rather than on every write. The feed
     * reads are all LIMIT-bounded, so a small overage between prunes is never
     * visible in the UI — this only bounds on-disk growth.
     */
    private suspend fun maybePrune(ownerUserId: String) {
        if (insertsSincePrune.incrementAndGet() < PRUNE_INTERVAL) return
        insertsSincePrune.set(0)
        val limit = feedLimit.value
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

    private fun UnifiedFeedRow.toFeedEntry() = FeedEntry(
        id = id,
        // One table backs two entry types: feed_online_offline carries its own
        // "online"/"offline" discriminator, the others are known by their source.
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
}
