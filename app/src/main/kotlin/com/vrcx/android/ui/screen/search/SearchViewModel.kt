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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchTab { USERS, WORLDS, AVATARS, GROUPS }

enum class WorldSearchMode {
    SEARCH,
    ACTIVE,
    RECENT,
    FAVORITES,
    MINE,
}

enum class AvatarSearchSource(val label: String, val hint: String) {
    /**
     * VRChat's `/avatars?search=…` endpoint without `user=me` returns only the
     * requesting user's own avatars — there is no public-avatar search through
     * the official API. Label this source accurately so users don't expect
     * results from other creators.
     */
    MY_AVATARS(
        label = "My Avatars",
        hint = "VRChat only exposes your own avatars to API search.",
    ),
    REMOTE(
        label = "Remote",
        hint = "Search a third-party avatar database via its provider URL.",
    ),
}

data class SearchUiState(
    val query: String = "",
    val selectedTab: SearchTab = SearchTab.USERS,
    val users: List<UserSearchResult> = emptyList(),
    val worlds: List<World> = emptyList(),
    val avatars: List<Avatar> = emptyList(),
    val groups: List<GroupSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val currentOffset: Int = 0,
    val hasMore: Boolean = false,
    val searchUsersByBio: Boolean = false,
    val sortUsersByLastLogin: Boolean = false,
    val worldMode: WorldSearchMode = WorldSearchMode.SEARCH,
    val includeWorldLabs: Boolean = false,
    val worldTag: String = "",
    val avatarSearchSource: AvatarSearchSource = AvatarSearchSource.MY_AVATARS,
    val avatarProviderUrl: String = "",
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private data class WorldSearchKey(
        val query: String,
        val mode: WorldSearchMode,
        val includeLabs: Boolean,
        val tag: String,
    )

    private class FilteredWorldSession(val key: WorldSearchKey) {
        val matches = mutableListOf<World>()
        var sourceOffset = 0
        var exhausted = false
    }

    private data class RemoteAvatarCache(
        val query: String,
        val providerUrl: String,
        val items: List<Avatar>,
    )

    private sealed interface SearchResult {
        val hasMore: Boolean

        data class Users(val items: List<UserSearchResult>, override val hasMore: Boolean) : SearchResult
        data class Worlds(val items: List<World>, override val hasMore: Boolean) : SearchResult
        data class Avatars(val items: List<Avatar>, override val hasMore: Boolean) : SearchResult
        data class Groups(val items: List<GroupSearchResult>, override val hasMore: Boolean) : SearchResult
    }

    private val pageSize = 10
    private val worldSourcePageSize = 50
    private var remoteAvatarCache: RemoteAvatarCache? = null
    private var filteredWorldSession: FilteredWorldSession? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun updateQuery(query: String) = updateCriteria(immediate = false, resetRemoteAvatars = true) {
        it.copy(query = query)
    }

    fun selectTab(tab: SearchTab) = updateCriteria(immediate = true) { it.copy(selectedTab = tab) }

    fun setSearchUsersByBio(enabled: Boolean) = updateCriteria(immediate = true) {
        it.copy(searchUsersByBio = enabled)
    }

    fun setSortUsersByLastLogin(enabled: Boolean) = updateCriteria(immediate = true) {
        it.copy(sortUsersByLastLogin = enabled)
    }

    fun setWorldMode(mode: WorldSearchMode) = updateCriteria(immediate = true) { it.copy(worldMode = mode) }

    fun setIncludeWorldLabs(enabled: Boolean) = updateCriteria(immediate = true) {
        it.copy(includeWorldLabs = enabled)
    }

    fun setWorldTag(tag: String) = updateCriteria(immediate = false) { it.copy(worldTag = tag) }

    fun setAvatarSearchSource(source: AvatarSearchSource) =
        updateCriteria(immediate = true, resetRemoteAvatars = true) { it.copy(avatarSearchSource = source) }

    fun setAvatarProviderUrl(url: String) =
        updateCriteria(immediate = false, resetRemoteAvatars = true) { it.copy(avatarProviderUrl = url) }

    fun nextPage() {
        if (searchJob?.isActive == true || !_uiState.value.hasMore) return
        _uiState.update { it.copy(currentOffset = it.currentOffset + pageSize) }
        scheduleSearch(immediate = true, useRemoteAvatarCache = true)
    }

    fun previousPage() {
        if (searchJob?.isActive == true || _uiState.value.currentOffset == 0) return
        _uiState.update { it.copy(currentOffset = (it.currentOffset - pageSize).coerceAtLeast(0)) }
        scheduleSearch(immediate = true, useRemoteAvatarCache = true)
    }

    fun retry() = scheduleSearch(immediate = true, useRemoteAvatarCache = true)

    private inline fun updateCriteria(
        immediate: Boolean,
        resetRemoteAvatars: Boolean = false,
        transform: (SearchUiState) -> SearchUiState,
    ) {
        _uiState.update { transform(it).copy(currentOffset = 0) }
        filteredWorldSession = null
        if (resetRemoteAvatars) remoteAvatarCache = null
        scheduleSearch(immediate = immediate)
    }

    private fun scheduleSearch(immediate: Boolean, useRemoteAvatarCache: Boolean = false) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        _uiState.update { it.copy(isSearching = false) }
        val state = _uiState.value
        val validationError = validateSearchState(state)
        if (validationError != null) {
            _uiState.value = clearCurrentResults(state).copy(
                error = validationError,
                hasSearched = false,
                hasMore = false,
            )
            return
        }
        if (!isSearchReady(state)) {
            _uiState.value = clearCurrentResults(state).copy(
                error = null,
                hasSearched = false,
                hasMore = false,
            )
            return
        }

        searchJob = viewModelScope.launch {
            if (!immediate) delay(300)
            search(generation, useRemoteAvatarCache)
        }
    }

    private fun isSearchReady(state: SearchUiState): Boolean {
        val trimmedQuery = state.query.trim()
        return when (state.selectedTab) {
            SearchTab.USERS -> trimmedQuery.length >= 2
            SearchTab.WORLDS ->
                state.worldMode != WorldSearchMode.SEARCH || trimmedQuery.length >= 2 || state.worldTag.isNotBlank()
            SearchTab.AVATARS -> if (state.avatarSearchSource == AvatarSearchSource.REMOTE) {
                trimmedQuery.length >= 3 && state.avatarProviderUrl.isNotBlank()
            } else {
                trimmedQuery.length >= 2
            }
            SearchTab.GROUPS -> trimmedQuery.length >= 2
        }
    }

    private fun validateSearchState(state: SearchUiState): String? =
        if (
            state.selectedTab == SearchTab.AVATARS &&
            state.avatarSearchSource == AvatarSearchSource.REMOTE &&
            state.query.trim().length >= 3 &&
            state.avatarProviderUrl.isBlank()
        ) {
            "Enter a remote avatar provider URL to search that source."
        } else {
            null
        }

    private suspend fun search(generation: Long, useRemoteAvatarCache: Boolean) {
        if (!isCurrentSearch(generation)) return
        _uiState.update { it.copy(isSearching = true, error = null) }
        val request = _uiState.value
        try {
            val query = request.query.trim()
            val offset = request.currentOffset
            val result = when (request.selectedTab) {
                SearchTab.USERS -> {
                    val items = searchRepository.searchUsers(
                        query = query,
                        n = pageSize + 1,
                        offset = offset,
                        searchByBio = request.searchUsersByBio,
                        sortByLastLogin = request.sortUsersByLastLogin,
                    )
                    SearchResult.Users(items.take(pageSize), items.size > pageSize)
                }
                SearchTab.WORLDS -> loadWorldPage(request, generation)
                SearchTab.AVATARS -> loadAvatarPage(request, query, offset, useRemoteAvatarCache, generation)
                SearchTab.GROUPS -> {
                    val items = searchRepository.searchGroups(query, n = pageSize + 1, offset = offset)
                    SearchResult.Groups(items.take(pageSize), items.size > pageSize)
                }
            }
            if (!isCurrentSearch(generation)) return
            _uiState.update { current -> publishResult(current, result) }
        } catch (e: CancellationException) {
            if (isCurrentSearch(generation)) _uiState.update { it.copy(isSearching = false) }
            throw e
        } catch (e: Exception) {
            if (isCurrentSearch(generation)) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Search failed",
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            }
        }
    }

    private suspend fun loadAvatarPage(
        request: SearchUiState,
        query: String,
        offset: Int,
        useRemoteAvatarCache: Boolean,
        generation: Long,
    ): SearchResult.Avatars {
        if (request.avatarSearchSource != AvatarSearchSource.REMOTE) {
            val items = searchRepository.searchAvatars(query, n = pageSize + 1, offset = offset)
            return SearchResult.Avatars(items.take(pageSize), items.size > pageSize)
        }
        val providerUrl = request.avatarProviderUrl.trim()
        val cached = remoteAvatarCache?.takeIf {
            it.query == query && it.providerUrl == providerUrl
        }
        val items = if (useRemoteAvatarCache && cached != null) {
            cached.items
        } else {
            val fetched = searchRepository.searchRemoteAvatars(
                query = query,
                providerUrl = providerUrl,
            )
            if (!isCurrentSearch(generation)) throw CancellationException("Search replaced")
            remoteAvatarCache = RemoteAvatarCache(query, providerUrl, fetched)
            fetched
        }
        return SearchResult.Avatars(
            items = items.drop(offset).take(pageSize),
            hasMore = offset + pageSize < items.size,
        )
    }

    private suspend fun loadWorldPage(request: SearchUiState, generation: Long): SearchResult.Worlds {
        val query = request.query.trim()
        val mode = request.worldMode.name.lowercase()
        if (request.worldMode == WorldSearchMode.SEARCH || query.isBlank()) {
            filteredWorldSession = null
            val items = searchRepository.searchWorlds(
                query = query,
                n = pageSize + 1,
                offset = request.currentOffset,
                mode = mode,
                includeLabs = request.includeWorldLabs,
                tag = request.worldTag,
            )
            return SearchResult.Worlds(items.take(pageSize), items.size > pageSize)
        }

        val key = WorldSearchKey(query, request.worldMode, request.includeWorldLabs, request.worldTag)
        val session = filteredWorldSession?.takeIf { it.key == key }
            ?: FilteredWorldSession(key).also { filteredWorldSession = it }
        val targetMatchCount = request.currentOffset + pageSize + 1
        while (session.matches.size < targetMatchCount && !session.exhausted) {
            val items = searchRepository.searchWorlds(
                query = query,
                n = worldSourcePageSize,
                offset = session.sourceOffset,
                mode = mode,
                includeLabs = request.includeWorldLabs,
                tag = request.worldTag,
            )
            if (!isCurrentSearch(generation)) throw CancellationException("Search replaced")
            if (items.isEmpty()) {
                session.exhausted = true
                break
            }
            session.matches += items.filter { world ->
                world.name.contains(query, ignoreCase = true) ||
                    world.authorName.contains(query, ignoreCase = true)
            }
            session.sourceOffset += items.size
            if (items.size < worldSourcePageSize) session.exhausted = true
        }
        return SearchResult.Worlds(
            items = session.matches.drop(request.currentOffset).take(pageSize),
            hasMore = session.matches.size > request.currentOffset + pageSize,
        )
    }

    private fun publishResult(state: SearchUiState, result: SearchResult): SearchUiState {
        val common = state.copy(
            hasMore = result.hasMore,
            isSearching = false,
            hasSearched = true,
            error = null,
        )
        return when (result) {
            is SearchResult.Users -> common.copy(users = result.items)
            is SearchResult.Worlds -> common.copy(worlds = result.items)
            is SearchResult.Avatars -> common.copy(avatars = result.items)
            is SearchResult.Groups -> common.copy(groups = result.items)
        }
    }

    private fun clearCurrentResults(state: SearchUiState): SearchUiState = when (state.selectedTab) {
        SearchTab.USERS -> state.copy(users = emptyList())
        SearchTab.WORLDS -> state.copy(worlds = emptyList())
        SearchTab.AVATARS -> state.copy(avatars = emptyList())
        SearchTab.GROUPS -> state.copy(groups = emptyList())
    }

    private fun isCurrentSearch(generation: Long): Boolean = generation == searchGeneration
}
