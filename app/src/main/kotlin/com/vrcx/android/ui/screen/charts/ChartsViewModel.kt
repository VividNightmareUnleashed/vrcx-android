package com.vrcx.android.ui.screen.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.util.parseInstantMillisOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChartsSummary(
    val totalVisits: Int = 0,
    val distinctWorlds: Int = 0,
    val activeDays: Int = 0,
)

/** A GPS row pre-parsed once: epoch millis + local hour/day-of-week + string keys. */
private data class GpsPoint(
    val epochMs: Long,
    val hour: Int,
    val dayOfWeek: Int,
    val dateKey: String,
    val worldKey: String,
)

@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val feedDao: FeedDao,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var points: List<GpsPoint> = emptyList()

    private val _dailyActivity = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val dailyActivity: StateFlow<List<Pair<String, Int>>> = _dailyActivity.asStateFlow()

    private val _topWorlds = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val topWorlds: StateFlow<List<Pair<String, Int>>> = _topWorlds.asStateFlow()

    private val _hourlyActivity = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val hourlyActivity: StateFlow<List<Pair<String, Int>>> = _hourlyActivity.asStateFlow()

    private val _weekdayActivity = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val weekdayActivity: StateFlow<List<Pair<String, Int>>> = _weekdayActivity.asStateFlow()

    private val _selectedRangeDays = MutableStateFlow<Int?>(30)
    val selectedRangeDays: StateFlow<Int?> = _selectedRangeDays.asStateFlow()

    private val _summary = MutableStateFlow(ChartsSummary())
    val summary: StateFlow<ChartsSummary> = _summary.asStateFlow()

    init { loadData(initial = true) }

    fun refresh() = loadData(initial = false)

    fun setRangeDays(days: Int?) {
        _selectedRangeDays.value = days
        recomputeCharts()
    }

    private fun loadData(initial: Boolean) {
        viewModelScope.launch {
            if (initial) _isLoading.value = true else _isRefreshing.value = true
            _error.value = null
            try {
                val userId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
                if (userId == null) {
                    _error.value = "Not signed in"
                    return@launch
                }
                val zone = ZoneId.systemDefault()
                val history = feedDao.getAllGpsFeed(userId).first()
                points = withContext(Dispatchers.Default) { history.map { it.toGpsPoint(zone) } }
                recomputeCharts()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load chart data"
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    private fun recomputeCharts() {
        val rangeDays = _selectedRangeDays.value
        val snapshot = points
        viewModelScope.launch(Dispatchers.Default) {
            val cutoffMs = rangeDays?.let {
                Instant.now().toEpochMilli() - it.toLong() * 24L * 60L * 60L * 1000L
            }
            val filtered = if (cutoffMs == null) snapshot else snapshot.filter { it.epochMs > cutoffMs }

            val hourBuckets = IntArray(24)
            val dayBuckets = IntArray(7)
            val worldCounts = LinkedHashMap<String, Int>()
            val dateCounts = LinkedHashMap<String, Int>()
            filtered.forEach { p ->
                if (p.hour in 0..23) hourBuckets[p.hour]++
                if (p.dayOfWeek in 1..7) dayBuckets[p.dayOfWeek - 1]++
                worldCounts[p.worldKey] = (worldCounts[p.worldKey] ?: 0) + 1
                dateCounts[p.dateKey] = (dateCounts[p.dateKey] ?: 0) + 1
            }

            _summary.value = ChartsSummary(
                totalVisits = filtered.size,
                distinctWorlds = worldCounts.size,
                activeDays = dateCounts.size,
            )
            _dailyActivity.value = dateCounts.entries
                .map { it.key to it.value }
                .sortedBy { it.first }
                .takeLast(30)
            _topWorlds.value = worldCounts.entries
                .map { it.key to it.value }
                .sortedByDescending { it.second }
                .take(10)
            _hourlyActivity.value = (0..23).map { "%02d:00".format(it) to hourBuckets[it] }
            _weekdayActivity.value = (1..7).map { weekdayLabel(it) to dayBuckets[it - 1] }
        }
    }

    private fun FeedGpsEntity.toGpsPoint(zone: ZoneId): GpsPoint {
        val epochMs = parseInstantMillisOrNull(createdAt)
        val zoned = epochMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return GpsPoint(
            epochMs = epochMs ?: Long.MIN_VALUE,
            hour = zoned?.hour ?: -1,
            dayOfWeek = zoned?.dayOfWeek?.value ?: -1,
            dateKey = createdAt.take(10),
            worldKey = worldName.ifBlank { location.substringBefore(":") },
        )
    }

    private fun weekdayLabel(dayValue: Int): String {
        return java.time.DayOfWeek.of(dayValue)
            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

}
