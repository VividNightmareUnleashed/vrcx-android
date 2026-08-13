package com.vrcx.android.ui.screen.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.TrustRank
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.common.derivationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsSortOption { NAME, LAST_SEEN, TRUST_RANK }

/**
 * Everything the roster renders, published as one value: the tab counts, the
 * rows under the current tab, and the notify flags those rows badge. Derived
 * together so the list and the tab labels can never describe different
 * snapshots of the friend map.
 */
data class FriendsUiState(
    val friends: List<FriendContext> = emptyList(),
    val counts: Map<FriendState, Int> = emptyMap(),
    val notifyEnabledIds: Set<String> = emptySet(),
)

private data class FriendsCriteria(
    val tab: FriendState,
    val query: String,
    val sort: FriendsSortOption,
    val vipOnly: Boolean,
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(FriendState.ONLINE)
    val selectedTab: StateFlow<FriendState> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sortOption = MutableStateFlow(FriendsSortOption.NAME)
    val sortOption: StateFlow<FriendsSortOption> = _sortOption.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    private val criteria = combine(
        _selectedTab,
        _searchQuery,
        _sortOption,
        _vipOnly,
        ::FriendsCriteria,
    )

    val state: StateFlow<FriendsUiState> = combine(
        friendRepository.friends,
        criteria,
        friendRepository.favoriteFriendIds,
        friendRepository.notifyEnabledIds,
    ) { friends, criteria, favoriteIds, notifyIds ->
        val matching = friends.values
            .filter { it.state == criteria.tab }
            .filter { criteria.query.isBlank() || it.name.contains(criteria.query, ignoreCase = true) }
            .filter { !criteria.vipOnly || it.id in favoriteIds }
        FriendsUiState(
            friends = when (criteria.sort) {
                FriendsSortOption.NAME ->
                    matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                FriendsSortOption.LAST_SEEN -> matching.sortedByDescending { it.ref?.lastLogin ?: "" }
                // Resolving the trust rank scans the tag list, so do it once per
                // friend rather than once per comparison.
                FriendsSortOption.TRUST_RANK -> matching
                    .map { it to TrustRank.fromTags(it.ref?.tags ?: emptyList()).priority }
                    .sortedBy { (_, priority) -> priority }
                    .map { (friend, _) -> friend }
            },
            counts = friends.values.groupingBy { it.state }.eachCount(),
            notifyEnabledIds = notifyIds,
        )
    }
        .stateIn(derivationScope, SharingStarted.WhileSubscribed(5000), FriendsUiState())

    fun selectTab(tab: FriendState) { _selectedTab.value = tab }
    fun updateSearch(query: String) { _searchQuery.value = query }
    fun setSortOption(option: FriendsSortOption) { _sortOption.value = option }
    fun toggleVipOnly() { _vipOnly.value = !_vipOnly.value }
    fun consumeError() { _error.value = null }
    fun toggleFriendNotify(friendUserId: String) {
        viewModelScope.launch {
            try {
                friendRepository.toggleFriendNotify(friendUserId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("FriendsViewModel", "Failed to toggle notify for $friendUserId", e)
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
                _error.value = e.message ?: "Failed to load friends"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    init {
        // The foreground service preloads friends at login and the websocket
        // keeps them fresh, so only cold-start a fetch when we have nothing yet.
        // Pull-to-refresh still covers explicit reloads.
        if (friendRepository.friends.value.isEmpty()) {
            refresh()
        }
    }
}
