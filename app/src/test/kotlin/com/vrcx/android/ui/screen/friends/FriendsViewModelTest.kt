package com.vrcx.android.ui.screen.friends

import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FriendsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `a failed refresh publishes an error so the retry path exists`() = runTest(testDispatcher) {
        val friendRepository = buildRepository()
        whenever(friendRepository.loadFriendsList()).thenThrow(RuntimeException("no network"))
        val viewModel = FriendsViewModel(friendRepository, testDispatcher)

        viewModel.refresh()
        advanceUntilIdle()

        val failedState = viewModel.state.first { it.error != null }
        assertEquals("no network", failedState.error)
        assertEquals(false, failedState.isRefreshing)

        viewModel.consumeError()
        assertNull(viewModel.state.first { it.error == null }.error)
    }

    @Test
    fun `counts are keyed by state so the tabs cannot be mislabelled`() = runTest(testDispatcher) {
        val friends = MutableStateFlow(
            mapOf(
                "a" to FriendContext("a", "Alice", FriendState.ONLINE),
                "b" to FriendContext("b", "Bob", FriendState.OFFLINE),
                "c" to FriendContext("c", "Carol", FriendState.OFFLINE),
            ),
        )
        val viewModel = FriendsViewModel(buildRepository(friends), testDispatcher)

        val state = viewModel.state.first { it.counts.isNotEmpty() }
        assertEquals(mapOf(FriendState.ONLINE to 1, FriendState.OFFLINE to 2), state.counts)
    }

    @Test
    fun `selecting a tab filters the roster by that state`() = runTest(testDispatcher) {
        val friends = MutableStateFlow(
            mapOf(
                "a" to FriendContext("a", "Alice", FriendState.ONLINE),
                "b" to FriendContext("b", "Bob", FriendState.OFFLINE),
            ),
        )
        val viewModel = FriendsViewModel(buildRepository(friends), testDispatcher)

        viewModel.selectTab(FriendState.OFFLINE)
        viewModel.updateSearch("B")
        viewModel.updateSearch("Bob")
        assertEquals(FriendState.OFFLINE, viewModel.controls.value.selectedTab)
        assertEquals("Bob", viewModel.controls.value.searchQuery)

        val state = viewModel.state.first {
            it.selectedTab == FriendState.OFFLINE &&
                it.friends.isNotEmpty()
        }
        assertEquals(FriendState.OFFLINE, state.selectedTab)
        assertEquals(listOf("Bob"), state.friends.map { it.name })
    }

    @Test
    fun `the VIP filter reads the repository's favourite set`() = runTest(testDispatcher) {
        val friends = MutableStateFlow(
            mapOf(
                "a" to FriendContext("a", "Alice", FriendState.ONLINE),
                "b" to FriendContext("b", "Bob", FriendState.ONLINE),
            ),
        )
        val viewModel = FriendsViewModel(buildRepository(friends, favorites = setOf("b")), testDispatcher)

        viewModel.toggleVipOnly()

        val state = viewModel.state.first { it.vipOnly && it.friends.isNotEmpty() }
        assertEquals(listOf("Bob"), state.friends.map { it.name })
    }

    private fun buildRepository(
        friends: MutableStateFlow<Map<String, FriendContext>> = MutableStateFlow(emptyMap()),
        favorites: Set<String> = emptySet(),
    ) = mock<FriendRepository>().also {
        whenever(it.friends).thenReturn(friends)
        whenever(it.favoriteFriendIds).thenReturn(MutableStateFlow(favorites))
        whenever(it.notifyEnabledIds).thenReturn(MutableStateFlow(emptySet()))
    }
}
