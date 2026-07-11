package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.preferences.VrcxPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class FeedEntry(
    val id: Long,
    val type: String, // gps, status, bio, avatar, online, offline
    val userId: String,
    val displayName: String,
    val details: String,
    val previousDetails: String = "",
    val createdAt: String,
    val thumbnailUrl: String = "",
)

@Singleton
class FeedRepository @Inject constructor(
    private val feedDao: FeedDao,
    private val preferences: VrcxPreferences,
) {
    fun getGpsFeed(userId: String, limit: Int): Flow<List<FeedGpsEntity>> = feedDao.getGpsFeed(userId, limit)
    fun getAllGpsFeed(userId: String): Flow<List<FeedGpsEntity>> = feedDao.getAllGpsFeed(userId)
    fun getStatusFeed(userId: String, limit: Int): Flow<List<FeedStatusEntity>> = feedDao.getStatusFeed(userId, limit)
    fun getBioFeed(userId: String, limit: Int): Flow<List<FeedBioEntity>> = feedDao.getBioFeed(userId, limit)
    fun getAvatarFeed(userId: String, limit: Int): Flow<List<FeedAvatarEntity>> = feedDao.getAvatarFeed(userId, limit)
    fun getOnlineOfflineFeed(userId: String, limit: Int): Flow<List<FeedOnlineOfflineEntity>> = feedDao.getOnlineOfflineFeed(userId, limit)

    suspend fun insertGps(entry: FeedGpsEntity) { feedDao.insertGps(entry); feedDao.pruneGps(entry.ownerUserId, feedLimit()) }
    suspend fun insertStatus(entry: FeedStatusEntity) { feedDao.insertStatus(entry); feedDao.pruneStatus(entry.ownerUserId, feedLimit()) }
    suspend fun insertBio(entry: FeedBioEntity) { feedDao.insertBio(entry); feedDao.pruneBio(entry.ownerUserId, feedLimit()) }
    suspend fun insertAvatar(entry: FeedAvatarEntity) { feedDao.insertAvatar(entry); feedDao.pruneAvatar(entry.ownerUserId, feedLimit()) }
    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity) { feedDao.insertOnlineOffline(entry); feedDao.pruneOnlineOffline(entry.ownerUserId, feedLimit()) }

    suspend fun getLatestGps(ownerUserId: String, userId: String) = feedDao.getLatestGps(ownerUserId, userId)
    suspend fun getLatestStatus(ownerUserId: String, userId: String) = feedDao.getLatestStatus(ownerUserId, userId)
    suspend fun getLatestBio(ownerUserId: String, userId: String) = feedDao.getLatestBio(ownerUserId, userId)
    suspend fun getLatestAvatar(ownerUserId: String, userId: String) = feedDao.getLatestAvatar(ownerUserId, userId)
    suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String) = feedDao.getLatestOnlineOffline(ownerUserId, userId)

    private suspend fun feedLimit(): Int = preferences.maxFeedSize.first().coerceAtLeast(1)
}
