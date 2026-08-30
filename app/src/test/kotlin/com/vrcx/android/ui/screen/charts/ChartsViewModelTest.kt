package com.vrcx.android.ui.screen.charts

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChartsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    private val recentVisits = gpsRows(count = 4, daysAgo = 1, world = "Recent World")
    private val oldVisits = gpsRows(count = 6, daysAgo = 60, world = "Old World")

    @Test
    fun `narrowing the range republishes every series from the same snapshot`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(recentVisits + oldVisits)
        advanceUntilIdle()

        viewModel.setRangeDays(null)
        val all = viewModel.state.awaitVisits(recentVisits.size + oldVisits.size)
        assertConsistent(all)

        viewModel.setRangeDays(7)
        val narrowed = viewModel.state.awaitVisits(recentVisits.size)
        assertConsistent(narrowed)
        assertEquals(listOf("Recent World"), narrowed.topWorlds.map { it.first })
    }

    @Test
    fun `two rapid range changes settle on one internally consistent derivation`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(recentVisits + oldVisits)
        advanceUntilIdle()

        viewModel.setRangeDays(null)
        viewModel.setRangeDays(7)

        val settled = viewModel.state.awaitVisits(recentVisits.size)
        assertConsistent(settled)
    }

    @Test
    fun `a refresh that fails over loaded history keeps the charts and notes the failure`() = runTest(testDispatcher) {
        var attempt = 0
        val feedRepository = mock<FeedRepository>().also {
            whenever(it.getAllGpsFeed(any())).thenReturn(
                flow {
                    if (attempt++ == 0) emit(recentVisits) else error("history unavailable")
                },
            )
        }
        val viewModel = ChartsViewModel(feedRepository, loggedInAuthRepository(), testDispatcher)
        viewModel.state.awaitVisits(recentVisits.size)

        viewModel.refresh()

        val stale = viewModel.state.first { (it as? LoadState.Loaded)?.staleError != null }
        assertEquals("history unavailable", (stale as LoadState.Loaded).staleError)
        assertTrue(stale.value.hasData)
    }

    /** Every series must describe the same range as the summary beside it. */
    private fun assertConsistent(data: ChartsData) {
        assertEquals(data.summary.totalVisits, data.hourlyActivity.sumOf { it.second })
        assertEquals(data.summary.totalVisits, data.weekdayActivity.sumOf { it.second })
        assertEquals(data.summary.totalVisits, data.topWorlds.sumOf { it.second })
        assertEquals(data.summary.distinctWorlds, data.topWorlds.size)
        assertEquals(data.summary.activeDays, data.dailyActivity.size)
    }

    private suspend fun Flow<LoadState<ChartsData>>.awaitVisits(visits: Int): ChartsData =
        first { (it as? LoadState.Loaded)?.value?.summary?.totalVisits == visits }
            .let { (it as LoadState.Loaded).value }

    private fun buildViewModel(rows: List<FeedEntry>): ChartsViewModel {
        val feedRepository = mock<FeedRepository>().also {
            whenever(it.getAllGpsFeed(any())).thenReturn(flowOf(rows))
        }
        return ChartsViewModel(feedRepository, loggedInAuthRepository(), testDispatcher)
    }

    private fun loggedInAuthRepository() = mock<AuthRepository>().also {
        whenever(it.authState).thenReturn(
            MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_me"))),
        )
    }

    private fun gpsRows(count: Int, daysAgo: Long, world: String): List<FeedEntry> {
        val base = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
        return (0 until count).map { index ->
            FeedEntry(
                id = daysAgo * 1000 + index,
                type = FeedEntryType.GPS,
                userId = "usr_friend",
                displayName = "Friend",
                worldName = world,
                location = "wrld_$index:1",
                createdAt = base.minus(index.toLong(), ChronoUnit.MINUTES).toString(),
            )
        }
    }
}
