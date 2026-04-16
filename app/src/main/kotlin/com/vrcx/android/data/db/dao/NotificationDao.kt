package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(entry: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationV2(entry: NotificationV2Entity)

    @Query("SELECT * FROM notifications WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getNotifications(userId: String, limit: Int = 100): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications_v2 WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getNotificationsV2(userId: String, limit: Int = 100): Flow<List<NotificationV2Entity>>

    @Query("DELETE FROM notifications WHERE id = :notificationId")
    suspend fun deleteNotification(notificationId: String)

    @Query("DELETE FROM notifications_v2 WHERE id = :notificationId")
    suspend fun deleteNotificationV2(notificationId: String)

    @Query("UPDATE notifications SET seen = 1 WHERE id = :notificationId")
    suspend fun markSeen(notificationId: String)

    @Query("UPDATE notifications_v2 SET seen = 1 WHERE id = :notificationId")
    suspend fun markSeenV2(notificationId: String)

    /** Snapshot reads for hydrating in-memory state on cold start. */
    @Query("SELECT * FROM notifications WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun snapshotNotifications(userId: String, limit: Int = 200): List<NotificationEntity>

    @Query("SELECT * FROM notifications_v2 WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun snapshotNotificationsV2(userId: String, limit: Int = 200): List<NotificationV2Entity>

    @Query("DELETE FROM notifications WHERE ownerUserId = :userId")
    suspend fun clearForUser(userId: String)

    @Query("DELETE FROM notifications_v2 WHERE ownerUserId = :userId")
    suspend fun clearV2ForUser(userId: String)
}
