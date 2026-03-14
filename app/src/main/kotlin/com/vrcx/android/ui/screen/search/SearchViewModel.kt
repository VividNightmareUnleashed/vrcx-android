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

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _query.value = query
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
        if (_query.value.length >= 2) {
            viewModelScope.launch { search() }
        }
    }

    private suspend fun search() {
        _isSearching.value = true
        try {
            val q = _query.value
            when (_selectedTab.value) {
                SearchTab.USERS -> _users.value = searchRepository.searchUsers(q)
                SearchTab.WORLDS -> _worlds.value = searchRepository.searchWorlds(q)
                SearchTab.AVATARS -> _avatars.value = searchRepository.searchAvatars(q)
                SearchTab.GROUPS -> _groups.value = searchRepository.searchGroups(q)
            }
        } catch (_: Exception) {
        } finally {
            _isSearching.value = false
        }
    }
}
