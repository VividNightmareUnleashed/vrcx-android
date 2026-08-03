package com.vrcx.android.ui.screen.search

import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.SearchRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `canceled user search does not publish failed or completed state`() = runTest(testDispatcher) {
        val repository = mock<SearchRepository>()
        whenever(
            repository.searchUsers(
                query = any(),
                n = any(),
                offset = any(),
                searchByBio = any(),
                sortByLastLogin = any(),
            ),
        ).thenThrow(CancellationException("old search canceled"))
        val viewModel = SearchViewModel(repository)

        viewModel.updateQuery("ab")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasSearched)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `filtered world paging continues from the prior source offset`() = runTest(testDispatcher) {
        val repository = mock<SearchRepository>()
        val requestedOffsets = mutableListOf<Int>()
        whenever(
            repository.searchWorlds(
                query = any(),
                n = any(),
                offset = any(),
                mode = any(),
                includeLabs = any(),
                tag = any(),
            ),
        ).thenAnswer { invocation ->
            val offset = invocation.getArgument<Int>(2)
            requestedOffsets += offset
            (0 until 50).map { index ->
                World(
                    id = "wrld_${offset}_$index",
                    name = if (index < 10) "Needle $index" else "Other $index",
                )
            }
        }
        val viewModel = SearchViewModel(repository)

        viewModel.selectTab(SearchTab.WORLDS)
        viewModel.updateQuery("needle")
        viewModel.setWorldMode(WorldSearchMode.ACTIVE)
        advanceUntilIdle()
        viewModel.nextPage()
        advanceUntilIdle()

        assertEquals("state=${viewModel.uiState.value}", listOf(0, 50, 100), requestedOffsets)
        assertEquals(10, viewModel.uiState.value.worlds.size)
    }
}
