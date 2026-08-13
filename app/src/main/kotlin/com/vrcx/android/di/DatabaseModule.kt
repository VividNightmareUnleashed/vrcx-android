package com.vrcx.android.di

import android.content.Context
import androidx.room.Room
import com.vrcx.android.data.db.MIGRATION_1_2
import com.vrcx.android.data.db.MIGRATION_2_3
import com.vrcx.android.data.db.MIGRATION_3_4
import com.vrcx.android.data.db.MIGRATION_4_5
import com.vrcx.android.data.db.MIGRATION_5_6
import com.vrcx.android.data.db.VrcxDatabase
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.NotificationDao
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    @Provides fun provideFeedDao(db: VrcxDatabase): FeedDao = db.feedDao()
    @Provides fun provideFriendLogDao(db: VrcxDatabase): FriendLogDao = db.friendLogDao()
    @Provides fun provideNotificationDao(db: VrcxDatabase): NotificationDao = db.notificationDao()
    @Provides fun provideNoteDao(db: VrcxDatabase): NoteDao = db.noteDao()
    @Provides fun provideMemoDao(db: VrcxDatabase): MemoDao = db.memoDao()
    @Provides fun provideFriendNotifyDao(db: VrcxDatabase): FriendNotifyDao = db.friendNotifyDao()

}
