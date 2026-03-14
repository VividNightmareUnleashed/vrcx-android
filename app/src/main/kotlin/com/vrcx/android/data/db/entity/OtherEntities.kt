package com.vrcx.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moderation",
    indices = [Index(value = ["ownerUserId"])],
)
data class ModerationEntity(
    @PrimaryKey val odUserId: String = "", // owner:userId composite
    val ownerUserId: String = "",
    val updatedAt: String = "",
    val displayName: String = "",
    val block: Boolean = false,
    val mute: Boolean = false,
)

@Entity(
    tableName = "avatar_history",
    indices = [Index(value = ["ownerUserId"]), Index(value = ["createdAt"])],
)
data class AvatarHistoryEntity(
    @PrimaryKey val compositeId: String = "", // owner:avatarId
    val ownerUserId: String = "",
    val avatarId: String = "",
    val createdAt: String = "",
    val time: String = "",
)

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

@Entity(
    tableName = "mutual_graph_friends",
    indices = [Index(value = ["ownerUserId"])],
)
data class MutualGraphFriendEntity(
    @PrimaryKey val compositeId: String = "", // owner:friendId
    val ownerUserId: String = "",
    val friendId: String = "",
)

@Entity(
    tableName = "mutual_graph_links",
    indices = [Index(value = ["ownerUserId"])],
)
data class MutualGraphLinkEntity(
    @PrimaryKey val compositeId: String = "", // owner:friendId:mutualId
    val ownerUserId: String = "",
    val friendId: String = "",
    val mutualId: String = "",
)

@Entity(
    tableName = "cache_avatar",
)
data class CacheAvatarEntity(
    @PrimaryKey val id: String = "",
    val data: String = "", // JSON blob
    val updatedAt: String = "",
)

@Entity(
    tableName = "cache_world",
)
data class CacheWorldEntity(
    @PrimaryKey val id: String = "",
    val data: String = "", // JSON blob
    val updatedAt: String = "",
)

@Entity(
    tableName = "favorite_world",
    indices = [Index(value = ["ownerUserId"])],
)
data class FavoriteWorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String = "",
    val worldId: String = "",
    val groupName: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "favorite_avatar",
    indices = [Index(value = ["ownerUserId"])],
)
data class FavoriteAvatarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String = "",
    val avatarId: String = "",
    val groupName: String = "",
    val createdAt: String = "",
)

@Entity(
    tableName = "favorite_friend",
    indices = [Index(value = ["ownerUserId"])],
)
data class FavoriteFriendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String = "",
    val friendUserId: String = "",
    val groupName: String = "",
    val createdAt: String = "",
)

@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey val odUserId: String = "", // owner:userId
    val ownerUserId: String = "",
    val editedAt: String = "",
    val memo: String = "",
)

@Entity(tableName = "world_memos")
data class WorldMemoEntity(
    @PrimaryKey val compositeId: String = "", // owner:worldId
    val ownerUserId: String = "",
    val worldId: String = "",
    val editedAt: String = "",
    val memo: String = "",
)

@Entity(tableName = "avatar_memos")
data class AvatarMemoEntity(
    @PrimaryKey val compositeId: String = "", // owner:avatarId
    val ownerUserId: String = "",
    val avatarId: String = "",
    val editedAt: String = "",
    val memo: String = "",
)

@Entity(
    tableName = "avatar_tags",
    primaryKeys = ["ownerUserId", "avatarId", "tag"],
)
data class AvatarTagEntity(
    val ownerUserId: String = "",
    val avatarId: String = "",
    val tag: String = "",
    val color: String = "",
)
