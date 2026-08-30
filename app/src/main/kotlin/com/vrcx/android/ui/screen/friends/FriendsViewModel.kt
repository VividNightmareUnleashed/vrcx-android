package com.vrcx.android.ui.screen.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.TrustRank
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FriendsSortOption { NAME, LAST_SEEN, TRUST_RANK }

/**
 * The rows and the criteria used to derive them, published as one snapshot.
 * Editor controls publish separately so input does not wait for sorting.
 */
data class FriendsUiState(
    val friends: List<FriendContext> = emptyList(),
    val counts: Map<FriendState, Int> = emptyMap(),
    val notifyEnabledIds: Set<String> = emptySet(),
    val selectedTab: FriendState = FriendState.ONLINE,
    val searchQuery: String = "",
    val sortOption: FriendsSortOption = FriendsSortOption.NAME,
    val vipOnly: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class FriendsControls(
    val selectedTab: FriendState,
    val searchQuery: String,
    val sortOption: FriendsSortOption,
    val vipOnly: Boolean,
)

private data class FriendsOperationState(val isRefreshing: Boolean = false, val error: String? = null)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _controls = MutableStateFlow(
        FriendsControls(
            selectedTab = FriendState.ONLINE,
            searchQuery = "",
            sortOption = FriendsSortOption.NAME,
            vipOnly = false,
        ),
    )
    val controls: StateFlow<FriendsControls> = _controls.asStateFlow()
    private val operation = MutableStateFlow(FriendsOperationState())

    val state: StateFlow<FriendsUiState> = combine(
        friendRepository.friends,
        controls,
        friendRepository.favoriteFriendIds,
        friendRepository.notifyEnabledIds,
        operation,
    ) { friends, controls, favoriteIds, notifyIds, operation ->
        val matching = friends.values
            .filter { it.state == controls.selectedTab }
            .filter {
                controls.searchQuery.isBlank() ||
                    it.name.contains(controls.searchQuery, ignoreCase = true)
            }
            .filter { !controls.vipOnly || it.id in favoriteIds }
        FriendsUiState(
            friends = when (controls.sortOption) {
                FriendsSortOption.NAME ->
                    matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

                FriendsSortOption.LAST_SEEN -> matching.sortedByDescending {
                    it.ref?.lastLogin.orEmpty()
                }

                // Resolving the trust rank scans the tag list, so do it once per
                // friend rather than once per comparison.
                FriendsSortOption.TRUST_RANK ->
                    matching
                        .map { it to TrustRank.fromTags(it.ref?.tags.orEmpty()).priority }
                        .sortedBy { (_, priority) -> priority }
                        .map { (friend, _) -> friend }
            },
            counts = friends.values.groupingBy { it.state }.eachCount(),
            notifyEnabledIds = notifyIds,
            selectedTab = controls.selectedTab,
            searchQuery = controls.searchQuery,
            sortOption = controls.sortOption,
            vipOnly = controls.vipOnly,
            isRefreshing = operation.isRefreshing,
            error = operation.error,
        )
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, FriendsUiState())

    fun selectTab(tab: FriendState) {
        _controls.update { it.copy(selectedTab = tab) }
    }
    fun updateSearch(query: String) {
        _controls.update { it.copy(searchQuery = query) }
    }
    fun setSortOption(option: FriendsSortOption) {
        _controls.update { it.copy(sortOption = option) }
    }
    fun toggleVipOnly() {
        _controls.update { it.copy(vipOnly = !it.vipOnly) }
    }
    fun consumeError() {
        operation.update { it.copy(error = null) }
    }
    fun toggleFriendNotify(friendUserId: String) {
        viewModelScope.launch {
            runCatchingCancellable { friendRepository.toggleFriendNotify(friendUserId) }
                .onFailure { failure ->
                    android.util.Log.e(
                        "FriendsViewModel",
                        "Failed to toggle notify for $friendUserId",
                        failure,
                    )
                }
        }
    }

    fun refresh() {
        if (operation.value.isRefreshing) return
        operation.value = FriendsOperationState(isRefreshing = true)
        viewModelScope.launch {
            try {
                runCatchingCancellable { friendRepository.loadFriendsList() }
                    .exceptionOrNull()
                    ?.let { failure ->
                        operation.update {
                            it.copy(error = failure.message ?: "Failed to load friends")
                        }
                    }
            } finally {
                operation.update { it.copy(isRefreshing = false) }
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
