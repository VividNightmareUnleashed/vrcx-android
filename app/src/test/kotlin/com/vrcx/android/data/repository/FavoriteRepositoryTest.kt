package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.db.dao.FavoriteLocalDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FavoriteRepositoryTest {

    private val favoriteApi = mock<FavoriteApi>()
    private val favoriteLocalDao = mock<FavoriteLocalDao>()
    private val repository = FavoriteRepository(
        favoriteApi = favoriteApi,
        favoriteLocalDao = favoriteLocalDao,
    )

    @Test
    fun `loadFavoriteWorldsBulk paginates the bulk endpoint and exposes the result`() {
        runBlocking {
            // BulkPaginator passes (offset, pageSize=100) and a null tag default.
            whenever(favoriteApi.getFavoriteWorlds(eq(100), eq(0), anyOrNull()))
                .thenReturn(List(100) { stubWorld("wrld_$it") })
            whenever(favoriteApi.getFavoriteWorlds(eq(100), eq(100), anyOrNull()))
                .thenReturn(listOf(stubWorld("wrld_100"), stubWorld("wrld_101")))

            repository.loadFavoriteWorldsBulk()

            assertEquals(102, repository.favoriteWorlds.value.size)
        }
    }

    @Test
    fun `loadFavoriteWorldsBulk skips work on the second call unless forceRefresh is true`() {
        runBlocking {
            whenever(favoriteApi.getFavoriteWorlds(any(), any(), anyOrNull())).thenReturn(emptyList())

            repository.loadFavoriteWorldsBulk()
            repository.loadFavoriteWorldsBulk()
            // The first call ran one paginated request; the second short-circuits.
            verify(favoriteApi, times(1)).getFavoriteWorlds(any(), any(), anyOrNull())

            repository.loadFavoriteWorldsBulk(forceRefresh = true)
            verify(favoriteApi, times(2)).getFavoriteWorlds(any(), any(), anyOrNull())
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

    @Test
    fun `dropFavoriteWorldFromCache removes the matching entry without re-fetching`() {
        runBlocking {
            whenever(favoriteApi.getFavoriteWorlds(any(), any(), anyOrNull()))
                .thenReturn(listOf(stubWorld("wrld_a"), stubWorld("wrld_b")))
            repository.loadFavoriteWorldsBulk()

            repository.dropFavoriteWorldFromCache("wrld_a")

            assertEquals(listOf("wrld_b"), repository.favoriteWorlds.value.map { it.id })
            // No extra fetch was triggered by the cache drop.
            verify(favoriteApi, never()).getFavoriteWorlds(eq(100), eq(2), anyOrNull())
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
