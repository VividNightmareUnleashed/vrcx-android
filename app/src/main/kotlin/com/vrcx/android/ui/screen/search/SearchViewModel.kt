package com.vrcx.android.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SearchTab { USERS, WORLDS, AVATARS, GROUPS }

enum class WorldSearchMode {
    SEARCH,
    ACTIVE,
    RECENT,
    FAVORITES,
    MINE,
}

enum class AvatarSearchSource {
    VRCHAT,
    REMOTE,
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val pageSize = 10
    private var remoteAvatarResults: List<Avatar> = emptyList()
    private var searchJob: Job? = null

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

    private val _searchUsersByBio = MutableStateFlow(false)
    val searchUsersByBio: StateFlow<Boolean> = _searchUsersByBio.asStateFlow()

    private val _sortUsersByLastLogin = MutableStateFlow(false)
    val sortUsersByLastLogin: StateFlow<Boolean> = _sortUsersByLastLogin.asStateFlow()

    private val _worldMode = MutableStateFlow(WorldSearchMode.SEARCH)
    val worldMode: StateFlow<WorldSearchMode> = _worldMode.asStateFlow()

    private val _includeWorldLabs = MutableStateFlow(false)
    val includeWorldLabs: StateFlow<Boolean> = _includeWorldLabs.asStateFlow()

    private val _worldTag = MutableStateFlow("")
    val worldTag: StateFlow<String> = _worldTag.asStateFlow()

    private val _avatarSearchSource = MutableStateFlow(AvatarSearchSource.VRCHAT)
    val avatarSearchSource: StateFlow<AvatarSearchSource> = _avatarSearchSource.asStateFlow()

    private val _avatarProviderUrl = MutableStateFlow("")
    val avatarProviderUrl: StateFlow<String> = _avatarProviderUrl.asStateFlow()

    fun updateQuery(query: String) {
        _query.value = query
        _currentOffset.value = 0
        debounceSearch()
    }

    fun selectTab(tab: SearchTab) {
        _selectedTab.value = tab
        _currentOffset.value = 0
        scheduleSearch(immediate = true)
    }

    fun setSearchUsersByBio(enabled: Boolean) {
        _searchUsersByBio.value = enabled
        _currentOffset.value = 0
        scheduleSearch(immediate = true)
    }

    fun setSortUsersByLastLogin(enabled: Boolean) {
        _sortUsersByLastLogin.value = enabled
        _currentOffset.value = 0
        scheduleSearch(immediate = true)
    }

    fun setWorldMode(mode: WorldSearchMode) {
        _worldMode.value = mode
        _currentOffset.value = 0
        scheduleSearch(immediate = true)
    }

    fun setIncludeWorldLabs(enabled: Boolean) {
        _includeWorldLabs.value = enabled
        _currentOffset.value = 0
        scheduleSearch(immediate = true)
    }

    fun setWorldTag(tag: String) {
        _worldTag.value = tag
        _currentOffset.value = 0
        debounceSearch()
    }

    fun setAvatarSearchSource(source: AvatarSearchSource) {
        _avatarSearchSource.value = source
        _currentOffset.value = 0
        remoteAvatarResults = emptyList()
        scheduleSearch(immediate = true)
    }

    fun setAvatarProviderUrl(url: String) {
        _avatarProviderUrl.value = url
        _currentOffset.value = 0
        remoteAvatarResults = emptyList()
        debounceSearch()
    }

    fun nextPage() {
        _currentOffset.value += pageSize
        scheduleSearch(immediate = true, useRemoteAvatarCache = true)
    }

    fun previousPage() {
        _currentOffset.value = (_currentOffset.value - pageSize).coerceAtLeast(0)
        scheduleSearch(immediate = true, useRemoteAvatarCache = true)
    }

    fun retry() {
        scheduleSearch(immediate = true)
    }

    private fun debounceSearch() {
        scheduleSearch(immediate = false)
    }

    private fun scheduleSearch(immediate: Boolean, useRemoteAvatarCache: Boolean = false) {
        searchJob?.cancel()
        val validationError = validateSearchState()
        if (validationError != null) {
            clearCurrentResults()
            _error.value = validationError
            _hasSearched.value = false
            _hasMore.value = false
            return
        }
        if (!isSearchReady()) {
            clearCurrentResults()
            _error.value = null
            _hasSearched.value = false
            _hasMore.value = false
            return
        }

        if (immediate) {
            searchJob = viewModelScope.launch {
                search(useRemoteAvatarCache = useRemoteAvatarCache)
            }
        } else {
            searchJob = viewModelScope.launch {
                delay(300)
                search()
            }
        }
    }

