package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.World
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FavoriteRepositoryTest {

    private val favoriteApi = mock<FavoriteApi>()
    private val worldApi = mock<WorldApi>()
    private val avatarApi = mock<AvatarApi>()
    private val accountScope = AccountScope()
    private val repository = FavoriteRepository(
        favoriteApi = favoriteApi,
        worldApi = worldApi,
        avatarApi = avatarApi,
        accountScope = accountScope,
    )

    @Test
    fun `late add cannot repopulate favorites after account clear`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(favoriteApi.addFavorite(any())).doSuspendableAnswer {
            requestStarted.complete(Unit)
            releaseRequest.await()
            Favorite(id = "fav_old", favoriteId = "wrld_old", type = "world")
        }

        val add = async(start = CoroutineStart.UNDISPATCHED) {
            repository.addFavorite("world", "wrld_old", tags = listOf("worlds1"))
        }
        requestStarted.await()
        accountScope.invalidate()
        releaseRequest.complete(Unit)
        val failure = runCatching { add.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(repository.favorites.value.isEmpty())
        assertTrue(repository.favoriteWorlds.value.isEmpty())
    }

    @Test
    fun `loadFavoriteWorldsBulk paginates the bulk endpoint and exposes the result`() {
        runBlocking {
            whenever(favoriteApi.getFavoriteWorlds(eq(100), eq(0), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(List(100) { stubWorld("wrld_$it") })
            whenever(favoriteApi.getFavoriteWorlds(eq(100), eq(100), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(stubWorld("wrld_100"), stubWorld("wrld_101")))

            repository.loadFavoriteWorldsBulk()

            assertEquals(102, repository.favoriteWorlds.value.size)
        }
    }

    @Test
    fun `loadFavoriteWorldsBulk skips work on the second call unless forceRefresh is true`() {
        runBlocking {
            whenever(favoriteApi.getFavoriteWorlds(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(emptyList())

            repository.loadFavoriteWorldsBulk()
            repository.loadFavoriteWorldsBulk()
            verify(favoriteApi, times(1)).getFavoriteWorlds(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())

            repository.loadFavoriteWorldsBulk(forceRefresh = true)
            verify(favoriteApi, times(2)).getFavoriteWorlds(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `forceRefresh of one favorites type leaves the bulk world cache loaded`() {
        runBlocking {
            whenever(favoriteApi.getFavorites(any(), any(), eq("world"), anyOrNull()))
                .thenReturn(emptyList())
            whenever(favoriteApi.getFavoriteWorlds(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(emptyList())

            repository.loadFavorites(type = "world")
            repository.loadFavoriteWorldsBulk()
            repository.loadFavorites(type = "world", forceRefresh = true)
            repository.loadFavoriteWorldsBulk()

            verify(favoriteApi, times(2)).getFavorites(any(), any(), eq("world"), anyOrNull())
            verify(favoriteApi, times(1))
                .getFavoriteWorlds(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `loadFavoriteAvatarsBulk pulls the avatar bulk endpoint`() {
        runBlocking {
            whenever(favoriteApi.getFavoriteAvatars(any(), any(), anyOrNull()))
                .thenReturn(listOf(stubAvatar("avtr_a"), stubAvatar("avtr_b")))

            repository.loadFavoriteAvatarsBulk()

            assertEquals(2, repository.favoriteAvatars.value.size)
            assertEquals(setOf("avtr_a", "avtr_b"), repository.favoriteAvatars.value.map { it.id }.toSet())
        }
    }

    private fun stubWorld(id: String): World = World(
        id = id,
        name = "World $id",
        authorId = "usr_owner",
        authorName = "Owner",
    )

    private fun stubAvatar(id: String): Avatar = Avatar(
        id = id,
        name = "Avatar $id",
        authorId = "usr_owner",
        authorName = "Owner",
    )
}
