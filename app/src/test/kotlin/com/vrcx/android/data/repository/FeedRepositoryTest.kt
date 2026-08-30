package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.dao.UnifiedFeedRow
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.directTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FeedRepositoryTest {
    private val feedDao = mock<FeedDao>()
    private val preferences = mock<VrcxPreferences>()

    @Test
    fun `merged feed orders same-second rows by instant, not by timestamp text`() = runTest {
        stubGpsFeed(
            gps(id = 2, createdAt = "2026-08-12T10:00:30.500Z"),
            gps(id = 1, createdAt = "2026-08-12T10:00:30Z"),
        )

        val entries = repository().getUnifiedFeed("usr_me").first()

        assertEquals(listOf(2L, 1L), entries.map { it.id })
    }

    @Test
    fun `rows with an unwritten timestamp sort behind every dated row`() = runTest {
        stubGpsFeed(
            gps(id = 2, createdAt = ""),
            gps(id = 1, createdAt = "2026-08-12T10:00:30Z"),
        )

        val entries = repository().getUnifiedFeed("usr_me").first()

        assertEquals(listOf(1L, 2L), entries.map { it.id })
        assertEquals(Long.MIN_VALUE, entries.last().createdAtEpochMs)
    }

    @Test
    fun `rows sharing a per-table id still get distinct list keys`() = runTest {
        whenever(feedDao.getUnifiedFeed(any(), any())).thenReturn(
            flowOf(
                listOf(
                    gps(id = 1, createdAt = "2026-08-12T10:00:30Z"),
                    gps(id = 1, createdAt = "2026-08-12T10:00:31Z").copy(source = "status"),
                ),
            ),
        )

        val keys = repository().getUnifiedFeed("usr_me").first().map { it.key }

        assertEquals(keys.toSet().size, keys.size)
    }

    @Test
    fun `every screen asking for one account's feed shares one merge`() = runTest {
        stubGpsFeed(gps(id = 1, createdAt = "2026-08-12T10:00:30Z"))
        val repository = repository()

        val first = repository.getUnifiedFeed("usr_me")
        val second = repository.getUnifiedFeed("usr_me")
        val otherAccount = repository.getUnifiedFeed("usr_other")

        assertSame(first, second)
        assertNotSame(first, otherAccount)
    }

    @Test
    fun `an account change drops the shared feed so no page outlives its account`() = runTest {
        stubGpsFeed(gps(id = 1, createdAt = "2026-08-12T10:00:30Z"))
        val accountScope = AccountScope()
        val repository = repository(accountScope)

        val before = repository.getUnifiedFeed("usr_me")
        accountScope.invalidate()

        assertNotSame(before, repository.getUnifiedFeed("usr_me"))
    }

    private fun repository(accountScope: AccountScope = AccountScope()): FeedRepository {
        whenever(preferences.maxFeedSize).thenReturn(flowOf(1000))
        return FeedRepository(feedDao, preferences, accountScope, directTestDispatcher)
    }

    private fun stubGpsFeed(vararg rows: UnifiedFeedRow) {
        whenever(feedDao.getUnifiedFeed(any(), any())).thenReturn(flowOf(rows.toList()))
    }

    private fun gps(id: Long, createdAt: String) = UnifiedFeedRow(
        source = "gps",
        id = id,
        userId = "usr_friend",
        displayName = "Friend",
        createdAt = createdAt,
        type = "",
        worldName = "",
        location = "",
        previousLocation = "",
        status = "",
        statusDescription = "",
        previousStatus = "",
        previousStatusDescription = "",
        bio = "",
        previousBio = "",
        avatarName = "",
        thumbnailUrl = "",
    )
}
