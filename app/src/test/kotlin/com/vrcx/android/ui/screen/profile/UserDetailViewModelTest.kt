package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FavoriteWorldLoadResult
import com.vrcx.android.data.repository.ProfilePreferenceActions
import com.vrcx.android.data.repository.UserActionPerformer
import com.vrcx.android.data.repository.UserDetailProfile
import com.vrcx.android.data.repository.UserDetailRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val dispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `tabs load lazily once and publish content atomically`() = runTest(dispatcher) {
        val repository = mock<UserDetailRepository>()
        val favoriteRepository = favoriteRepository()
        val releaseMutuals = CompletableDeferred<Unit>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        whenever(repository.loadMutualFriends("usr_target")).doSuspendableAnswer {
            releaseMutuals.await()
            listOf(VrcUser(id = "usr_mutual", displayName = "Mutual"))
        }
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            mock(),
            mock(),
            favoriteRepository,
        )
        advanceUntilIdle()

        verify(favoriteRepository).loadFavorites(type = "friend")
        verify(repository, never()).loadGroups("usr_target")
        verify(repository, never()).loadWorlds("usr_target")
        verify(repository, never()).loadAvatars("usr_target")

        viewModel.selectTab(UserDetailTab.MUTUALS)
        viewModel.selectTab(UserDetailTab.MUTUALS)
        runCurrent()

        assertTrue(UserDetailTab.MUTUALS in viewModel.uiState.value.loadingTabs)
        assertTrue(viewModel.uiState.value.mutualFriends.isEmpty())

        releaseMutuals.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("usr_mutual"), state.mutualFriends.map { it.id })
        assertTrue(UserDetailTab.MUTUALS in state.loadedTabs)
        assertFalse(UserDetailTab.MUTUALS in state.loadingTabs)

        viewModel.selectTab(UserDetailTab.INFO)
        viewModel.selectTab(UserDetailTab.MUTUALS)
        advanceUntilIdle()
        verify(repository, times(1)).loadMutualFriends("usr_target")
    }

    @Test
    fun `favorite worlds remain retryable after every group request fails`() = runTest(dispatcher) {
        val repository = mock<UserDetailRepository>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        whenever(repository.loadFavoriteWorlds("usr_target"))
            .thenThrow(IllegalStateException("No favorite world groups could be loaded"))
            .thenReturn(FavoriteWorldLoadResult(emptyList()))
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            mock(),
            mock(),
            favoriteRepository(),
        )
        advanceUntilIdle()

        viewModel.selectTab(UserDetailTab.FAVORITE_WORLDS)
        advanceUntilIdle()
        assertFalse(UserDetailTab.FAVORITE_WORLDS in viewModel.uiState.value.loadedTabs)

        viewModel.selectTab(UserDetailTab.FAVORITE_WORLDS)
        advanceUntilIdle()
        assertTrue(UserDetailTab.FAVORITE_WORLDS in viewModel.uiState.value.loadedTabs)
        verify(repository, times(2)).loadFavoriteWorlds("usr_target")
    }

    @Test
    fun `unfriending drops the tab rows the account may no longer see`() = runTest(dispatcher) {
        val repository = mock<UserDetailRepository>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        whenever(repository.loadMutualFriends("usr_target"))
            .thenReturn(listOf(VrcUser(id = "usr_mutual", displayName = "Mutual")))
            .thenReturn(emptyList())
        val actionPerformer = mock<UserActionPerformer>()
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            actionPerformer,
            mock(),
            favoriteRepository(),
        )
        advanceUntilIdle()

        viewModel.selectTab(UserDetailTab.MUTUALS)
        advanceUntilIdle()
        assertEquals(listOf("usr_mutual"), viewModel.uiState.value.mutualFriends.map { it.id })

        viewModel.unfriend()
        advanceUntilIdle()

        verify(actionPerformer).unfriend("usr_target")
        verify(repository, times(2)).loadMutualFriends("usr_target")
        assertTrue(viewModel.uiState.value.mutualFriends.isEmpty())
    }

    @Test
    fun `a double-tapped favorite issues one request`() = runTest(dispatcher) {
        val repository = mock<UserDetailRepository>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        val profilePreferenceActions = mock<ProfilePreferenceActions>()
        val release = CompletableDeferred<Unit>()
        whenever(profilePreferenceActions.addFriendFavorite("usr_target")).doSuspendableAnswer {
            release.await()
        }
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            mock(),
            profilePreferenceActions,
            favoriteRepository(),
        )
        advanceUntilIdle()

        viewModel.toggleFavorite()
        runCurrent()
        viewModel.toggleFavorite()
        release.complete(Unit)
        advanceUntilIdle()

        verify(profilePreferenceActions, times(1)).addFriendFavorite("usr_target")
        assertEquals("Added to favorites", viewModel.uiState.value.message)
    }

    @Test
    fun `the favorite entry id is the single source of the favorited flag`() = runTest(dispatcher) {
        val favorites = MutableStateFlow(emptyList<Favorite>())
        val repository = mock<UserDetailRepository>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        val profilePreferenceActions = mock<ProfilePreferenceActions>()
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            mock(),
            profilePreferenceActions,
            favoriteRepository(favorites),
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFavorited)

        favorites.value = listOf(
            Favorite(
                favoriteId = "usr_target",
                id = "fvrt_1",
                type = "friend",
            ),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFavorited)

        viewModel.toggleFavorite()
        advanceUntilIdle()
        verify(profilePreferenceActions).deleteFavorite("fvrt_1")
    }

    @Test
    fun `notification preference is updated through the profile action owner`() = runTest(dispatcher) {
        val repository = mock<UserDetailRepository>()
        whenever(repository.observeIsSelf("usr_target")).thenReturn(flowOf(false))
        whenever(repository.loadProfile("usr_target")).thenReturn(
            UserDetailProfile(VrcUser(id = "usr_target", displayName = "Target")),
        )
        val profilePreferenceActions = mock<ProfilePreferenceActions>()
        whenever(profilePreferenceActions.toggleNotify("usr_target")).thenReturn(true)
        val viewModel = UserDetailViewModel(
            SavedStateHandle(mapOf("userId" to "usr_target")),
            repository,
            mock(),
            profilePreferenceActions,
            favoriteRepository(),
        )
        advanceUntilIdle()

        viewModel.toggleNotify()
        advanceUntilIdle()

        verify(profilePreferenceActions).toggleNotify("usr_target")
        assertTrue(viewModel.uiState.value.notifyEnabled)
        assertEquals("Notifications enabled", viewModel.uiState.value.message)
    }

    private fun favoriteRepository(
        favorites: MutableStateFlow<List<Favorite>> = MutableStateFlow(emptyList()),
    ): FavoriteRepository = mock<FavoriteRepository>().also { repository ->
        whenever(repository.favorites).thenReturn(favorites)
    }
}
