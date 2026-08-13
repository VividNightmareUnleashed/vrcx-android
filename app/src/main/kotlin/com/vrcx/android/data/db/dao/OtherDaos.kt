package com.vrcx.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: NoteEntity)

    @Query("SELECT * FROM notes WHERE compositeId = :compositeId")
    suspend fun get(compositeId: String): NoteEntity?
}

@Dao
interface MemoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(entry: MemoEntity)

    @Query("SELECT * FROM memos WHERE odUserId = :compositeId")
    suspend fun getMemo(compositeId: String): MemoEntity?

    @Query("SELECT * FROM memos WHERE ownerUserId = :ownerUserId")
    suspend fun getMemos(ownerUserId: String): List<MemoEntity>
}

@Dao
interface FriendNotifyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FriendNotifyEntity)

    @Query("DELETE FROM friend_notify WHERE compositeId = :compositeId")
    suspend fun delete(compositeId: String)

    @Query("SELECT * FROM friend_notify WHERE compositeId = :compositeId")
    suspend fun get(compositeId: String): FriendNotifyEntity?

    @Query("SELECT friendUserId FROM friend_notify WHERE ownerUserId = :ownerUserId")
    fun getEnabledFriendIds(ownerUserId: String): Flow<List<String>>

    @Query("SELECT friendUserId FROM friend_notify WHERE ownerUserId = :ownerUserId")
    suspend fun getEnabledFriendIdsSnapshot(ownerUserId: String): List<String>
}
