package com.vrcx.android.ui.screen.playerlist

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerListViewModelTest {

    @Test
    fun `scope and query publish with the rows derived from them`() = runTest {
        val location = "wrld_one:instance"
        val authRepository = mock<AuthRepository>().also {
            whenever(it.authState).thenReturn(
                MutableStateFlow(AuthState.LoggedIn(CurrentUser(location = location))),
            )
        }
        val friendRepository = mock<FriendRepository>().also {
            whenever(it.friends).thenReturn(
                MutableStateFlow(
                    mapOf(
                        "alice" to friend("alice", "Alice", location),
                        "bob" to friend("bob", "Bob", "wrld_two:instance"),
                    ),
                ),
            )
            whenever(it.favoriteFriendIds).thenReturn(MutableStateFlow(emptySet()))
        }
        val viewModel = PlayerListViewModel(
            authRepository,
            friendRepository,
            StandardTestDispatcher(testScheduler),
        )

        val instance = viewModel.state.first { it.players.isNotEmpty() }
        assertEquals(PlayerListScope.SAME_INSTANCE, instance.scope)
        assertEquals(listOf("Alice"), instance.players.map { it.name })

        viewModel.selectScope(PlayerListScope.FRIENDS)
        viewModel.updateSearch("B")
        viewModel.updateSearch("Bob")
        assertEquals(PlayerListScope.FRIENDS, viewModel.controls.value.scope)
        assertEquals("Bob", viewModel.controls.value.query)

        val searched = viewModel.state.first {
            it.scope == PlayerListScope.FRIENDS && it.searchQuery == "Bob"
        }
        assertEquals(listOf("Bob"), searched.players.map { it.name })
    }

    private fun friend(id: String, name: String, location: String) = FriendContext(
        id = id,
        name = name,
        state = FriendState.ONLINE,
        ref = VrcUser(id = id, displayName = name, location = location),
    )
}
