package com.vrcx.android.ui.screen.favorites

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `default tab loads alone and later tabs load once on selection`() = runTest(testDispatcher) {
        val favoriteApi = successfulFavoriteApi()
        val viewModel = buildViewModel(favoriteApi)

        advanceUntilIdle()

        verify(favoriteApi).getFavorites(100, 0, "friend", null)
        verify(favoriteApi, never()).getFavorites(100, 0, "world", null)
        verify(favoriteApi, never()).getFavorites(100, 0, "avatar", null)
        verify(favoriteApi, never()).getFavoriteWorlds(100, 0, null, null, null)
        verify(favoriteApi, never()).getFavoriteAvatars(100, 0, null)

        viewModel.selectTab(FavoritesTab.WORLDS)
        viewModel.selectTab(FavoritesTab.WORLDS)
        advanceUntilIdle()
        viewModel.selectTab(FavoritesTab.FRIENDS)
        viewModel.selectTab(FavoritesTab.WORLDS)
        advanceUntilIdle()

        verify(favoriteApi, times(1)).getFavorites(100, 0, "world", null)
        verify(favoriteApi, times(1)).getFavorites(100, 0, "vrcPlusWorld", null)
        verify(favoriteApi, times(1)).getFavoriteWorlds(100, 0, null, null, null)

        viewModel.selectTab(FavoritesTab.AVATARS)
        viewModel.selectTab(FavoritesTab.AVATARS)
        advanceUntilIdle()

        verify(favoriteApi, times(1)).getFavorites(100, 0, "avatar", null)
        verify(favoriteApi, times(1)).getFavoriteAvatars(100, 0, null)
        verify(favoriteApi, times(1)).getFavoriteGroups(50, 0, null, null)
    }

    @Test
    fun `refresh reloads selected favorites only`() = runTest(testDispatcher) {
        val favoriteApi = successfulFavoriteApi()
        val viewModel = buildViewModel(favoriteApi)
        advanceUntilIdle()

        viewModel.selectTab(FavoritesTab.WORLDS)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        verify(favoriteApi, times(1)).getFavorites(100, 0, "friend", null)
        verify(favoriteApi, times(2)).getFavorites(100, 0, "world", null)
        verify(favoriteApi, times(2)).getFavorites(100, 0, "vrcPlusWorld", null)
        verify(favoriteApi, times(2)).getFavoriteWorlds(100, 0, null, null, null)
        verify(favoriteApi, never()).getFavorites(100, 0, "avatar", null)
        verify(favoriteApi, never()).getFavoriteAvatars(100, 0, null)
    }

    @Test
    fun `failed tab stays independent and retries without loading other tabs`() = runTest(testDispatcher) {
        val favoriteApi = successfulFavoriteApi()
        whenever(favoriteApi.getFavorites(100, 0, "friend", null))
            .thenThrow(RuntimeException("friends unavailable"))
            .thenReturn(emptyList())
        val viewModel = buildViewModel(favoriteApi)
        advanceUntilIdle()

        val failed = viewModel.uiState.value.tabs.getValue(FavoritesTab.FRIENDS)
        assertFalse(failed.isLoaded)
        assertEquals("Failed to load friends favorites", failed.error)
        assertFalse(viewModel.uiState.value.tabs.getValue(FavoritesTab.WORLDS).isLoaded)

        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedTabState.isLoaded)
        assertEquals(null, viewModel.uiState.value.selectedTabState.error)
        verify(favoriteApi, times(2)).getFavorites(100, 0, "friend", null)
        verify(favoriteApi, never()).getFavorites(100, 0, "world", null)
        verify(favoriteApi, never()).getFavoriteWorlds(100, 0, null, null, null)
    }

    @Test
    fun `friend favorite resolution reacts to friend status changes`() = runTest(testDispatcher) {
        val favoriteApi = successfulFavoriteApi()
        whenever(favoriteApi.getFavorites(100, 0, "friend", null)).thenReturn(
            listOf(Favorite(id = "fav_friend", favoriteId = "usr_friend", type = "friend")),
        )
        val friends = MutableStateFlow(
            mapOf(
                "usr_friend" to FriendContext(
                    id = "usr_friend",
                    name = "Old name",
                    state = FriendState.OFFLINE,
                    ref = VrcUser(id = "usr_friend", displayName = "Old name", status = "offline"),
                )
            )
        )
        val friendRepository = mock<FriendRepository>()
        whenever(friendRepository.friends).thenReturn(friends)
        val viewModel = FavoritesViewModel(
            favoriteRepository = FavoriteRepository(favoriteApi, mock(), mock()),
            friendRepository = friendRepository,
            userRepository = mock(),
        )
        advanceUntilIdle()
        assertEquals("Old name", viewModel.resolvedFavorites.value.single().name)
        assertEquals(FriendState.OFFLINE, viewModel.resolvedFavorites.value.single().friendState)

        friends.value = mapOf(
            "usr_friend" to FriendContext(
                id = "usr_friend",
                name = "New name",
                state = FriendState.ONLINE,
                ref = VrcUser(id = "usr_friend", displayName = "New name", status = "active"),
            )
        )
        advanceUntilIdle()

        assertEquals("New name", viewModel.resolvedFavorites.value.single().name)
        assertEquals(FriendState.ONLINE, viewModel.resolvedFavorites.value.single().friendState)
        assertEquals("active", viewModel.resolvedFavorites.value.single().friendStatus)
    }

    private suspend fun successfulFavoriteApi(): FavoriteApi {
        val favoriteApi = mock<FavoriteApi>()
        whenever(favoriteApi.getFavorites(100, 0, "friend", null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavorites(100, 0, "world", null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavorites(100, 0, "vrcPlusWorld", null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavorites(100, 0, "avatar", null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavoriteGroups(50, 0, null, null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavoriteWorlds(100, 0, null, null, null)).thenReturn(emptyList())
        whenever(favoriteApi.getFavoriteAvatars(100, 0, null)).thenReturn(emptyList())
        return favoriteApi
    }

    private fun buildViewModel(favoriteApi: FavoriteApi): FavoritesViewModel {
        val friendRepository = mock<FriendRepository>()
        whenever(friendRepository.friends).thenReturn(
            MutableStateFlow<Map<String, FriendContext>>(emptyMap()),
        )
        return FavoritesViewModel(
            favoriteRepository = FavoriteRepository(
                favoriteApi = favoriteApi,
                worldApi = mock<WorldApi>(),
                avatarApi = mock<AvatarApi>(),
            ),
            friendRepository = friendRepository,
            userRepository = mock<UserRepository>(),
        )
    }
}
