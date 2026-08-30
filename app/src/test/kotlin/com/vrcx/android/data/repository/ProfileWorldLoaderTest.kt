package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.World
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProfileWorldLoaderTest {
    @Test
    fun `profile worlds paginate through every available result`() = runTest {
        val worldApi = mock<WorldApi>()
        whenever(worldApi.getWorlds(n = 100, offset = 0, user = "usr_target")).thenReturn(
            List(100) { World(id = "wrld_$it") },
        )
        whenever(worldApi.getWorlds(n = 100, offset = 100, user = "usr_target")).thenReturn(
            listOf(World(id = "wrld_100")),
        )

        val worlds = loader(worldApi = worldApi).loadWorlds("usr_target")

        assertEquals(101, worlds.size)
        assertEquals("wrld_100", worlds.last().id)
    }

    @Test
    fun `favorite world loading keeps successful groups when another group fails`() = runTest {
        val favoriteApi = mock<FavoriteApi>()
        whenever(favoriteApi.getFavoriteGroups(100, 0, "world", "usr_target")).thenReturn(
            listOf(
                FavoriteGroup(name = "worlds1", displayName = "First", type = "world"),
                FavoriteGroup(name = "worlds2", displayName = "Second", type = "world"),
            ),
        )
        whenever(favoriteApi.getFavoriteWorlds(100, 0, "worlds1", "usr_target", null)).thenReturn(
            listOf(World(id = "wrld_one", name = "One")),
        )
        whenever(favoriteApi.getFavoriteWorlds(100, 0, "worlds2", "usr_target", null))
            .thenThrow(RuntimeException("group unavailable"))

        val result = loader(favoriteApi = favoriteApi).loadFavoriteWorlds("usr_target")

        assertEquals(listOf("worlds1"), result.sections.map { it.tag })
        assertEquals("Some favorite world groups could not be loaded", result.warning)
    }

    @Test
    fun `favorite world loading fails when every group request fails`() = runTest {
        val favoriteApi = mock<FavoriteApi>()
        whenever(favoriteApi.getFavoriteGroups(100, 0, "world", "usr_target")).thenReturn(
            listOf(
                FavoriteGroup(name = "worlds1", type = "world"),
                FavoriteGroup(name = "worlds2", type = "world"),
            ),
        )
        whenever(favoriteApi.getFavoriteWorlds(100, 0, "worlds1", "usr_target", null))
            .thenThrow(RuntimeException("first unavailable"))
        whenever(favoriteApi.getFavoriteWorlds(100, 0, "worlds2", "usr_target", null))
            .thenThrow(RuntimeException("second unavailable"))

        val failure = runCatching {
            loader(favoriteApi = favoriteApi).loadFavoriteWorlds("usr_target")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun loader(worldApi: WorldApi = mock(), favoriteApi: FavoriteApi = mock()) =
        ProfileWorldLoader(worldApi = worldApi, favoriteApi = favoriteApi)
}
