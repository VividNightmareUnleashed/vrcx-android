package com.vrcx.android.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
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
    val feedLimit: StateFlow<Int> = _feedLimit.asStateFlow()

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

    private val _activeFilters = MutableStateFlow(setOf("gps", "status", "bio", "avatar", "online", "offline"))
    val activeFilters: StateFlow<Set<String>> = _activeFilters.asStateFlow()

    private val vipFriendIds: StateFlow<Set<String>> = friendRepository.friends.map { friends ->
        friends.values.filter { it.isVIP }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val queryLimit: StateFlow<Int> = combine(_feedLimit, maxFeedSize) { currentLimit, maxLimit ->
        currentLimit.coerceAtMost(maxLimit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 100)

    private val allEntries: StateFlow<List<FeedEntry>> = combine(userId, queryLimit) { uid, limit ->
        uid to limit
    }.flatMapLatest { (uid, limit) ->
        if (uid.isEmpty()) return@flatMapLatest flowOf(emptyList<FeedEntry>())

        combine(
            feedRepository.getGpsFeed(uid, limit),
            feedRepository.getStatusFeed(uid, limit),
            feedRepository.getBioFeed(uid, limit),
            feedRepository.getAvatarFeed(uid, limit),
            feedRepository.getOnlineOfflineFeed(uid, limit),
        ) { gps, status, bio, avatar, onlineOffline ->
            val entries = mutableListOf<FeedEntry>()
            gps.forEach {
                entries.add(FeedEntry(it.id, "gps", it.userId, it.displayName, it.worldName, it.previousLocation, it.createdAt))
            }
            status.forEach {
                entries.add(FeedEntry(it.id, "status", it.userId, it.displayName, "${it.status}: ${it.statusDescription}", "${it.previousStatus}: ${it.previousStatusDescription}", it.createdAt))
            }
            bio.forEach {
                entries.add(FeedEntry(it.id, "bio", it.userId, it.displayName, it.bio, it.previousBio, it.createdAt))
            }
            avatar.forEach {
                entries.add(FeedEntry(it.id, "avatar", it.userId, it.displayName, it.avatarName.ifEmpty { "Avatar changed" }, "", it.createdAt, it.currentAvatarThumbnailImageUrl))
            }
            onlineOffline.forEach {
                entries.add(FeedEntry(it.id, it.type, it.userId, it.displayName, it.worldName, "", it.createdAt))
            }
            entries.sortedByDescending { it.createdAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feedEntries: StateFlow<List<FeedEntry>> = combine(
        allEntries,
        _activeFilters,
        _searchQuery,
        _vipOnly,
        vipFriendIds,
        _feedLimit,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val entries = values[0] as List<FeedEntry>
        val filters = values[1] as Set<*>
        val query = values[2] as String
        val vip = values[3] as Boolean
        val vipIds = values[4] as Set<*>
        val limit = values[5] as Int

        entries
            .filter { it.type in filters }
            .filter { entry ->
                if (query.isBlank()) true
                else entry.displayName.contains(query, ignoreCase = true) ||
                    entry.details.contains(query, ignoreCase = true)
            }
            .filter { if (vip) it.userId in vipIds else true }
            .take(limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val canLoadMore: StateFlow<Boolean> = combine(feedEntries, allEntries, _feedLimit, maxFeedSize) { visibleEntries, allLoadedEntries, currentLimit, maxLimit ->
        visibleEntries.size >= currentLimit &&
            allLoadedEntries.size >= currentLimit &&
            currentLimit < maxLimit
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleFilter(filter: String) {
        val current = _activeFilters.value.toMutableSet()
        if (current.contains(filter)) current.remove(filter) else current.add(filter)
        _activeFilters.value = current
    }
}
