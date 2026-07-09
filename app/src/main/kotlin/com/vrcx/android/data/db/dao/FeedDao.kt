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
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGps(entry: FeedGpsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatus(entry: FeedStatusEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBio(entry: FeedBioEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAvatar(entry: FeedAvatarEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnlineOffline(entry: FeedOnlineOfflineEntity)

    @Query("SELECT * FROM feed_gps WHERE ownerUserId = :userId ORDER BY id DESC LIMIT :limit")
    fun getGpsFeed(userId: String, limit: Int = 100): Flow<List<FeedGpsEntity>>

    @Query("SELECT * FROM feed_status WHERE ownerUserId = :userId ORDER BY id DESC LIMIT :limit")
    fun getStatusFeed(userId: String, limit: Int = 100): Flow<List<FeedStatusEntity>>

    @Query("SELECT * FROM feed_bio WHERE ownerUserId = :userId ORDER BY id DESC LIMIT :limit")
    fun getBioFeed(userId: String, limit: Int = 100): Flow<List<FeedBioEntity>>

    @Query("SELECT * FROM feed_avatar WHERE ownerUserId = :userId ORDER BY id DESC LIMIT :limit")
    fun getAvatarFeed(userId: String, limit: Int = 100): Flow<List<FeedAvatarEntity>>

    @Query("SELECT * FROM feed_online_offline WHERE ownerUserId = :userId ORDER BY id DESC LIMIT :limit")
    fun getOnlineOfflineFeed(userId: String, limit: Int = 100): Flow<List<FeedOnlineOfflineEntity>>

    // Order feed reads by `id DESC`, not `createdAt DESC`: createdAt is
    // Instant.toString() text whose fractional seconds are variable-width
    // (e.g. "...30Z" vs "...30.123Z"), so a lexicographic sort can return the
    // wrong row for two events in the same second. The autoGenerate Long
    // primary key is monotonic and gives us the true latest.

    @Query("SELECT * FROM feed_gps WHERE ownerUserId = :ownerUserId AND userId = :userId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestGps(ownerUserId: String, userId: String): FeedGpsEntity?

    @Query("SELECT * FROM feed_status WHERE ownerUserId = :ownerUserId AND userId = :userId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestStatus(ownerUserId: String, userId: String): FeedStatusEntity?

    @Query("SELECT * FROM feed_bio WHERE ownerUserId = :ownerUserId AND userId = :userId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestBio(ownerUserId: String, userId: String): FeedBioEntity?

    @Query("SELECT * FROM feed_avatar WHERE ownerUserId = :ownerUserId AND userId = :userId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestAvatar(ownerUserId: String, userId: String): FeedAvatarEntity?

    @Query("SELECT * FROM feed_online_offline WHERE ownerUserId = :ownerUserId AND userId = :userId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestOnlineOffline(ownerUserId: String, userId: String): FeedOnlineOfflineEntity?

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
