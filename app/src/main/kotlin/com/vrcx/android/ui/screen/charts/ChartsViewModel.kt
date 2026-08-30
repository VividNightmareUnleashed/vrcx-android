package com.vrcx.android.ui.screen.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChartsSummary(val totalVisits: Int = 0, val distinctWorlds: Int = 0, val activeDays: Int = 0)

/**
 * Every series the Charts screen renders, derived from one snapshot and one
 * range. Published as a single value so the screen can never show a summary
 * for one range beside a chart for another.
 */
data class ChartsData(
    val summary: ChartsSummary = ChartsSummary(),
    val dailyActivity: List<Pair<String, Int>> = emptyList(),
    val topWorlds: List<Pair<String, Int>> = emptyList(),
    val hourlyActivity: List<Pair<String, Int>> = emptyList(),
    val weekdayActivity: List<Pair<String, Int>> = emptyList(),
) {
    val hasData: Boolean
        get() = dailyActivity.isNotEmpty() ||
            topWorlds.isNotEmpty() ||
            hourlyActivity.any { it.second > 0 } ||
            weekdayActivity.any { it.second > 0 }
}

/** A location row pre-parsed once: epoch millis + local hour/day-of-week + string keys. */
private data class GpsPoint(
    val epochMs: Long,
    val hour: Int,
    val dayOfWeek: Int,
    val dateKey: String,
    val worldKey: String,
)

@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val authRepository: AuthRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val historyState = MutableStateFlow<LoadState<List<GpsPoint>>>(LoadState.NotLoaded)

    private val _selectedRangeDays = MutableStateFlow<Int?>(DEFAULT_RANGE_DAYS)
    val selectedRangeDays: StateFlow<Int?> = _selectedRangeDays.asStateFlow()

    /**
     * The whole screen in one value. A refresh that fails over history already on
     * screen keeps the charts and carries the message as a stale-error note, so
     * the range picker stays reachable.
     */
    val state: StateFlow<LoadState<ChartsData>> = combine(
        historyState,
        _selectedRangeDays,
    ) { history, rangeDays ->
        when (history) {
            LoadState.NotLoaded -> LoadState.NotLoaded

            LoadState.Loading -> LoadState.Loading

            is LoadState.Failed -> history

            is LoadState.Loaded -> LoadState.Loaded(
                value = buildCharts(history.value, rangeDays),
                isRefreshing = history.isRefreshing,
                staleError = history.staleError,
            )
        }
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, LoadState.NotLoaded)

    init {
        loadData()
    }

    fun refresh() = loadData()

    fun setRangeDays(days: Int?) {
        _selectedRangeDays.value = days
    }

    private fun loadData() {
        viewModelScope.launch {
            historyState.update { it.startLoad() }
            try {
                val userId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
                if (userId == null) {
                    historyState.update { it.failLoad("Not signed in") }
                    return@launch
                }
                val zone = ZoneId.systemDefault()
                val points = runCatching {
                    withContext(defaultDispatcher) {
                        feedRepository.getAllGpsFeed(userId).first().map { it.toGpsPoint(zone) }
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    historyState.update { it.failLoad(error.message ?: "Failed to load chart data") }
                    return@launch
                }
                historyState.update { it.completeLoad(points) }
            } finally {
                historyState.update { it.settleLoad() }
            }
        }
    }

    private fun buildCharts(points: List<GpsPoint>, rangeDays: Int?): ChartsData {
        val cutoffMs = rangeDays?.let {
            Instant.now().toEpochMilli() - it.toLong() * MILLIS_PER_DAY
        }
        val filtered = if (cutoffMs == null) points else points.filter { it.epochMs > cutoffMs }

        val hourBuckets = IntArray(HOURS_PER_DAY)
        val dayBuckets = IntArray(DAYS_PER_WEEK)
        val worldCounts = LinkedHashMap<String, Int>()
        val dateCounts = LinkedHashMap<String, Int>()
        filtered.forEach { p ->
            if (p.hour in 0 until HOURS_PER_DAY) hourBuckets[p.hour]++
            if (p.dayOfWeek in 1..DAYS_PER_WEEK) dayBuckets[p.dayOfWeek - 1]++
            worldCounts[p.worldKey] = (worldCounts[p.worldKey] ?: 0) + 1
            dateCounts[p.dateKey] = (dateCounts[p.dateKey] ?: 0) + 1
        }

        return ChartsData(
            summary = ChartsSummary(
                totalVisits = filtered.size,
                distinctWorlds = worldCounts.size,
                activeDays = dateCounts.size,
            ),
            dailyActivity = dateCounts.entries
                .map { it.key to it.value }
                .sortedBy { it.first }
                .takeLast(RECENT_ACTIVITY_DAYS),
            topWorlds = worldCounts.entries
                .map { it.key to it.value }
                .sortedByDescending { it.second }
                .take(TOP_WORLD_LIMIT),
            hourlyActivity = (0 until HOURS_PER_DAY).map { "%02d:00".format(it) to hourBuckets[it] },
            weekdayActivity = (1..DAYS_PER_WEEK).map { weekdayLabel(it) to dayBuckets[it - 1] },
        )
    }

    private fun FeedEntry.toGpsPoint(zone: ZoneId): GpsPoint {
        val epochMs = createdAtEpochMs.takeIf { it != Long.MIN_VALUE }
        val zoned = epochMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return GpsPoint(
            epochMs = epochMs ?: Long.MIN_VALUE,
            hour = zoned?.hour ?: -1,
            dayOfWeek = zoned?.dayOfWeek?.value ?: -1,
            // Same zoned instant as the hour/weekday buckets — `createdAt` is a
            // UTC ISO-8601 string, so slicing it files evening sessions under
            // the wrong local day for anyone off UTC.
            dateKey = zoned?.toLocalDate()?.toString() ?: createdAt.take(ISO_DATE_LENGTH),
            worldKey = worldName.ifBlank { location.substringBefore(":") },
        )
    }

    private fun weekdayLabel(dayValue: Int): String = java.time.DayOfWeek.of(dayValue)
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

private const val DEFAULT_RANGE_DAYS = 30
private const val RECENT_ACTIVITY_DAYS = 30
private const val TOP_WORLD_LIMIT = 10
private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7
private const val ISO_DATE_LENGTH = 10
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_DAY =
    24L * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
