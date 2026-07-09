package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCurrent(entry: FriendLogCurrentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: FriendLogHistoryEntity)

    @Query("SELECT * FROM friend_log_current WHERE ownerUserId = :userId")
    suspend fun getCurrentFriends(userId: String): List<FriendLogCurrentEntity>

    @Query("SELECT * FROM friend_log_current WHERE odUserId = :compositeId LIMIT 1")
    suspend fun getCurrent(compositeId: String): FriendLogCurrentEntity?

    @Query("SELECT MAX(friendNumber) FROM friend_log_current WHERE ownerUserId = :userId")
    suspend fun getMaxFriendNumber(userId: String): Int?

    @Query("SELECT * FROM friend_log_history WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getHistory(userId: String, limit: Int = 100): Flow<List<FriendLogHistoryEntity>>

    @Query("DELETE FROM friend_log_current WHERE odUserId = :compositeId")
    suspend fun deleteCurrent(compositeId: String)

    @Query("DELETE FROM friend_log_current WHERE ownerUserId = :userId")
    suspend fun clearCurrent(userId: String)
}
