package com.vrcx.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [Index(value = ["ownerUserId"])],
)
data class NoteEntity(
    @PrimaryKey val compositeId: String = "", // owner:userId
    val ownerUserId: String = "",
    val odUserId: String = "",
    val displayName: String = "",
    val note: String = "",
    val createdAt: String = "",
)

@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey val odUserId: String = "", // owner:userId
    val ownerUserId: String = "",
    val editedAt: String = "",
    val memo: String = "",
)

@Entity(
    tableName = "friend_notify",
    indices = [Index(value = ["ownerUserId"])],
)
data class FriendNotifyEntity(
    @PrimaryKey val compositeId: String = "", // ownerUserId:friendUserId
    val ownerUserId: String = "",
    val friendUserId: String = "",
)
