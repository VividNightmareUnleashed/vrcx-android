package com.vrcx.android.ui.screen.avatars

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val dispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `toggle removes the favorite the refresh just revealed`() = runTest(dispatcher) {
        val favorites = MutableStateFlow(emptyList<Favorite>())
        val favoriteRepository = mock<FavoriteRepository>()
        whenever(favoriteRepository.favorites).thenReturn(favorites)
        // The avatar is already favorited on another device, so loadFavorites is
        // what reveals it. The toggle has to act on that, not on the observer's
        // mirror, which has not resumed yet when loadFavorites returns.
        whenever(favoriteRepository.loadFavorites("avatar")).thenAnswer {
            favorites.value = listOf(Favorite(favoriteId = "avtr_1", id = "fvrt_1", type = "avatar"))
            Unit
        }
        val viewModel = buildViewModel(favoriteRepository)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        verify(favoriteRepository).deleteFavorite("fvrt_1")
        verify(favoriteRepository, never()).addFavorite("avatar", "avtr_1")
        assertEquals("Removed from favorites", viewModel.message.value)
    }

    @Test
    fun `toggle adds a favorite when the refresh finds none`() = runTest(dispatcher) {
        val favoriteRepository = mock<FavoriteRepository>()
        whenever(favoriteRepository.favorites).thenReturn(MutableStateFlow(emptyList()))
        val viewModel = buildViewModel(favoriteRepository)
        advanceUntilIdle()

        viewModel.toggleFavorite()
        advanceUntilIdle()

        verify(favoriteRepository).addFavorite("avatar", "avtr_1")
        assertEquals("Added to favorites", viewModel.message.value)
        assertNull(viewModel.favoriteEntryId.value)
    }

    private suspend fun buildViewModel(favoriteRepository: FavoriteRepository): AvatarDetailViewModel {
        val avatarRepository = mock<AvatarRepository>()
        whenever(avatarRepository.getAvatar("avtr_1", forceRefresh = true))
            .thenReturn(Avatar(id = "avtr_1", name = "Test Avatar"))
        return AvatarDetailViewModel(
            SavedStateHandle(mapOf("avatarId" to "avtr_1")),
            avatarRepository,
            favoriteRepository,
        )
    }
}
