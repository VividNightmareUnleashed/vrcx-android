package com.vrcx.android.ui.screen.dashboard

import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
            )
        )
        val viewModel = buildViewModel(friends = friends)

        testDispatcher.scheduler.runCurrent()

        val counts = viewModel.friendCounts.first { it != Triple(0, 0, 0) }
        assertEquals(Triple(2, 1, 3), counts)
    }

    @Test
    fun `friendCounts updates when the upstream flow emits a new map`() = runTest(testDispatcher) {
        val friends = MutableStateFlow<Map<String, FriendContext>>(emptyMap())
        val viewModel = buildViewModel(friends = friends)

        testDispatcher.scheduler.runCurrent()
        // No friends yet — counts stay at the initial value.
        assertEquals(Triple(0, 0, 0), viewModel.friendCounts.value)

        friends.value = mapOf("x" to FriendContext("x", "X", FriendState.ONLINE))
        val updated = viewModel.friendCounts.first { it != Triple(0, 0, 0) }
        assertEquals(Triple(1, 0, 0), updated)
    }

    private fun buildViewModel(
        friends: MutableStateFlow<Map<String, FriendContext>> = MutableStateFlow(emptyMap()),
    ): DashboardViewModel {
        val authRepository = mock<AuthRepository>().also {
            whenever(it.authState).thenReturn(MutableStateFlow(AuthState.NotLoggedIn))
        }
        val friendRepository = mock<FriendRepository>().also {
            whenever(it.friends).thenReturn(friends)
        }
        val feedRepository = mock<FeedRepository>().also {
            whenever(it.getGpsFeed(any(), any())).thenReturn(flowOf(emptyList()))
            whenever(it.getStatusFeed(any(), any())).thenReturn(flowOf(emptyList()))
            whenever(it.getBioFeed(any(), any())).thenReturn(flowOf(emptyList()))
            whenever(it.getAvatarFeed(any(), any())).thenReturn(flowOf(emptyList()))
            whenever(it.getOnlineOfflineFeed(any(), any())).thenReturn(flowOf(emptyList()))
        }
        return DashboardViewModel(authRepository, friendRepository, feedRepository)
    }
}
