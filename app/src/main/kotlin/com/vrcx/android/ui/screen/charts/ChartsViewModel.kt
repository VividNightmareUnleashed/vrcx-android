package com.vrcx.android.ui.screen.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.FeedDao
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    init { loadData(initial = true) }

    fun refresh() = loadData(initial = false)

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
                val gps = feedDao.getGpsFeed(userId, limit = GPS_FEED_LIMIT).first()
                _gpsHistory.value = gps

                _dailyActivity.value = gps
                    .groupBy { it.createdAt.take(10) }
                    .map { (date, entries) -> date to entries.size }
                    .sortedBy { it.first } // chronological for charting
                    .takeLast(30)

                _topWorlds.value = gps
                    .filter { it.worldName.isNotEmpty() }
                    .groupBy { it.worldName }
                    .map { (name, entries) -> name to entries.size }
                    .sortedByDescending { it.second }
                    .take(10)
            } catch (e: Exception) {
                // Surface the error instead of silently showing an empty state.
                _error.value = e.message ?: "Failed to load chart data"
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    companion object {
        private const val GPS_FEED_LIMIT = 500
    }
}
