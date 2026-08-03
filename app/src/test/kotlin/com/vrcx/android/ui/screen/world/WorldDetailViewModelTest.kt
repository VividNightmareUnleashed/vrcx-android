package com.vrcx.android.ui.screen.world

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.repository.WorldRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `selfInvite forwards worldId and instanceId to the repository`() = runTest(testDispatcher) {
        val repo = mock<WorldRepository>()
        val vm = buildViewModel(worldId = "wrld_xyz", repository = repo)

        vm.selfInvite("12345~public")
        advanceUntilIdle()

        verify(repo).selfInvite(eq("wrld_xyz"), eq("12345~public"))
    }

    private fun buildViewModel(
        worldId: String,
        repository: WorldRepository = mock(),
    ): WorldDetailViewModel {
        val handle = SavedStateHandle(mapOf("worldId" to worldId))
        return WorldDetailViewModel(handle, repository)
    }
}
