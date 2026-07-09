package com.vrcx.android.ui.screen.search

import com.vrcx.android.data.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

        assertNull(viewModel.error.value)
        assertFalse(viewModel.hasSearched.value)
        assertFalse(viewModel.isSearching.value)
    }
}