    private fun isSearchReady(): Boolean {
        val trimmedQuery = _query.value.trim()
        return when (_selectedTab.value) {
            SearchTab.USERS -> trimmedQuery.length >= 2
            SearchTab.WORLDS -> _worldMode.value != WorldSearchMode.SEARCH || trimmedQuery.length >= 2 || _worldTag.value.isNotBlank()
            SearchTab.AVATARS -> {
                if (_avatarSearchSource.value == AvatarSearchSource.REMOTE) {
                    trimmedQuery.length >= 3 && _avatarProviderUrl.value.isNotBlank()
                } else {
                    trimmedQuery.length >= 2
                }
            }
            SearchTab.GROUPS -> trimmedQuery.length >= 2
        }
    }

    private fun validateSearchState(): String? {
        return if (
            _selectedTab.value == SearchTab.AVATARS &&
            _avatarSearchSource.value == AvatarSearchSource.REMOTE &&
            _query.value.trim().length >= 3 &&
            _avatarProviderUrl.value.isBlank()
        ) {
            "Enter a remote avatar provider URL to search that source."
        } else {
            null
        }
    }

    private suspend fun search(useRemoteAvatarCache: Boolean = false) {
        _isSearching.value = true
        _error.value = null
        try {
            val q = _query.value.trim()
            val offset = _currentOffset.value
            when (_selectedTab.value) {
                SearchTab.USERS -> {
                    val results = searchRepository.searchUsers(
                        query = q,
                        n = pageSize,
                        offset = offset,
                        searchByBio = _searchUsersByBio.value,
                        sortByLastLogin = _sortUsersByLastLogin.value,
                    )
                    _users.value = results
                    _hasMore.value = results.size >= pageSize
                }
                SearchTab.WORLDS -> {
                    val results = searchRepository.searchWorlds(
                        query = q,
                        n = pageSize,
                        offset = offset,
                        mode = _worldMode.value.name.lowercase(),
                        includeLabs = _includeWorldLabs.value,
                        tag = _worldTag.value,
                    )
                    _worlds.value = if (_worldMode.value == WorldSearchMode.SEARCH || q.isBlank()) {
                        results
                    } else {
                        results.filter {
                            it.name.contains(q, ignoreCase = true) ||
                                it.authorName.contains(q, ignoreCase = true)
                        }
                    }
                    _hasMore.value = results.size >= pageSize
                }
                SearchTab.AVATARS -> {
                    if (_avatarSearchSource.value == AvatarSearchSource.REMOTE) {
                        if (!useRemoteAvatarCache || remoteAvatarResults.isEmpty()) {
                            remoteAvatarResults = searchRepository.searchRemoteAvatars(
                                query = q,
                                providerUrl = _avatarProviderUrl.value.trim(),
                            )
                        }
                        _avatars.value = remoteAvatarResults.drop(offset).take(pageSize)
                        _hasMore.value = offset + pageSize < remoteAvatarResults.size
                    } else {
                        val results = searchRepository.searchAvatars(q, n = pageSize, offset = offset)
                        _avatars.value = results
                        _hasMore.value = results.size >= pageSize
                    }
                }
                SearchTab.GROUPS -> {
                    val results = searchRepository.searchGroups(q, n = pageSize, offset = offset)
                    _groups.value = results
                    _hasMore.value = results.size >= pageSize
                }
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Search failed"
        } finally {
            _isSearching.value = false
            _hasSearched.value = true
        }
    }

    private fun clearCurrentResults() {
        when (_selectedTab.value) {
            SearchTab.USERS -> _users.value = emptyList()
            SearchTab.WORLDS -> _worlds.value = emptyList()
            SearchTab.AVATARS -> {
                _avatars.value = emptyList()
                if (_avatarSearchSource.value != AvatarSearchSource.REMOTE) {
                    remoteAvatarResults = emptyList()
                }
            }
            SearchTab.GROUPS -> _groups.value = emptyList()
        }
    }
}
