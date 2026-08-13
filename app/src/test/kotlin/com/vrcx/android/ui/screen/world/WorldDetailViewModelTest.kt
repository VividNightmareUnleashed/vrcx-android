package com.vrcx.android.ui.screen.world

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorldDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `browserLaunchUrl includes both worldId and instanceId in canonical form`() {
        val vm = buildViewModel(worldId = "wrld_xyz")

        val url = vm.browserLaunchUrl("12345~public")

        assertEquals(
            "https://vrchat.com/home/launch?worldId=wrld_xyz&instanceId=12345~public",
            url,
        )
    }

    @Test
    fun `browserLaunchUrl leaves the VRChat id charset alone and escapes the rest`() {
        val vm = buildViewModel(worldId = "wrld_xyz")

        assertEquals(
            "https://vrchat.com/home/launch?worldId=wrld_xyz" +
                "&instanceId=12345~region(use)~group(grp_1),2:3",
            vm.browserLaunchUrl("12345~region(use)~group(grp_1),2:3"),
        )
        assertEquals(
            "https://vrchat.com/home/launch?worldId=wrld_xyz&instanceId=12345%26evil%3Dx",
            vm.browserLaunchUrl("12345&evil=x"),
        )
    }

    @Test
    fun `selfInvite forwards worldId and instanceId to the repository`() = runTest(testDispatcher) {
        val repo = mock<WorldRepository>()
        val vm = buildViewModel(worldId = "wrld_xyz", repository = repo)

        vm.selfInvite("12345~public")
        advanceUntilIdle()

        verify(repo).selfInvite(eq("wrld_xyz"), eq("12345~public"))
    }

    @Test
    fun `a world that loads keeps the screen when its instances do not`() = runTest(testDispatcher) {
        val world = World(id = "wrld_xyz", name = "Test World")
        val repo = mock<WorldRepository>()
        whenever(repo.getWorld("wrld_xyz", forceRefresh = true)).thenReturn(world)
        whenever(repo.parseInstanceIds(world)).thenReturn(listOf("12345"))
        whenever(repo.getInstances("wrld_xyz", listOf("12345")))
            .thenThrow(RuntimeException("instances unavailable"))

        val vm = buildViewModel(worldId = "wrld_xyz", repository = repo)
        advanceUntilIdle()

        val state = vm.state.value as LoadState.Loaded
        assertEquals(world, state.value.world)
        assertEquals(emptyList<Any>(), state.value.instances)
        // Renderable beside the world, rather than a message no branch can show.
        assertEquals("instances unavailable", state.staleError)
    }

    @Test
    fun `a world that never loads is a failure with nothing behind it`() = runTest(testDispatcher) {
        val repo = mock<WorldRepository>()
        whenever(repo.getWorld("wrld_xyz", forceRefresh = true))
            .thenThrow(RuntimeException("world unavailable"))

        val vm = buildViewModel(worldId = "wrld_xyz", repository = repo)
        advanceUntilIdle()

        assertEquals(LoadState.Failed("world unavailable"), vm.state.value)
    }

    private fun buildViewModel(
        worldId: String,
        repository: WorldRepository = mock(),
    ): WorldDetailViewModel {
        val handle = SavedStateHandle(mapOf("worldId" to worldId))
        return WorldDetailViewModel(handle, repository)
    }
}
