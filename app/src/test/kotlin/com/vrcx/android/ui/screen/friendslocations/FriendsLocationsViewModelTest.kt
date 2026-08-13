package com.vrcx.android.ui.screen.friendslocations

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FriendsLocationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `the active segment yields no groups when no friend is active`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(
            friends = MutableStateFlow(mapOf("a" to FriendContext("a", "Alice", FriendState.OFFLINE))),
        )
        viewModel.selectSegment(LocationSegment.ACTIVE)
        advanceUntilIdle()

        assertEquals(emptyList<LocationGroup>(), viewModel.locationGroups.first())
    }

    @Test
    fun `the active segment groups the friends that are active`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(
            friends = MutableStateFlow(mapOf("a" to FriendContext("a", "Alice", FriendState.ACTIVE))),
        )
        viewModel.selectSegment(LocationSegment.ACTIVE)

        val groups = viewModel.locationGroups.first { it.isNotEmpty() }
        assertEquals(listOf("Alice"), groups.single().friends.map { it.name })
    }

    @Test
    fun `a world whose lookup failed is not queued again on the next emission`() = runTest(testDispatcher) {
        val worldRepository = mock<WorldRepository>()
        whenever(worldRepository.getWorld(any(), any())).thenThrow(RuntimeException("world unavailable"))
        val friends = MutableStateFlow(mapOf("a" to onlineFriendAt("a", "Alice")))
        buildViewModel(friends = friends, worldRepository = worldRepository)
        advanceUntilIdle()

        // A later emission carrying the same world must not re-enqueue the lookup.
        friends.value = friends.value + ("b" to onlineFriendAt("b", "Bob"))
        advanceUntilIdle()

        verify(worldRepository, times(1)).getWorld(eq("wrld_pug"), any())
    }

    private fun onlineFriendAt(id: String, name: String) = FriendContext(
        id = id,
        name = name,
        state = FriendState.ONLINE,
        ref = VrcUser(id = id, displayName = name, location = "wrld_pug:12345"),
    )

    private fun buildViewModel(
        friends: MutableStateFlow<Map<String, FriendContext>> = MutableStateFlow(emptyMap()),
        worldRepository: WorldRepository = mock(),
    ): FriendsLocationsViewModel {
        val authRepository = mock<AuthRepository>().also {
            whenever(it.authState).thenReturn(
                MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_me"))),
            )
        }
        val friendRepository = mock<FriendRepository>().also {
            whenever(it.friends).thenReturn(friends)
            whenever(it.favoriteFriendIds).thenReturn(MutableStateFlow(emptySet()))
        }
        val feedRepository = mock<FeedRepository>().also {
            whenever(it.getGpsFeed(any(), any())).thenReturn(flowOf(emptyList()))
        }
        return FriendsLocationsViewModel(authRepository, friendRepository, worldRepository, feedRepository)
    }
}
