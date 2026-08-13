package com.vrcx.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.db.dao.enable
import com.vrcx.android.data.db.dao.memosByUserId
import com.vrcx.android.data.db.dao.saveMemo
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every table shared between accounts is read, trimmed and deleted through an
 * `ownerUserId` clause. Each fixture writes owner B last so an unscoped query
 * would return B's rows to A.
 */
@RunWith(RobolectricTestRunner::class)
class AccountScopedDaoTest {
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
    fun `notification reads, trims and deletes stay scoped to their owner`() = runBlocking {
        val dao = db.notificationDao()
        dao.insertNotifications(
            listOf(
                NotificationEntity(id = "a1", ownerUserId = OWNER_A),
                NotificationEntity(id = "a2", ownerUserId = OWNER_A),
                NotificationEntity(id = "a3", ownerUserId = OWNER_A),
                NotificationEntity(id = "b1", ownerUserId = OWNER_B),
                NotificationEntity(id = "b2", ownerUserId = OWNER_B),
            )
        )

        assertEquals(listOf("a3", "a2", "a1"), dao.getNotifications(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotifications(OWNER_B).map { it.id })

        dao.markSeen(OWNER_B, "a1")
        assertEquals(emptyList<String>(), dao.getNotifications(OWNER_A).filter { it.seen }.map { it.id })

        dao.trimNotifications(OWNER_A, limit = 2)
        assertEquals(listOf("a3", "a2"), dao.getNotifications(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotifications(OWNER_B).map { it.id })

        dao.deleteNotificationsForUser(OWNER_A)
        assertEquals(emptyList<String>(), dao.getNotifications(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotifications(OWNER_B).map { it.id })
    }

    @Test
    fun `notification v2 reads, trims and deletes stay scoped to their owner`() = runBlocking {
        val dao = db.notificationDao()
        dao.insertNotificationsV2(
            listOf(
                NotificationV2Entity(id = "a1", ownerUserId = OWNER_A),
                NotificationV2Entity(id = "a2", ownerUserId = OWNER_A),
                NotificationV2Entity(id = "a3", ownerUserId = OWNER_A),
                NotificationV2Entity(id = "b1", ownerUserId = OWNER_B),
                NotificationV2Entity(id = "b2", ownerUserId = OWNER_B),
            )
        )

        assertEquals(listOf("a3", "a2", "a1"), dao.getNotificationsV2(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotificationsV2(OWNER_B).map { it.id })

        dao.markSeenV2(OWNER_B, "a1")
        assertEquals(emptyList<String>(), dao.getNotificationsV2(OWNER_A).filter { it.seen }.map { it.id })

        dao.trimNotificationsV2(OWNER_A, limit = 2)
        assertEquals(listOf("a3", "a2"), dao.getNotificationsV2(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotificationsV2(OWNER_B).map { it.id })

        dao.deleteNotificationsV2ForUser(OWNER_A)
        assertEquals(emptyList<String>(), dao.getNotificationsV2(OWNER_A).map { it.id })
        assertEquals(listOf("b2", "b1"), dao.getNotificationsV2(OWNER_B).map { it.id })
    }

    @Test
    fun `friend log reads stay scoped to their owner`() = runBlocking {
        val dao = db.friendLogDao()
        dao.insertCurrent(
            listOf(
                FriendLogCurrentEntity(odUserId = "$OWNER_A:usr_1", ownerUserId = OWNER_A, friendNumber = 1),
                FriendLogCurrentEntity(odUserId = "$OWNER_A:usr_2", ownerUserId = OWNER_A, friendNumber = 2),
                // B's friend number is the highest, so an unscoped MAX() would hand it to A.
                FriendLogCurrentEntity(odUserId = "$OWNER_B:usr_1", ownerUserId = OWNER_B, friendNumber = 9),
            )
        )
        listOf("a1", "a2", "b1").forEach { entry ->
            dao.insertHistory(
                FriendLogHistoryEntity(
                    ownerUserId = if (entry.startsWith("a")) OWNER_A else OWNER_B,
                    type = "Friend",
                    displayName = entry,
                )
            )
        }

        assertEquals(
            listOf("$OWNER_A:usr_1", "$OWNER_A:usr_2"),
            dao.getCurrentFriends(OWNER_A).map { it.odUserId },
        )
        assertEquals(listOf("$OWNER_B:usr_1"), dao.getCurrentFriends(OWNER_B).map { it.odUserId })
        assertEquals(2, dao.getMaxFriendNumber(OWNER_A))
        assertEquals(9, dao.getMaxFriendNumber(OWNER_B))
        assertEquals(listOf("a2", "a1"), dao.getHistory(OWNER_A).first().map { it.displayName })
        assertEquals(listOf("b1"), dao.getHistory(OWNER_B).first().map { it.displayName })
    }

    @Test
    fun `memo and friend-notify reads stay scoped to their owner`() = runBlocking {
        val memoDao = db.memoDao()
        memoDao.saveMemo(OWNER_A, "usr_1", memo = "a1", editedAt = EDITED_AT)
        memoDao.saveMemo(OWNER_A, "usr_2", memo = "a2", editedAt = EDITED_AT)
        memoDao.saveMemo(OWNER_B, "usr_1", memo = "b1", editedAt = EDITED_AT)

        assertEquals(mapOf("usr_1" to "a1", "usr_2" to "a2"), memoDao.memosByUserId(OWNER_A))
        assertEquals(mapOf("usr_1" to "b1"), memoDao.memosByUserId(OWNER_B))

        val notifyDao = db.friendNotifyDao()
        notifyDao.enable(OWNER_A, "usr_1")
        notifyDao.enable(OWNER_A, "usr_2")
        notifyDao.enable(OWNER_B, "usr_3")

        assertEquals(listOf("usr_1", "usr_2"), notifyDao.getEnabledFriendIds(OWNER_A).first())
        assertEquals(listOf("usr_3"), notifyDao.getEnabledFriendIds(OWNER_B).first())
        assertEquals(listOf("usr_1", "usr_2"), notifyDao.getEnabledFriendIdsSnapshot(OWNER_A))
        assertEquals(listOf("usr_3"), notifyDao.getEnabledFriendIdsSnapshot(OWNER_B))
    }

    private companion object {
        const val OWNER_A = "usr_owner_a"
        const val OWNER_B = "usr_owner_b"
        const val EDITED_AT = "2026-03-19T10:00:30Z"
    }
}
