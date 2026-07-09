package com.vrcx.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vrcx.android.data.db.converter.Converters
import com.vrcx.android.data.db.dao.AvatarHistoryDao
import com.vrcx.android.data.db.dao.AvatarTagDao
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.dao.FavoriteLocalDao
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.ModerationDao
import com.vrcx.android.data.db.dao.MutualGraphDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.db.entity.AvatarHistoryEntity
import com.vrcx.android.data.db.entity.AvatarMemoEntity
import com.vrcx.android.data.db.entity.AvatarTagEntity
import com.vrcx.android.data.db.entity.CacheAvatarEntity
import com.vrcx.android.data.db.entity.CacheWorldEntity
import com.vrcx.android.data.db.entity.FavoriteAvatarEntity
import com.vrcx.android.data.db.entity.FavoriteFriendEntity
import com.vrcx.android.data.db.entity.FavoriteWorldEntity
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.ModerationEntity
import com.vrcx.android.data.db.entity.MutualGraphFriendEntity
import com.vrcx.android.data.db.entity.MutualGraphLinkEntity
import com.vrcx.android.data.db.entity.NoteEntity
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import com.vrcx.android.data.db.entity.WorldMemoEntity

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
        ModerationEntity::class,
        AvatarHistoryEntity::class,
        NoteEntity::class,
        MutualGraphFriendEntity::class,
        MutualGraphLinkEntity::class,
        CacheAvatarEntity::class,
        CacheWorldEntity::class,
        FavoriteWorldEntity::class,
        FavoriteAvatarEntity::class,
        FavoriteFriendEntity::class,
        MemoEntity::class,
        WorldMemoEntity::class,
        AvatarMemoEntity::class,
        AvatarTagEntity::class,
        FriendNotifyEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VrcxDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun friendLogDao(): FriendLogDao
    abstract fun notificationDao(): NotificationDao
    abstract fun moderationDao(): ModerationDao
    abstract fun avatarHistoryDao(): AvatarHistoryDao
    abstract fun noteDao(): NoteDao
    abstract fun mutualGraphDao(): MutualGraphDao
    abstract fun cacheDao(): CacheDao
    abstract fun favoriteLocalDao(): FavoriteLocalDao
    abstract fun memoDao(): MemoDao
    abstract fun avatarTagDao(): AvatarTagDao
    abstract fun friendNotifyDao(): FriendNotifyDao
}
