package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(entry: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(entries: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationV2(entry: NotificationV2Entity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationsV2(entries: List<NotificationV2Entity>)

    @Query("SELECT * FROM notifications WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getNotifications(userId: String, limit: Int = 100): List<NotificationEntity>

    @Query("SELECT * FROM notifications_v2 WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getNotificationsV2(userId: String, limit: Int = 100): List<NotificationV2Entity>

    @Query("DELETE FROM notifications WHERE ownerUserId = :userId")
    suspend fun deleteNotificationsForUser(userId: String)

    @Query("DELETE FROM notifications_v2 WHERE ownerUserId = :userId")
    suspend fun deleteNotificationsV2ForUser(userId: String)

    @Query("DELETE FROM notifications WHERE ownerUserId = :userId AND id = :notificationId")
    suspend fun deleteNotification(userId: String, notificationId: String)

    @Query("DELETE FROM notifications_v2 WHERE ownerUserId = :userId AND id = :notificationId")
    suspend fun deleteNotificationV2(userId: String, notificationId: String)

    @Query("DELETE FROM notifications WHERE ownerUserId = :userId AND id IN (:notificationIds)")
    suspend fun deleteNotifications(userId: String, notificationIds: List<String>)

    @Query("DELETE FROM notifications_v2 WHERE ownerUserId = :userId AND id IN (:notificationIds)")
    suspend fun deleteNotificationsV2(userId: String, notificationIds: List<String>)

    @Query("UPDATE notifications SET seen = 1 WHERE ownerUserId = :userId AND id = :notificationId")
    suspend fun markSeen(userId: String, notificationId: String)

    @Query("UPDATE notifications_v2 SET seen = 1 WHERE ownerUserId = :userId AND id = :notificationId")
    suspend fun markSeenV2(userId: String, notificationId: String)

    @Transaction
    suspend fun replaceNotifications(userId: String, entries: List<NotificationEntity>) {
        deleteNotificationsForUser(userId)
        if (entries.isNotEmpty()) {
            insertNotifications(entries)
        }
    }

    @Transaction
    suspend fun replaceNotificationsV2(userId: String, entries: List<NotificationV2Entity>) {
        deleteNotificationsV2ForUser(userId)
        if (entries.isNotEmpty()) {
            insertNotificationsV2(entries)
        }
    }
}
