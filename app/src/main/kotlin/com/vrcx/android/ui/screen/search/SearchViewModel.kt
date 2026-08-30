package com.vrcx.android.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.SearchRepository
import com.vrcx.android.data.util.runCatchingCancellable
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

/** One tab's page of results. The variant is what the tab renders. */
sealed interface SearchResult {
    val items: List<Any>
    val hasMore: Boolean

    data class Users(override val items: List<UserSearchResult>, override val hasMore: Boolean) : SearchResult
    data class Worlds(override val items: List<World>, override val hasMore: Boolean) : SearchResult
    data class Avatars(override val items: List<Avatar>, override val hasMore: Boolean) : SearchResult
    data class Groups(override val items: List<GroupSearchResult>, override val hasMore: Boolean) : SearchResult
}

internal const val SEARCH_PAGE_SIZE = 10

data class SearchUiState(
    val query: String = "",
    val selectedTab: SearchTab = SearchTab.USERS,
    /** Kept per tab so switching back shows what that tab last loaded. */
    val results: Map<SearchTab, SearchResult> = emptyMap(),
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
) {
    val currentResult: SearchResult? get() = results[selectedTab]

    val pageNumber: Int get() = currentOffset / SEARCH_PAGE_SIZE + 1
}

@HiltViewModel
class SearchViewModel @Inject constructor(searchRepository: SearchRepository) : ViewModel() {
    private val resultLoader = SearchResultLoader(searchRepository)
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    internal fun handle(command: SearchCommand) {
        when (command) {
            is SearchCommand.Criteria -> handleCriteria(command)
            is SearchCommand.Paging -> handlePaging(command)
        }
    }

    private fun handleCriteria(command: SearchCommand.Criteria) {
        when (command) {
            is SearchCommand.UpdateQuery ->
                updateCriteria(immediate = false, resetRemoteAvatars = true) { it.copy(query = command.query) }

            is SearchCommand.SelectTab ->
                updateCriteria(immediate = true) { it.copy(selectedTab = command.tab) }

            is SearchCommand.SearchUsersByBio ->
                updateCriteria(immediate = true) { it.copy(searchUsersByBio = command.enabled) }

            is SearchCommand.SortUsersByLastLogin ->
                updateCriteria(immediate = true) { it.copy(sortUsersByLastLogin = command.enabled) }

            is SearchCommand.SetWorldMode ->
                updateCriteria(immediate = true) { it.copy(worldMode = command.mode) }

            is SearchCommand.IncludeWorldLabs ->
                updateCriteria(immediate = true) { it.copy(includeWorldLabs = command.enabled) }

            is SearchCommand.SetWorldTag ->
                updateCriteria(immediate = false) { it.copy(worldTag = command.tag) }

            is SearchCommand.SetAvatarSource ->
                updateCriteria(immediate = true, resetRemoteAvatars = true) {
                    it.copy(avatarSearchSource = command.source)
                }

            is SearchCommand.SetAvatarProviderUrl ->
                updateCriteria(immediate = false, resetRemoteAvatars = true) {
                    it.copy(avatarProviderUrl = command.url)
                }
        }
    }

    private fun handlePaging(command: SearchCommand.Paging) {
        when (command) {
            SearchCommand.NextPage -> if (searchJob?.isActive != true && _uiState.value.hasMore) {
                _uiState.update { it.copy(currentOffset = it.currentOffset + SEARCH_PAGE_SIZE) }
                scheduleSearch(immediate = true, useRemoteAvatarCache = true)
            }

            SearchCommand.PreviousPage -> if (
                searchJob?.isActive != true &&
                _uiState.value.currentOffset > 0
            ) {
                _uiState.update {
                    it.copy(currentOffset = (it.currentOffset - SEARCH_PAGE_SIZE).coerceAtLeast(0))
                }
                scheduleSearch(immediate = true, useRemoteAvatarCache = true)
            }

            SearchCommand.Retry -> scheduleSearch(immediate = true, useRemoteAvatarCache = true)
        }
    }

    private inline fun updateCriteria(
        immediate: Boolean,
        resetRemoteAvatars: Boolean = false,
        transform: (SearchUiState) -> SearchUiState,
    ) {
        _uiState.update { transform(it).copy(currentOffset = 0) }
        resultLoader.resetWorldSession()
        if (resetRemoteAvatars) resultLoader.resetRemoteAvatars()
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
            if (!immediate) delay(REMOTE_SEARCH_DEBOUNCE_MS)
            search(generation, useRemoteAvatarCache)
        }
    }

    private suspend fun search(generation: Long, useRemoteAvatarCache: Boolean) {
        if (!isCurrentSearch(generation)) return
        _uiState.update { it.copy(isSearching = true, error = null) }
        val request = _uiState.value
        try {
            runCatchingCancellable {
                resultLoader.load(
                    request = request,
                    useRemoteAvatarCache = useRemoteAvatarCache,
                    ensureCurrent = {
                        if (!isCurrentSearch(generation)) throw CancellationException("Search replaced")
                    },
                )
            }.fold(
                onSuccess = { result ->
                    if (isCurrentSearch(generation)) {
                        _uiState.update { current -> publishSearchResult(current, result) }
                    }
                },
                onFailure = { failure ->
                    if (isCurrentSearch(generation)) {
                        _uiState.update {
                            it.copy(
                                error = failure.message ?: "Search failed",
                                isSearching = false,
                                hasSearched = true,
                            )
                        }
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            if (isCurrentSearch(generation)) _uiState.update { it.copy(isSearching = false) }
            throw cancelled
        }
    }

    private fun isCurrentSearch(generation: Long): Boolean = generation == searchGeneration
}

private fun isSearchReady(state: SearchUiState): Boolean {
    val trimmedQuery = state.query.trim()
    return when (state.selectedTab) {
        SearchTab.USERS -> trimmedQuery.length >= 2

        SearchTab.WORLDS ->
            state.worldMode != WorldSearchMode.SEARCH ||
                trimmedQuery.length >= 2 ||
                state.worldTag.isNotBlank()

        SearchTab.AVATARS -> if (state.avatarSearchSource == AvatarSearchSource.REMOTE) {
            trimmedQuery.length >= MIN_REMOTE_AVATAR_QUERY_LENGTH && state.avatarProviderUrl.isNotBlank()
        } else {
            trimmedQuery.length >= 2
        }

        SearchTab.GROUPS -> trimmedQuery.length >= 2
    }
}

private fun validateSearchState(state: SearchUiState): String? {
    val isRemoteAvatarSearch =
        state.selectedTab == SearchTab.AVATARS && state.avatarSearchSource == AvatarSearchSource.REMOTE
    return if (
        isRemoteAvatarSearch &&
        state.query.trim().length >= MIN_REMOTE_AVATAR_QUERY_LENGTH &&
        state.avatarProviderUrl.isBlank()
    ) {
        "Enter a remote avatar provider URL to search that source."
    } else {
        null
    }
}

private fun publishSearchResult(state: SearchUiState, result: SearchResult): SearchUiState = state.copy(
    results = state.results + (state.selectedTab to result),
    hasMore = result.hasMore,
    isSearching = false,
    hasSearched = true,
    error = null,
)

private fun clearCurrentResults(state: SearchUiState): SearchUiState =
    state.copy(results = state.results - state.selectedTab)

private const val REMOTE_SEARCH_DEBOUNCE_MS = 300L
private const val MIN_REMOTE_AVATAR_QUERY_LENGTH = 3
