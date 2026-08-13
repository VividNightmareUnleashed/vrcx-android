package com.vrcx.android.ui.screen.moderation

import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationScreenTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `tab strip carries the API type strings the repository filters on`() {
        assertEquals(
            listOf("block", "mute", "hideAvatar", "showAvatar", "interactOff", "interactOn"),
            MODERATION_TABS.map { it.type },
        )
    }

    @Test
    fun `every tab has a strip position, so the selection needs no fallback`() {
        MODERATION_TABS.forEachIndexed { index, tab ->
            assertEquals(index, MODERATION_TABS.indexOf(tab))
        }
    }

    @Test
    fun `a first load that fails is an error page, not a snackbar`() = runTest(testDispatcher) {
        val api = mock<PlayerModerationApi>()
        whenever(api.getPlayerModerations()).thenThrow(RuntimeException("moderations unavailable"))

        val viewModel = buildViewModel(api)
        advanceUntilIdle()

        assertEquals(LoadState.Failed("moderations unavailable"), viewModel.screenState.value.load)
        assertEquals(null, viewModel.screenState.value.staleError)
    }

    @Test
    fun `a failed removal reaches the snackbar over the rows it left on screen`() =
        runTest(testDispatcher) {
            val api = mock<PlayerModerationApi>()
            whenever(api.getPlayerModerations()).thenReturn(emptyList())
            whenever(api.unmoderatePlayer(any()))
                .thenThrow(RuntimeException("removal rejected"))

            val viewModel = buildViewModel(api)
            advanceUntilIdle()

            viewModel.remove(PlayerModeration(id = "mod_1", targetUserId = "usr_1", type = "block"))
            advanceUntilIdle()

            assertEquals("removal rejected", viewModel.screenState.value.staleError)

            viewModel.consumeError()
            assertEquals(null, viewModel.screenState.value.staleError)
        }

    @Test
    fun `the visible rows and the tab counts are derived from one snapshot`() =
        runTest(testDispatcher) {
            val api = mock<PlayerModerationApi>()
            whenever(api.getPlayerModerations()).thenReturn(
                listOf(
                    PlayerModeration(id = "m1", targetDisplayName = "Alice", type = "block"),
                    PlayerModeration(id = "m2", targetDisplayName = "Bob", type = "block"),
                    PlayerModeration(id = "m3", targetDisplayName = "Cara", type = "mute"),
                ),
            )
            val viewModel = buildViewModel(api)
            advanceUntilIdle()

            // Two rapid criteria changes: whatever settles has to be self-consistent.
            viewModel.selectTab(MODERATION_TABS.first { it.type == "mute" })
            viewModel.updateSearch("car")

            val settled = viewModel.moderations.first { it.visible.size == 1 }
            assertEquals(listOf("Cara"), settled.visible.map { it.targetDisplayName })
            assertEquals(mapOf("block" to 2, "mute" to 1), settled.countsByType)
        }

    private fun buildViewModel(api: PlayerModerationApi) =
        ModerationViewModel(ModerationRepository(api, AccountScope()))
}
