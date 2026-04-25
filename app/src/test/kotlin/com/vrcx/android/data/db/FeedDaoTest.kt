package com.vrcx.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedDaoTest {
    private lateinit var db: VrcxDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VrcxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `feed queries use inserted row order instead of createdAt text order`() = runBlocking {
        val dao = db.feedDao()
        val ownerUserId = "usr_owner"
        val userId = "usr_friend"
        val compactTimestamp = "2026-03-19T10:00:30Z"
        val fractionalTimestamp = "2026-03-19T10:00:30.123Z"

        dao.insertGps(
            FeedGpsEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                location = "wrld_old:old",
                createdAt = compactTimestamp,
            )
        )
        dao.insertGps(
            FeedGpsEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                location = "wrld_new:new",
                createdAt = fractionalTimestamp,
            )
        )
        assertEquals(
            listOf("wrld_new:new", "wrld_old:old"),
            dao.getGpsFeed(ownerUserId, limit = 2).first().map { it.location },
        )
        assertEquals("wrld_new:new", dao.getLatestGps(ownerUserId, userId)?.location)

        dao.insertStatus(
            FeedStatusEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                status = "old",
                createdAt = compactTimestamp,
            )
        )
        dao.insertStatus(
            FeedStatusEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                status = "new",
                createdAt = fractionalTimestamp,
            )
        )
        assertEquals(
            listOf("new", "old"),
            dao.getStatusFeed(ownerUserId, limit = 2).first().map { it.status },
        )
        assertEquals("new", dao.getLatestStatus(ownerUserId, userId)?.status)

        dao.insertBio(
            FeedBioEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                bio = "old",
                createdAt = compactTimestamp,
            )
        )
        dao.insertBio(
            FeedBioEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                bio = "new",
                createdAt = fractionalTimestamp,
            )
        )
        assertEquals(
            listOf("new", "old"),
            dao.getBioFeed(ownerUserId, limit = 2).first().map { it.bio },
        )
        assertEquals("new", dao.getLatestBio(ownerUserId, userId)?.bio)

        dao.insertAvatar(
            FeedAvatarEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                avatarName = "old",
                createdAt = compactTimestamp,
            )
        )
        dao.insertAvatar(
            FeedAvatarEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                avatarName = "new",
                createdAt = fractionalTimestamp,
            )
        )
        assertEquals(
            listOf("new", "old"),
            dao.getAvatarFeed(ownerUserId, limit = 2).first().map { it.avatarName },
        )
        assertEquals("new", dao.getLatestAvatar(ownerUserId, userId)?.avatarName)

        dao.insertOnlineOffline(
            FeedOnlineOfflineEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                type = "offline",
                createdAt = compactTimestamp,
            )
        )
        dao.insertOnlineOffline(
            FeedOnlineOfflineEntity(
                ownerUserId = ownerUserId,
                userId = userId,
                type = "online",
                createdAt = fractionalTimestamp,
            )
        )
        assertEquals(
            listOf("online", "offline"),
            dao.getOnlineOfflineFeed(ownerUserId, limit = 2).first().map { it.type },
        )
        assertEquals("online", dao.getLatestOnlineOffline(ownerUserId, userId)?.type)
    }
}
