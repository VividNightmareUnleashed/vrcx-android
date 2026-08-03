package com.vrcx.android.ui.screen.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.TrustRank
import com.vrcx.android.data.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsTab { ONLINE, ACTIVE, OFFLINE }
enum class FriendsSortOption { NAME, LAST_SEEN, TRUST_RANK }
data class FriendCounts(val online: Int = 0, val active: Int = 0, val offline: Int = 0)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(FriendsTab.ONLINE)
    val selectedTab: StateFlow<FriendsTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _sortOption = MutableStateFlow(FriendsSortOption.NAME)
    val sortOption: StateFlow<FriendsSortOption> = _sortOption.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    val filteredFriends: StateFlow<List<FriendContext>> = combine(
        friendRepository.friends,
        _selectedTab,
        _searchQuery,
        _sortOption,
        _vipOnly,
    ) { friends, tab, query, sort, vip ->
        val targetState = when (tab) {
            FriendsTab.ONLINE -> FriendState.ONLINE
            FriendsTab.ACTIVE -> FriendState.ACTIVE
            FriendsTab.OFFLINE -> FriendState.OFFLINE
        }
        friends.values
            .filter { it.state == targetState }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { if (vip) it.isVIP else true }
            .let { list ->
                when (sort) {
                    FriendsSortOption.NAME -> list.sortedBy { it.name.lowercase() }
                    FriendsSortOption.LAST_SEEN -> list.sortedByDescending { it.ref?.lastLogin ?: "" }
                    FriendsSortOption.TRUST_RANK -> list.sortedBy { TrustRank.fromTags(it.ref?.tags ?: emptyList()).priority }
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val counts: StateFlow<FriendCounts> = friendRepository.friends.map { friends ->
        val states = friends.values.groupingBy { it.state }.eachCount()
        FriendCounts(
            online = states[FriendState.ONLINE] ?: 0,
            active = states[FriendState.ACTIVE] ?: 0,
            offline = states[FriendState.OFFLINE] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FriendCounts())

    fun selectTab(tab: FriendsTab) { _selectedTab.value = tab }
    fun updateSearch(query: String) { _searchQuery.value = query }
    fun setSortOption(option: FriendsSortOption) { _sortOption.value = option }
    fun toggleVipOnly() { _vipOnly.value = !_vipOnly.value }
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
            try {
                friendRepository.loadFriendsList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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
