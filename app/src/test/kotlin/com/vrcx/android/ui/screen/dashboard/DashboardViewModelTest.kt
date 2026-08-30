package com.vrcx.android.ui.screen.dashboard

import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.common.MainDispatcherRule
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

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `friendCounts groups friends by enum state, not name strings`() = runTest(testDispatcher) {
        val friends = MutableStateFlow(
            mapOf(
                "a" to FriendContext("a", "Alice", FriendState.ONLINE),
                "b" to FriendContext("b", "Bob", FriendState.ONLINE),
                "c" to FriendContext("c", "Carol", FriendState.ACTIVE),
                "d" to FriendContext("d", "Dave", FriendState.OFFLINE),
                "e" to FriendContext("e", "Eve", FriendState.OFFLINE),
                "f" to FriendContext("f", "Frank", FriendState.OFFLINE),
            ),
        )
        val viewModel = buildViewModel(friends = friends)

        testDispatcher.scheduler.runCurrent()

        val counts = viewModel.state.first { it.friendCounts.isNotEmpty() }.friendCounts
        assertEquals(
            mapOf(FriendState.ONLINE to 2, FriendState.ACTIVE to 1, FriendState.OFFLINE to 3),
            counts,
        )
    }

    @Test
    fun `friendCounts updates when the upstream flow emits a new map`() = runTest(testDispatcher) {
        val friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
        val viewModel = buildViewModel(friends = friends)

        testDispatcher.scheduler.runCurrent()
        // No friends yet — counts stay at the initial value.
        assertEquals(emptyMap<FriendState, Int>(), viewModel.state.value.friendCounts)

        friends.value = mapOf("x" to FriendContext("x", "X", FriendState.ONLINE))
        val updated = viewModel.state.first { it.friendCounts.isNotEmpty() }.friendCounts
        assertEquals(mapOf(FriendState.ONLINE to 1), updated)
    }

    @Test
    fun `the favourites row and the counters come from one snapshot`() = runTest(testDispatcher) {
        val friends = MutableStateFlow(
            mapOf(
                "a" to FriendContext("a", "Alice", FriendState.ONLINE),
                "b" to FriendContext("b", "Bob", FriendState.ONLINE),
                "c" to FriendContext("c", "Carol", FriendState.OFFLINE),
            ),
        )
        val viewModel = buildViewModel(friends = friends, favorites = setOf("b", "c"))

        val state = viewModel.state.first { it.friendCounts.isNotEmpty() }
        assertEquals(mapOf(FriendState.ONLINE to 2, FriendState.OFFLINE to 1), state.friendCounts)
        assertEquals(listOf("Bob"), state.favoriteOnlineFriends.map { it.name })
    }

    private fun buildViewModel(
        friends: MutableStateFlow<Map<String, FriendContext>> = MutableStateFlow(emptyMap()),
        favorites: Set<String> = emptySet(),
    ): DashboardViewModel {
        val authRepository = mock<AuthRepository>().also {
            whenever(it.authState).thenReturn(MutableStateFlow(AuthState.NotLoggedIn))
        }
        val friendRepository = mock<FriendRepository>().also {
            whenever(it.friends).thenReturn(friends)
            whenever(it.favoriteFriendIds).thenReturn(MutableStateFlow(favorites))
        }
        val feedRepository = mock<FeedRepository>().also {
            whenever(it.getUnifiedFeed(any())).thenReturn(flowOf(emptyList()))
        }
        return DashboardViewModel(authRepository, friendRepository, feedRepository, testDispatcher)
    }
}
