package com.vrcx.android.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.FeedFilter
import com.vrcx.android.ui.common.applyFeedFilter
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The rows the feed shows and whether older ones are still held back, published
 * as one value so the list and the "Load More" affordance always describe the
 * same page.
 */
data class FeedPage(val entries: List<FeedEntry> = emptyList(), val canLoadMore: Boolean = false)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    // How many of the merged page are on screen. The repository already caps the
    // page at the configured history size, so growing this only reveals rows
    // that have already been fetched.
    private val visibleCount = MutableStateFlow(PAGE_SIZE)

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

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }
    fun toggleVipOnly() {
        _vipOnly.value = !_vipOnly.value
    }
    fun loadMore() {
        visibleCount.value += PAGE_SIZE
    }

    /** The friend map itself, so rows can look up an avatar without a parallel map. */
    val friends: StateFlow<Map<String, FriendContext>> = friendRepository.friends

    private val userId = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user?.id.orEmpty()
    }

    private val _activeFilters = MutableStateFlow(FeedEntryType.entries.toSet())
    val activeFilters: StateFlow<Set<FeedEntryType>> = _activeFilters.asStateFlow()

    private val allEntries = userId.flatMapLatest { uid ->
        if (uid.isEmpty()) flowOf(emptyList()) else feedRepository.getUnifiedFeed(uid)
    }

    private val filter = combine(
        _activeFilters,
        _searchQuery,
        _vipOnly,
        friendRepository.favoriteFriendIds,
        ::FeedFilter,
    )

    val page: StateFlow<FeedPage> = combine(
        allEntries,
        filter,
        visibleCount,
    ) { entries, filter, visibleCount ->
        val matching = entries.applyFeedFilter(filter)
        FeedPage(
            entries = matching.take(visibleCount),
            canLoadMore = matching.size > visibleCount,
        )
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, FeedPage())

    fun toggleFilter(filter: FeedEntryType) {
        val current = _activeFilters.value.toMutableSet()
        if (current.contains(filter)) current.remove(filter) else current.add(filter)
        _activeFilters.value = current
    }

    fun consumeError() {
        _error.value = null
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
