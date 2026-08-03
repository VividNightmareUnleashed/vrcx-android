package com.vrcx.android.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UnifiedFeed
import com.vrcx.android.data.repository.detailText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

private data class FeedCriteria(
    val filters: Set<FeedEntryType>,
    val query: String,
    val vipOnly: Boolean,
    val vipFriendIds: Set<String>,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    preferences: VrcxPreferences,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    private val _feedLimit = MutableStateFlow(100)

    private val maxFeedSize: StateFlow<Int> = preferences.maxFeedSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1000)

    init {
        viewModelScope.launch {
            maxFeedSize.collect { maxSize ->
                _feedLimit.value = _feedLimit.value.coerceAtMost(maxSize)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                friendRepository.loadFriendsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun updateSearch(query: String) { _searchQuery.value = query }
    fun toggleVipOnly() { _vipOnly.value = !_vipOnly.value }
    fun loadMore() {
        _feedLimit.value = (_feedLimit.value + 100).coerceAtMost(maxFeedSize.value)
    }

    val userAvatarUrls: StateFlow<Map<String, String>> = friendRepository.friends.map { friends ->
        friends.values.mapNotNull { f ->
            f.ref?.displayAvatarUrl()?.takeIf { it.isNotEmpty() }?.let { url -> f.id to url }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val userId = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user?.id ?: ""
    }

    private val _activeFilters = MutableStateFlow(FeedEntryType.entries.toSet())
    val activeFilters: StateFlow<Set<FeedEntryType>> = _activeFilters.asStateFlow()

    private val vipFriendIds: StateFlow<Set<String>> = friendRepository.friends.map { friends ->
        friends.values.filter { it.isVIP }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val queryLimit: StateFlow<Int> = combine(_feedLimit, maxFeedSize) { currentLimit, maxLimit ->
        currentLimit.coerceAtMost(maxLimit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 100)

    private val feedPage: StateFlow<UnifiedFeed> = combine(userId, queryLimit) { uid, limit ->
        uid to limit
    }.flatMapLatest { (uid, limit) ->
        if (uid.isEmpty()) flowOf(UnifiedFeed(emptyList(), false))
        else feedRepository.getUnifiedFeed(uid, limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnifiedFeed(emptyList(), false))

    private val criteria = combine(
        _activeFilters,
        _searchQuery,
        _vipOnly,
        vipFriendIds,
    ) { filters, query, vipOnly, vipFriendIds ->
        FeedCriteria(filters, query, vipOnly, vipFriendIds)
    }

    val feedEntries: StateFlow<List<FeedEntry>> = combine(
        feedPage,
        criteria,
        _feedLimit,
    ) { page, criteria, limit ->
        page.entries
            .filter { it.type in criteria.filters }
            .filter { entry ->
                if (criteria.query.isBlank()) true
                else entry.displayName.contains(criteria.query, ignoreCase = true) ||
                    entry.detailText().contains(criteria.query, ignoreCase = true)
            }
            .filter { if (criteria.vipOnly) it.userId in criteria.vipFriendIds else true }
            .take(limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val canLoadMore: StateFlow<Boolean> = combine(feedPage, _feedLimit, maxFeedSize) { page, currentLimit, maxLimit ->
        page.sourceSaturated && currentLimit < maxLimit
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleFilter(filter: FeedEntryType) {
        val current = _activeFilters.value.toMutableSet()
        if (current.contains(filter)) current.remove(filter) else current.add(filter)
        _activeFilters.value = current
    }

    fun consumeError() { _error.value = null }
}
