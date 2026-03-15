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

    private val _gpsHistory = MutableStateFlow<List<FeedGpsEntity>>(emptyList())

    private val _dailyActivity = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val dailyActivity: StateFlow<List<Pair<String, Int>>> = _dailyActivity.asStateFlow()

    private val _topWorlds = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val topWorlds: StateFlow<List<Pair<String, Int>>> = _topWorlds.asStateFlow()

    init { loadData() }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val userId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: return@launch
                val gps = feedDao.getGpsFeed(userId, limit = 500).first()
                _gpsHistory.value = gps

                // Daily activity: group by date
                _dailyActivity.value = gps
                    .groupBy { it.createdAt.take(10) }
                    .map { (date, entries) -> date to entries.size }
                    .sortedByDescending { it.first }
                    .take(30)

                // Top worlds: group by world name
                _topWorlds.value = gps
                    .filter { it.worldName.isNotEmpty() }
                    .groupBy { it.worldName }
                    .map { (name, entries) -> name to entries.size }
                    .sortedByDescending { it.second }
                    .take(10)
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }
}
