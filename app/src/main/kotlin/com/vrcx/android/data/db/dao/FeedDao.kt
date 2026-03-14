package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGps(entry: FeedGpsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(entry: FeedStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBio(entry: FeedBioEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(entry: FeedAvatarEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity)

    @Query("SELECT * FROM feed_gps WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getGpsFeed(userId: String, limit: Int = 100): Flow<List<FeedGpsEntity>>

    @Query("SELECT * FROM feed_status WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getStatusFeed(userId: String, limit: Int = 100): Flow<List<FeedStatusEntity>>

    @Query("SELECT * FROM feed_bio WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getBioFeed(userId: String, limit: Int = 100): Flow<List<FeedBioEntity>>

    @Query("SELECT * FROM feed_avatar WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getAvatarFeed(userId: String, limit: Int = 100): Flow<List<FeedAvatarEntity>>

    @Query("SELECT * FROM feed_online_offline WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getOnlineOfflineFeed(userId: String, limit: Int = 100): Flow<List<FeedOnlineOfflineEntity>>

    @Query("DELETE FROM feed_gps WHERE ownerUserId = :userId")
    suspend fun clearGpsFeed(userId: String)

    @Query("DELETE FROM feed_status WHERE ownerUserId = :userId")
    suspend fun clearStatusFeed(userId: String)

    @Query("DELETE FROM feed_bio WHERE ownerUserId = :userId")
    suspend fun clearBioFeed(userId: String)

    @Query("DELETE FROM feed_avatar WHERE ownerUserId = :userId")
    suspend fun clearAvatarFeed(userId: String)

    @Query("DELETE FROM feed_online_offline WHERE ownerUserId = :userId")
    suspend fun clearOnlineOfflineFeed(userId: String)
}
