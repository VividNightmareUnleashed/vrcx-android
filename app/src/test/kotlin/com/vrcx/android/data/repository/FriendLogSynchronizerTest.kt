package com.vrcx.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.VrcxDatabase
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FriendLogSynchronizerTest {

    private lateinit var db: VrcxDatabase
    private lateinit var synchronizer: FriendLogSynchronizer

    private val ownerId = "usr_owner"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VrcxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        synchronizer = FriendLogSynchronizer(db, db.friendLogDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun friend(id: String, displayName: String, tags: List<String> = emptyList()) =
        FriendContext(
            id = id,
            name = displayName,
            state = FriendState.OFFLINE,
            ref = VrcUser(id = id, displayName = displayName, tags = tags),
        )

    private fun snapshot(vararg friends: FriendContext) = friends.associateBy { it.id }

    private suspend fun history() = db.friendLogDao().getHistory(ownerId).first()

    private suspend fun currentIds() =
        db.friendLogDao().getCurrentFriends(ownerId).map { it.odUserId }.toSet()

    @Test
    fun `the first sync seeds current friends without writing history`() = runBlocking {
        synchronizer.synchronize(ownerId, snapshot(friend("usr_a", "A"), friend("usr_b", "B")))

        assertEquals(setOf("$ownerId:usr_a", "$ownerId:usr_b"), currentIds())
        assertTrue(history().isEmpty())
    }

    @Test
    fun `a new friend gets a Friend row and the next friend number`() = runBlocking {
        synchronizer.synchronize(ownerId, snapshot(friend("usr_a", "A")))
        synchronizer.synchronize(ownerId, snapshot(friend("usr_a", "A"), friend("usr_b", "B")))

        val added = history().single()
        assertEquals("Friend", added.type)
        assertEquals("$ownerId:usr_b", added.odUserId)
        assertEquals(1, added.friendNumber)
    }

    @Test
    fun `renames and trust changes are recorded once each`() = runBlocking {
        synchronizer.synchronize(ownerId, snapshot(friend("usr_a", "A", listOf("system_trust_basic"))))
        synchronizer.synchronize(
            ownerId,
            snapshot(friend("usr_a", "Renamed", listOf("system_trust_known"))),
        )

        val rows = history()
        assertEquals(setOf("DisplayName", "TrustLevel"), rows.map { it.type }.toSet())
        assertEquals("A", rows.single { it.type == "DisplayName" }.previousDisplayName)

        // A repeat sync over the same snapshot must not duplicate them.
        synchronizer.synchronize(
            ownerId,
            snapshot(friend("usr_a", "Renamed", listOf("system_trust_known"))),
        )
        assertEquals(rows.size, history().size)
    }

    @Test
    fun `a plausible removal is reconciled as an Unfriend`() = runBlocking {
        val roster = (1..20).map { friend("usr_$it", "Friend $it") }
        synchronizer.synchronize(ownerId, snapshot(*roster.toTypedArray()))
        synchronizer.synchronize(ownerId, snapshot(*roster.drop(1).toTypedArray()))

        val removed = history().single()
        assertEquals("Unfriend", removed.type)
        assertEquals("$ownerId:usr_1", removed.odUserId)
        assertNull(db.friendLogDao().getCurrent("$ownerId:usr_1"))
    }

    @Test
    fun `a snapshot that came back short does not unfriend the missing friends`() = runBlocking {
        val roster = (1..20).map { friend("usr_$it", "Friend $it") }
        synchronizer.synchronize(ownerId, snapshot(*roster.toTypedArray()))

        // A truncated sweep: two thirds of the roster simply isn't there.
        synchronizer.synchronize(ownerId, snapshot(*roster.take(7).toTypedArray()))

        assertTrue(history().none { it.type == "Unfriend" })
        assertEquals(20, currentIds().size)

        // The next complete snapshot still reconciles the genuine removal.
        synchronizer.synchronize(ownerId, snapshot(*roster.drop(1).toTypedArray()))
        assertEquals(listOf("Unfriend"), history().map { it.type })
    }

    @Test
    fun `pipeline add and remove write a matching history pair`() = runBlocking {
        synchronizer.recordAdded(ownerId, "usr_a", VrcUser(id = "usr_a", displayName = "A"))
        assertNotNull(db.friendLogDao().getCurrent("$ownerId:usr_a"))

        synchronizer.recordRemoved(ownerId, "usr_a")
        assertNull(db.friendLogDao().getCurrent("$ownerId:usr_a"))
        assertEquals(listOf("Unfriend", "Friend"), history().map { it.type })
    }
}
