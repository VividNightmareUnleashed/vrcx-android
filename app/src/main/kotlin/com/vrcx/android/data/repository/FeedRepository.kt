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
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

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

interface FeedStreams {
    fun getUnifiedFeed(userId: String): Flow<List<FeedEntry>>

    fun getGpsFeed(userId: String, limit: Int): Flow<List<FeedEntry>>

    fun getAllGpsFeed(userId: String): Flow<List<FeedEntry>>
}

interface FeedEntryWrites {
    suspend fun insertGps(entry: FeedGpsEntity)

    suspend fun insertStatus(entry: FeedStatusEntity)

    suspend fun insertBio(entry: FeedBioEntity)

    suspend fun insertAvatar(entry: FeedAvatarEntity)

    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity)
}

interface FeedLatestEntries {
    suspend fun getLatestGps(ownerUserId: String, userId: String): FeedGpsEntity?

    suspend fun getLatestStatus(ownerUserId: String, userId: String): FeedStatusEntity?

    suspend fun getLatestBio(ownerUserId: String, userId: String): FeedBioEntity?

    suspend fun getLatestAvatar(ownerUserId: String, userId: String): FeedAvatarEntity?

    suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String): FeedOnlineOfflineEntity?
}

@Singleton
class FeedRepository private constructor(
    private val components: FeedRepositoryComponents,
    accountScope: AccountScope,
) : AccountScoped,
    FeedStreams by components.streams,
    FeedEntryWrites by components.writer,
    FeedLatestEntries by components.latest {

    @Inject
    constructor(
        feedDao: FeedDao,
        preferences: VrcxPreferences,
        accountScope: AccountScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        components = FeedRepositoryComponents(feedDao, preferences, ioDispatcher),
        accountScope = accountScope,
    )

    init {
        accountScope.bindTo(this)
    }

    override fun clearRuntimeState() {
        components.streams.clear()
    }
}
