package com.vrcx.android.ui.screen.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsTab { ONLINE, ACTIVE, OFFLINE }

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

    val filteredFriends: StateFlow<List<FriendContext>> = combine(
        friendRepository.friends,
        _selectedTab,
        _searchQuery,
    ) { friends, tab, query ->
        val targetState = when (tab) {
            FriendsTab.ONLINE -> FriendState.ONLINE
            FriendsTab.ACTIVE -> FriendState.ACTIVE
            FriendsTab.OFFLINE -> FriendState.OFFLINE
        }
        friends.values
            .filter { it.state == targetState }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val onlineCount: StateFlow<Int> = friendRepository.friends.combine(MutableStateFlow(Unit)) { friends, _ ->
        friends.values.count { it.state == FriendState.ONLINE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeCount: StateFlow<Int> = friendRepository.friends.combine(MutableStateFlow(Unit)) { friends, _ ->
        friends.values.count { it.state == FriendState.ACTIVE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val offlineCount: StateFlow<Int> = friendRepository.friends.combine(MutableStateFlow(Unit)) { friends, _ ->
        friends.values.count { it.state == FriendState.OFFLINE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun selectTab(tab: FriendsTab) { _selectedTab.value = tab }
    fun updateSearch(query: String) { _searchQuery.value = query }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                friendRepository.loadFriendsList()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    init {
        refresh()
    }
}
