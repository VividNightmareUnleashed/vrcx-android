package com.vrcx.android.ui.screen.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChartsSummary(
    val totalVisits: Int = 0,
    val distinctWorlds: Int = 0,
    val activeDays: Int = 0,
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

    private val _gpsHistory = MutableStateFlow<List<FeedGpsEntity>>(emptyList())

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
                _gpsHistory.value = feedDao.getGpsFeed(userId, limit = GPS_FEED_LIMIT).first()
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
        val filteredHistory = filterByRange(_gpsHistory.value, _selectedRangeDays.value)

        _summary.value = ChartsSummary(
            totalVisits = filteredHistory.size,
            distinctWorlds = filteredHistory
                .map { it.worldName.ifBlank { it.location.substringBefore(":") } }
                .distinct()
                .count(),
            activeDays = filteredHistory
                .map { it.createdAt.take(10) }
                .distinct()
                .count(),
        )

        _dailyActivity.value = filteredHistory
            .groupBy { it.createdAt.take(10) }
            .map { (date, entries) -> date to entries.size }
            .sortedBy { it.first }
            .takeLast(30)

        _topWorlds.value = filteredHistory
            .groupBy { it.worldName.ifBlank { it.location.substringBefore(":") } }
            .map { (name, entries) -> name to entries.size }
            .sortedByDescending { it.second }
            .take(10)

        _hourlyActivity.value = (0..23).map { hour ->
            "%02d:00".format(hour) to filteredHistory.count { entity ->
                parseInstant(entity.createdAt)?.atZone(ZoneId.systemDefault())?.hour == hour
            }
        }

        _weekdayActivity.value = (1..7).map { dayValue ->
            weekdayLabel(dayValue) to filteredHistory.count { entity ->
                parseInstant(entity.createdAt)?.atZone(ZoneId.systemDefault())?.dayOfWeek?.value == dayValue
            }
        }
    }

    private fun filterByRange(history: List<FeedGpsEntity>, rangeDays: Int?): List<FeedGpsEntity> {
        if (rangeDays == null) return history
        val cutoff = Instant.now().minusSeconds(rangeDays.toLong() * 24L * 60L * 60L)
        return history.filter { entity ->
            parseInstant(entity.createdAt)?.isAfter(cutoff) ?: false
        }
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun weekdayLabel(dayValue: Int): String {
        return java.time.DayOfWeek.of(dayValue)
            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    companion object {
        private const val GPS_FEED_LIMIT = 500
    }
}
