package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vrcx.android.data.db.entity.AvatarHistoryEntity
import com.vrcx.android.data.db.entity.AvatarMemoEntity
import com.vrcx.android.data.db.entity.AvatarTagEntity
import com.vrcx.android.data.db.entity.CacheAvatarEntity
import com.vrcx.android.data.db.entity.CacheWorldEntity
import com.vrcx.android.data.db.entity.FavoriteAvatarEntity
import com.vrcx.android.data.db.entity.FavoriteFriendEntity
import com.vrcx.android.data.db.entity.FavoriteWorldEntity
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.ModerationEntity
import com.vrcx.android.data.db.entity.MutualGraphFriendEntity
import com.vrcx.android.data.db.entity.MutualGraphLinkEntity
import com.vrcx.android.data.db.entity.NoteEntity
import com.vrcx.android.data.db.entity.WorldMemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModerationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ModerationEntity)

    @Query("SELECT * FROM moderation WHERE ownerUserId = :userId")
    fun getAll(userId: String): Flow<List<ModerationEntity>>

    @Query("DELETE FROM moderation WHERE odUserId = :compositeId")
    suspend fun delete(compositeId: String)
}

@Dao
interface AvatarHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AvatarHistoryEntity)

    @Query("SELECT * FROM avatar_history WHERE ownerUserId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getHistory(userId: String, limit: Int = 100): Flow<List<AvatarHistoryEntity>>
}

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: NoteEntity)

    @Query("SELECT * FROM notes WHERE compositeId = :compositeId")
    suspend fun get(compositeId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE ownerUserId = :userId")
    fun getAll(userId: String): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes WHERE compositeId = :compositeId")
    suspend fun delete(compositeId: String)
}

@Dao
interface MutualGraphDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(entry: MutualGraphFriendEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(entry: MutualGraphLinkEntity)

    @Query("SELECT * FROM mutual_graph_friends WHERE ownerUserId = :userId")
    suspend fun getFriends(userId: String): List<MutualGraphFriendEntity>

    @Query("SELECT * FROM mutual_graph_links WHERE ownerUserId = :userId")
    suspend fun getLinks(userId: String): List<MutualGraphLinkEntity>

    @Query("DELETE FROM mutual_graph_friends WHERE ownerUserId = :userId")
    suspend fun clearFriends(userId: String)

    @Query("DELETE FROM mutual_graph_links WHERE ownerUserId = :userId")
    suspend fun clearLinks(userId: String)
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(entry: CacheAvatarEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorld(entry: CacheWorldEntity)

    @Query("SELECT * FROM cache_avatar WHERE id = :id")
    suspend fun getAvatar(id: String): CacheAvatarEntity?

    @Query("SELECT * FROM cache_world WHERE id = :id")
    suspend fun getWorld(id: String): CacheWorldEntity?
}

@Dao
interface FavoriteLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorld(entry: FavoriteWorldEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(entry: FavoriteAvatarEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(entry: FavoriteFriendEntity)

    @Query("SELECT * FROM favorite_world WHERE ownerUserId = :userId")
    fun getWorlds(userId: String): Flow<List<FavoriteWorldEntity>>

    @Query("SELECT * FROM favorite_avatar WHERE ownerUserId = :userId")
    fun getAvatars(userId: String): Flow<List<FavoriteAvatarEntity>>

    @Query("SELECT * FROM favorite_friend WHERE ownerUserId = :userId")
    fun getFriends(userId: String): Flow<List<FavoriteFriendEntity>>

    @Query("DELETE FROM favorite_world WHERE id = :id")
    suspend fun deleteWorld(id: Long)

    @Query("DELETE FROM favorite_avatar WHERE id = :id")
    suspend fun deleteAvatar(id: Long)

    @Query("DELETE FROM favorite_friend WHERE id = :id")
    suspend fun deleteFriend(id: Long)
}

@Dao
interface MemoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(entry: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorldMemo(entry: WorldMemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatarMemo(entry: AvatarMemoEntity)

    @Query("SELECT * FROM memos WHERE odUserId = :compositeId")
    suspend fun getMemo(compositeId: String): MemoEntity?

    @Query("SELECT * FROM world_memos WHERE compositeId = :compositeId")
    suspend fun getWorldMemo(compositeId: String): WorldMemoEntity?

    @Query("SELECT * FROM avatar_memos WHERE compositeId = :compositeId")
    suspend fun getAvatarMemo(compositeId: String): AvatarMemoEntity?

    @Query("DELETE FROM memos WHERE odUserId = :compositeId")
    suspend fun deleteMemo(compositeId: String)
}

@Dao
interface AvatarTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AvatarTagEntity)

    @Query("SELECT * FROM avatar_tags WHERE ownerUserId = :userId AND avatarId = :avatarId")
    suspend fun getTags(userId: String, avatarId: String): List<AvatarTagEntity>

    @Query("DELETE FROM avatar_tags WHERE ownerUserId = :userId AND avatarId = :avatarId AND tag = :tag")
    suspend fun delete(userId: String, avatarId: String, tag: String)
}
