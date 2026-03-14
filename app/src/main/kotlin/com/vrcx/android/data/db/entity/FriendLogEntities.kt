package com.vrcx.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "friend_log_current",
    indices = [Index(value = ["ownerUserId"])],
)
data class FriendLogCurrentEntity(
    @PrimaryKey val odUserId: String = "", // owner:userId composite
    val ownerUserId: String = "",
    val odDisplayName: String = "",
    val trustLevel: String = "",
    val friendNumber: Int = 0,
)

@Entity(
    tableName = "friend_log_history",
    indices = [Index(value = ["ownerUserId"]), Index(value = ["createdAt"])],
)
data class FriendLogHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String = "",
    val type: String = "",
    val odUserId: String = "",
    val displayName: String = "",
    val previousDisplayName: String = "",
    val trustLevel: String = "",
    val previousTrustLevel: String = "",
    val friendNumber: Int = 0,
    val createdAt: String = "",
)
