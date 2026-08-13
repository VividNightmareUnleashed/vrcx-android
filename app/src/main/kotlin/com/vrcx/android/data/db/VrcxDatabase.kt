package com.vrcx.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.NoteEntity
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity

@Database(
    entities = [
        FeedGpsEntity::class,
        FeedStatusEntity::class,
        FeedBioEntity::class,
        FeedAvatarEntity::class,
        FeedOnlineOfflineEntity::class,
        FriendLogCurrentEntity::class,
        FriendLogHistoryEntity::class,
        NotificationEntity::class,
        NotificationV2Entity::class,
        NoteEntity::class,
        MemoEntity::class,
        FriendNotifyEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class VrcxDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun friendLogDao(): FriendLogDao
    abstract fun notificationDao(): NotificationDao
    abstract fun noteDao(): NoteDao
    abstract fun memoDao(): MemoDao
    abstract fun friendNotifyDao(): FriendNotifyDao
}
