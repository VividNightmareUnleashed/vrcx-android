package com.vrcx.android.ui.common

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.screen.feed.FeedViewModel
import com.vrcx.android.ui.screen.gamelog.GameLogViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * The Feed and Activity History screens list the same rows behind the same
 * controls, so they must answer a search the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedFilterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    private val gpsEntry = FeedEntry(
        id = 1L,
        type = FeedEntryType.GPS,
        userId = "usr_friend",
        displayName = "Alice",
        createdAt = Instant.now().toString(),
        worldName = "The Great Pug",
        location = "wrld_pug:12345~region(eu)",
    )

    @Test
    fun `a world-name query matches a GPS entry on the feed`() = runTest(testDispatcher) {
        val viewModel = buildFeedViewModel(listOf(gpsEntry))
        testDispatcher.scheduler.runCurrent()

        viewModel.updateSearch("great pug")

        assertEquals(listOf(gpsEntry), viewModel.page.first { it.entries.isNotEmpty() }.entries)
    }

    @Test
    fun `a world-name query matches a GPS entry on activity history`() = runTest(testDispatcher) {
        val viewModel = buildGameLogViewModel(listOf(gpsEntry))
        testDispatcher.scheduler.runCurrent()

        viewModel.updateSearch("great pug")

        assertEquals(listOf(gpsEntry), viewModel.page.first { it.entries.isNotEmpty() }.entries)
    }

    @Test
    fun `a query that matches nothing filters the row out on both screens`() = runTest(testDispatcher) {
        val feed = buildFeedViewModel(listOf(gpsEntry))
        val gameLog = buildGameLogViewModel(listOf(gpsEntry))
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(gpsEntry), feed.page.first { it.entries.isNotEmpty() }.entries)
        assertEquals(listOf(gpsEntry), gameLog.page.first { it.entries.isNotEmpty() }.entries)

        feed.updateSearch("nowhere")
        gameLog.updateSearch("nowhere")

        assertEquals(emptyList<FeedEntry>(), feed.page.first { it.entries.isEmpty() }.entries)
        assertEquals(emptyList<FeedEntry>(), gameLog.page.first { it.entries.isEmpty() }.entries)
    }

    private fun buildFeedViewModel(entries: List<FeedEntry>) = FeedViewModel(
        feedRepository = feedRepository(entries),
        authRepository = authRepository(),
        friendRepository = friendRepository(),
    )

    private fun buildGameLogViewModel(entries: List<FeedEntry>) = GameLogViewModel(
        authRepository = authRepository(),
        feedRepository = feedRepository(entries),
        friendRepository = friendRepository(),
    )

    private fun feedRepository(entries: List<FeedEntry>) = mock<FeedRepository>().also {
        whenever(it.getUnifiedFeed(any())).thenReturn(flowOf(entries))
    }

    private fun authRepository() = mock<AuthRepository>().also {
        whenever(it.authState).thenReturn(MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_me"))))
    }

    private fun friendRepository() = mock<FriendRepository>().also {
        whenever(it.friends).thenReturn(
            MutableStateFlow(mapOf("usr_friend" to FriendContext("usr_friend", "Alice", FriendState.ONLINE))),
        )
        whenever(it.favoriteFriendIds).thenReturn(MutableStateFlow(emptySet()))
    }
}
