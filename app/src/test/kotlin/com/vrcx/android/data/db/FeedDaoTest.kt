package com.vrcx.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.UnifiedFeedRow
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
        assertEquals(listOf("new", "old"), dao.merged(ownerUserId, "status") { it.status })
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
        assertEquals(listOf("new", "old"), dao.merged(ownerUserId, "bio") { it.bio })
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
        assertEquals(listOf("new", "old"), dao.merged(ownerUserId, "avatar") { it.avatarName })
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
        assertEquals(listOf("online", "offline"), dao.merged(ownerUserId, "onlineOffline") { it.type })
        assertEquals("online", dao.getLatestOnlineOffline(ownerUserId, userId)?.type)
    }

    @Test
    fun `feed reads only return rows belonging to the requested owner`() = runBlocking {
        val dao = db.feedDao()
        val userId = "usr_friend"

        dao.insertRow(OWNER_A, userId, "a1")
        dao.insertRow(OWNER_A, userId, "a2")
        // Owner B writes last and shares the friend id, so an unscoped read would
        // put B's row at the head of A's feed and return it as A's latest.
        dao.insertRow(OWNER_B, userId, "b1")

        assertEquals(FEED_TABLES.associateWith { listOf("a2", "a1") }, dao.feedPayloads(OWNER_A))
        assertEquals(FEED_TABLES.associateWith { listOf("b1") }, dao.feedPayloads(OWNER_B))
        assertEquals(FEED_TABLES.associateWith { "a2" }, dao.latestPayloads(OWNER_A, userId))
        assertEquals(FEED_TABLES.associateWith { "b1" }, dao.latestPayloads(OWNER_B, userId))
        assertEquals(listOf("a2", "a1"), dao.getAllGpsFeed(OWNER_A).first().map { it.location })
        assertEquals(listOf("b1"), dao.getAllGpsFeed(OWNER_B).first().map { it.location })
    }

    @Test
    fun `pruning one owner keeps the other owner's rows`() = runBlocking {
        val dao = db.feedDao()
        val userId = "usr_friend"

        dao.insertRow(OWNER_A, userId, "a1")
        dao.insertRow(OWNER_A, userId, "a2")
        dao.insertRow(OWNER_A, userId, "a3")
        dao.insertRow(OWNER_B, userId, "b1")
        dao.insertRow(OWNER_B, userId, "b2")

        dao.pruneGps(OWNER_A, limit = 2)
        dao.pruneStatus(OWNER_A, limit = 2)
        dao.pruneBio(OWNER_A, limit = 2)
        dao.pruneAvatar(OWNER_A, limit = 2)
        dao.pruneOnlineOffline(OWNER_A, limit = 2)

        assertEquals(FEED_TABLES.associateWith { listOf("a3", "a2") }, dao.feedPayloads(OWNER_A))
        assertEquals(FEED_TABLES.associateWith { listOf("b2", "b1") }, dao.feedPayloads(OWNER_B))
    }

    @Test
    fun `the merged feed reads every source and stays scoped to one owner`() = runBlocking {
        val dao = db.feedDao()
        val userId = "usr_friend"

        dao.insertRow(OWNER_A, userId, "a1")
        dao.insertRow(OWNER_A, userId, "a2")
        dao.insertRow(OWNER_B, userId, "b1")

        val rows = dao.getUnifiedFeed(OWNER_A, limit = 10).first()

        // Every source contributes, none of B's rows leak in, and the payload
        // column each source writes to survives the union.
        assertEquals(FEED_TABLES.sorted(), rows.map { it.source }.distinct().sorted())
        assertEquals(listOf("a2", "a1"), rows.filter { it.source == "gps" }.map { it.location })
        assertEquals(listOf("a2", "a1"), rows.filter { it.source == "status" }.map { it.status })
        assertEquals(listOf("a2", "a1"), rows.filter { it.source == "bio" }.map { it.bio })
        assertEquals(listOf("a2", "a1"), rows.filter { it.source == "avatar" }.map { it.avatarName })
        assertEquals(listOf("a2", "a1"), rows.filter { it.source == "onlineOffline" }.map { it.type })
        assertEquals(
            listOf("b1"),
            dao.getUnifiedFeed(OWNER_B, limit = 10).first().filter { it.source == "gps" }.map { it.location },
        )
    }

    @Test
    fun `the merged feed limits each source separately`() = runBlocking {
        val dao = db.feedDao()
        val userId = "usr_friend"
        repeat(3) { index -> dao.insertRow(OWNER_A, userId, "a$index") }

        val rows = dao.getUnifiedFeed(OWNER_A, limit = 2).first()

        // A shared limit across the union would starve four of the five sources.
        assertEquals(FEED_TABLES.associateWith { 2 }, rows.groupingBy { it.source }.eachCount())
    }

    /** One row per feed table carrying [payload] as its changed value. */
    private suspend fun FeedDao.insertRow(ownerUserId: String, userId: String, payload: String) {
        val createdAt = "2026-03-19T10:00:30Z"
        insertGps(
            FeedGpsEntity(ownerUserId = ownerUserId, userId = userId, location = payload, createdAt = createdAt)
        )
        insertStatus(
            FeedStatusEntity(ownerUserId = ownerUserId, userId = userId, status = payload, createdAt = createdAt)
        )
        insertBio(
            FeedBioEntity(ownerUserId = ownerUserId, userId = userId, bio = payload, createdAt = createdAt)
        )
        insertAvatar(
            FeedAvatarEntity(ownerUserId = ownerUserId, userId = userId, avatarName = payload, createdAt = createdAt)
        )
        insertOnlineOffline(
            FeedOnlineOfflineEntity(ownerUserId = ownerUserId, userId = userId, type = payload, createdAt = createdAt)
        )
    }

    private suspend fun FeedDao.feedPayloads(ownerUserId: String): Map<String, List<String>> = mapOf(
        "gps" to merged(ownerUserId, "gps") { it.location },
        "status" to merged(ownerUserId, "status") { it.status },
        "bio" to merged(ownerUserId, "bio") { it.bio },
        "avatar" to merged(ownerUserId, "avatar") { it.avatarName },
        "onlineOffline" to merged(ownerUserId, "onlineOffline") { it.type },
    )

    /** The payload column [source] writes to, newest first, as the merged read returns it. */
    private suspend fun FeedDao.merged(
        ownerUserId: String,
        source: String,
        payloadOf: (UnifiedFeedRow) -> String,
    ): List<String> = getUnifiedFeed(ownerUserId, limit = 10).first()
        .filter { it.source == source }
        .map(payloadOf)

    private suspend fun FeedDao.latestPayloads(ownerUserId: String, userId: String): Map<String, String?> = mapOf(
        "gps" to getLatestGps(ownerUserId, userId)?.location,
        "status" to getLatestStatus(ownerUserId, userId)?.status,
        "bio" to getLatestBio(ownerUserId, userId)?.bio,
        "avatar" to getLatestAvatar(ownerUserId, userId)?.avatarName,
        "onlineOffline" to getLatestOnlineOffline(ownerUserId, userId)?.type,
    )

    private companion object {
        const val OWNER_A = "usr_owner_a"
        const val OWNER_B = "usr_owner_b"
        val FEED_TABLES = listOf("gps", "status", "bio", "avatar", "onlineOffline")
    }
}
