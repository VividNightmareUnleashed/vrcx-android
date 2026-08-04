package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.repository.FavoriteWorldLoadResult
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
        val favorites = MutableStateFlow(emptyList<com.vrcx.android.data.api.model.Favorite>())
        val releaseMutuals = CompletableDeferred<Unit>()
        whenever(repository.favorites).thenReturn(favorites)
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
        )
        advanceUntilIdle()

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
        whenever(repository.favorites).thenReturn(
            MutableStateFlow(emptyList<com.vrcx.android.data.api.model.Favorite>()),
        )
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
}
