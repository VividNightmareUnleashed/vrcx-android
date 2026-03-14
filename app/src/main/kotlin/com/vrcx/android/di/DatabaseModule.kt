package com.vrcx.android.di

import android.content.Context
import androidx.room.Room
import com.vrcx.android.data.db.VrcxDatabase
import com.vrcx.android.data.db.dao.AvatarHistoryDao
import com.vrcx.android.data.db.dao.AvatarTagDao
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.dao.FavoriteLocalDao
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.ModerationDao
import com.vrcx.android.data.db.dao.MutualGraphDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.preferences.VrcxPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VrcxDatabase {
        return Room.databaseBuilder(context, VrcxDatabase::class.java, "vrcx.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideFeedDao(db: VrcxDatabase): FeedDao = db.feedDao()
    @Provides fun provideFriendLogDao(db: VrcxDatabase): FriendLogDao = db.friendLogDao()
    @Provides fun provideNotificationDao(db: VrcxDatabase): NotificationDao = db.notificationDao()
    @Provides fun provideModerationDao(db: VrcxDatabase): ModerationDao = db.moderationDao()
    @Provides fun provideAvatarHistoryDao(db: VrcxDatabase): AvatarHistoryDao = db.avatarHistoryDao()
    @Provides fun provideNoteDao(db: VrcxDatabase): NoteDao = db.noteDao()
    @Provides fun provideMutualGraphDao(db: VrcxDatabase): MutualGraphDao = db.mutualGraphDao()
    @Provides fun provideCacheDao(db: VrcxDatabase): CacheDao = db.cacheDao()
    @Provides fun provideFavoriteLocalDao(db: VrcxDatabase): FavoriteLocalDao = db.favoriteLocalDao()
    @Provides fun provideMemoDao(db: VrcxDatabase): MemoDao = db.memoDao()
    @Provides fun provideAvatarTagDao(db: VrcxDatabase): AvatarTagDao = db.avatarTagDao()

    @Provides
    @Singleton
    fun provideVrcxPreferences(@ApplicationContext context: Context): VrcxPreferences {
        return VrcxPreferences(context)
    }
}
