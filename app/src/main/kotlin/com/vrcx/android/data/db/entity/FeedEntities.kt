package com.vrcx.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feed_gps",
    indices = [Index(value = ["ownerUserId", "id"]), Index(value = ["ownerUserId", "userId", "id"])],
)
data class FeedGpsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val ownerUserId: String = "",
    val displayName: String = "",
    val location: String = "",
    val worldName: String = "",
    val previousLocation: String = "",
    val time: String = "",
    val groupName: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "feed_status",
    indices = [Index(value = ["ownerUserId", "id"]), Index(value = ["ownerUserId", "userId", "id"])],
)
data class FeedStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val ownerUserId: String = "",
    val displayName: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val previousStatus: String = "",
    val previousStatusDescription: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "feed_bio",
    indices = [Index(value = ["ownerUserId", "id"]), Index(value = ["ownerUserId", "userId", "id"])],
)
data class FeedBioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val ownerUserId: String = "",
    val displayName: String = "",
    val bio: String = "",
    val previousBio: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "feed_avatar",
    indices = [Index(value = ["ownerUserId", "id"]), Index(value = ["ownerUserId", "userId", "id"])],
)
data class FeedAvatarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val ownerUserId: String = "",
    val displayName: String = "",
    val ownerId: String = "",
    val avatarName: String = "",
    val currentAvatarImageUrl: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val previousCurrentAvatarImageUrl: String = "",
    val previousCurrentAvatarThumbnailImageUrl: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "feed_online_offline",
    indices = [Index(value = ["ownerUserId", "id"]), Index(value = ["ownerUserId", "userId", "id"])],
)
data class FeedOnlineOfflineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val ownerUserId: String = "",
    val displayName: String = "",
    val type: String = "",
    val location: String = "",
    val worldName: String = "",
    val time: String = "",
    val groupName: String = "",
    val createdAt: String = "",
)
