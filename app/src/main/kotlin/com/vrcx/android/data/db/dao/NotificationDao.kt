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
    suspend fun insertNotifications(entries: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationsV2(entries: List<NotificationV2Entity>)

    @Query("SELECT * FROM notifications WHERE ownerUserId = :userId ORDER BY rowid DESC LIMIT :limit")
    suspend fun getNotifications(userId: String, limit: Int = 100): List<NotificationEntity>

    @Query("SELECT * FROM notifications_v2 WHERE ownerUserId = :userId ORDER BY rowid DESC LIMIT :limit")
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

    @Query(
        """
        DELETE FROM notifications
        WHERE ownerUserId = :userId AND id NOT IN (
            SELECT id FROM notifications
            WHERE ownerUserId = :userId
            ORDER BY rowid DESC
            LIMIT :limit
        )
        """
    )
    suspend fun trimNotifications(userId: String, limit: Int)

    @Query(
        """
        DELETE FROM notifications_v2
        WHERE ownerUserId = :userId AND id NOT IN (
            SELECT id FROM notifications_v2
            WHERE ownerUserId = :userId
            ORDER BY rowid DESC
            LIMIT :limit
        )
        """
    )
    suspend fun trimNotificationsV2(userId: String, limit: Int)

    /** Upserts only the fetched page and bounds the owner's retained history. */
    @Transaction
    suspend fun upsertNotifications(
        userId: String,
        entriesNewestFirst: List<NotificationEntity>,
        limit: Int,
    ) {
        if (entriesNewestFirst.isNotEmpty()) {
            insertNotifications(entriesNewestFirst.asReversed())
        }
        trimNotifications(userId, limit)
    }

    /** Upserts only the fetched page and bounds the owner's retained history. */
    @Transaction
    suspend fun upsertNotificationsV2(
        userId: String,
        entriesNewestFirst: List<NotificationV2Entity>,
        limit: Int,
    ) {
        if (entriesNewestFirst.isNotEmpty()) {
            insertNotificationsV2(entriesNewestFirst.asReversed())
        }
        trimNotificationsV2(userId, limit)
    }

    /** Reconciles an explicitly requested full snapshot without delete/reinsert churn. */
    @Transaction
    suspend fun synchronizeNotifications(
        userId: String,
        entriesNewestFirst: List<NotificationEntity>,
        limit: Int,
    ) {
        val snapshotIds = entriesNewestFirst.map { it.id }.toSet()
        val staleIds = getNotifications(userId, limit).map { it.id }.filterNot(snapshotIds::contains)
        // Keep each IN clause below SQLite's traditional 999 bind-parameter limit.
        staleIds.chunked(900).forEach { deleteNotifications(userId, it) }
        upsertNotifications(userId, entriesNewestFirst, limit)
    }

    /** Reconciles an explicitly requested full snapshot without delete/reinsert churn. */
    @Transaction
    suspend fun synchronizeNotificationsV2(
        userId: String,
        entriesNewestFirst: List<NotificationV2Entity>,
        limit: Int,
    ) {
        val snapshotIds = entriesNewestFirst.map { it.id }.toSet()
        val staleIds = getNotificationsV2(userId, limit).map { it.id }.filterNot(snapshotIds::contains)
        staleIds.chunked(900).forEach { deleteNotificationsV2(userId, it) }
        upsertNotificationsV2(userId, entriesNewestFirst, limit)
    }
}
