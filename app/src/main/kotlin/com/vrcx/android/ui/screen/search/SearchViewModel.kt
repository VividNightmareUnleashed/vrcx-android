package com.vrcx.android.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchTab { USERS, WORLDS, AVATARS, GROUPS }

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val PAGE_SIZE = 10

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.USERS)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()

    private val _users = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val users: StateFlow<List<UserSearchResult>> = _users.asStateFlow()

    private val _worlds = MutableStateFlow<List<World>>(emptyList())
    val worlds: StateFlow<List<World>> = _worlds.asStateFlow()

    private val _avatars = MutableStateFlow<List<Avatar>>(emptyList())
    val avatars: StateFlow<List<Avatar>> = _avatars.asStateFlow()

    private val _groups = MutableStateFlow<List<GroupSearchResult>>(emptyList())
    val groups: StateFlow<List<GroupSearchResult>> = _groups.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentOffset = MutableStateFlow(0)
    val currentOffset: StateFlow<Int> = _currentOffset.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _query.value = query
        _currentOffset.value = 0
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300) // Debounce
                search()
            }
        }
    }

    fun selectTab(tab: SearchTab) {
        _selectedTab.value = tab
        _currentOffset.value = 0
        if (_query.value.length >= 2) {
            viewModelScope.launch { search() }
        }
    }

    fun nextPage() {
        _currentOffset.value += PAGE_SIZE
        viewModelScope.launch { search() }
    }

    fun previousPage() {
        _currentOffset.value = (_currentOffset.value - PAGE_SIZE).coerceAtLeast(0)
        viewModelScope.launch { search() }
    }

    private suspend fun search() {
        _isSearching.value = true
        _error.value = null
        try {
            val q = _query.value
            val offset = _currentOffset.value
            when (_selectedTab.value) {
                SearchTab.USERS -> {
                    val results = searchRepository.searchUsers(q, n = PAGE_SIZE, offset = offset)
                    _users.value = results
                    _hasMore.value = results.size >= PAGE_SIZE
                }
                SearchTab.WORLDS -> {
                    val results = searchRepository.searchWorlds(q, n = PAGE_SIZE, offset = offset)
                    _worlds.value = results
                    _hasMore.value = results.size >= PAGE_SIZE
                }
                SearchTab.AVATARS -> {
                    val results = searchRepository.searchAvatars(q, n = PAGE_SIZE, offset = offset)
                    _avatars.value = results
                    _hasMore.value = results.size >= PAGE_SIZE
                }
                SearchTab.GROUPS -> {
                    val results = searchRepository.searchGroups(q, n = PAGE_SIZE, offset = offset)
                    _groups.value = results
                    _hasMore.value = results.size >= PAGE_SIZE
                }
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Search failed"
        } finally {
            _isSearching.value = false
            _hasSearched.value = true
        }
    }
}
