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
        val fixture = OrderingFixture(
            ownerUserId = "usr_owner",
            userId = "usr_friend",
            compactTimestamp = "2026-03-19T10:00:30Z",
            fractionalTimestamp = "2026-03-19T10:00:30.123Z",
        )

        dao.verifyGpsOrder(fixture)
        dao.verifyStatusOrder(fixture)
        dao.verifyBioOrder(fixture)
        dao.verifyAvatarOrder(fixture)
        dao.verifyOnlineOrder(fixture)
    }

    private suspend fun FeedDao.verifyGpsOrder(fixture: OrderingFixture) {
        insertGps(
            FeedGpsEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                location = "wrld_old:old",
                createdAt = fixture.compactTimestamp,
            ),
        )
        insertGps(
            FeedGpsEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                location = "wrld_new:new",
                createdAt = fixture.fractionalTimestamp,
            ),
        )
        assertEquals(
            listOf("wrld_new:new", "wrld_old:old"),
            getGpsFeed(fixture.ownerUserId, limit = 2).first().map { it.location },
        )
        assertEquals("wrld_new:new", getLatestGps(fixture.ownerUserId, fixture.userId)?.location)
    }

    private suspend fun FeedDao.verifyStatusOrder(fixture: OrderingFixture) {
        insertStatus(
            FeedStatusEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                status = "old",
                createdAt = fixture.compactTimestamp,
            ),
        )
        insertStatus(
            FeedStatusEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                status = "new",
                createdAt = fixture.fractionalTimestamp,
            ),
        )
        assertEquals(listOf("new", "old"), merged(fixture.ownerUserId, "status") { it.status })
        assertEquals("new", getLatestStatus(fixture.ownerUserId, fixture.userId)?.status)
    }

    private suspend fun FeedDao.verifyBioOrder(fixture: OrderingFixture) {
        insertBio(
            FeedBioEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                bio = "old",
                createdAt = fixture.compactTimestamp,
            ),
        )
        insertBio(
            FeedBioEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                bio = "new",
                createdAt = fixture.fractionalTimestamp,
            ),
        )
        assertEquals(listOf("new", "old"), merged(fixture.ownerUserId, "bio") { it.bio })
        assertEquals("new", getLatestBio(fixture.ownerUserId, fixture.userId)?.bio)
    }

    private suspend fun FeedDao.verifyAvatarOrder(fixture: OrderingFixture) {
        insertAvatar(
            FeedAvatarEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                avatarName = "old",
                createdAt = fixture.compactTimestamp,
            ),
        )
        insertAvatar(
            FeedAvatarEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                avatarName = "new",
                createdAt = fixture.fractionalTimestamp,
            ),
        )
        assertEquals(listOf("new", "old"), merged(fixture.ownerUserId, "avatar") { it.avatarName })
        assertEquals("new", getLatestAvatar(fixture.ownerUserId, fixture.userId)?.avatarName)
    }

    private suspend fun FeedDao.verifyOnlineOrder(fixture: OrderingFixture) {
        insertOnlineOffline(
            FeedOnlineOfflineEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                type = "offline",
                createdAt = fixture.compactTimestamp,
            ),
        )
        insertOnlineOffline(
            FeedOnlineOfflineEntity(
                ownerUserId = fixture.ownerUserId,
                userId = fixture.userId,
                type = "online",
                createdAt = fixture.fractionalTimestamp,
            ),
        )
        assertEquals(listOf("online", "offline"), merged(fixture.ownerUserId, "onlineOffline") { it.type })
        assertEquals("online", getLatestOnlineOffline(fixture.ownerUserId, fixture.userId)?.type)
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
            FeedGpsEntity(ownerUserId = ownerUserId, userId = userId, location = payload, createdAt = createdAt),
        )
        insertStatus(
            FeedStatusEntity(ownerUserId = ownerUserId, userId = userId, status = payload, createdAt = createdAt),
        )
        insertBio(
            FeedBioEntity(ownerUserId = ownerUserId, userId = userId, bio = payload, createdAt = createdAt),
        )
        insertAvatar(
            FeedAvatarEntity(ownerUserId = ownerUserId, userId = userId, avatarName = payload, createdAt = createdAt),
        )
        insertOnlineOffline(
            FeedOnlineOfflineEntity(ownerUserId = ownerUserId, userId = userId, type = payload, createdAt = createdAt),
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
        data class OrderingFixture(
            val ownerUserId: String,
            val userId: String,
            val compactTimestamp: String,
            val fractionalTimestamp: String,
        )

        const val OWNER_A = "usr_owner_a"
        const val OWNER_B = "usr_owner_b"
        val FEED_TABLES = listOf("gps", "status", "bio", "avatar", "onlineOffline")
    }
}
