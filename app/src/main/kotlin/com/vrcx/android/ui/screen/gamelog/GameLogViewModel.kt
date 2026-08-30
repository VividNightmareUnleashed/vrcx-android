package com.vrcx.android.ui.screen.gamelog

import androidx.lifecycle.ViewModel
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.model.resolvePresenceLocation
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
import java.time.Duration
import javax.inject.Inject
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
import kotlinx.coroutines.flow.update

/** A selectable time window for Activity History. */
enum class ActivityRange(val label: String, val days: Int?) {
    TODAY("Today", 1),
    LAST_7("Last 7 days", 7),
    LAST_30("Last 30 days", 30),
    ALL("All time", null),
}

enum class GameLogScope(val label: String) {
    CURRENT_INSTANCE("Instance"),
    CURRENT_WORLD("World"),
    ALL_ACTIVITY("All"),
}

internal data class GameLogControls(
    val searchQuery: String = "",
    val vipOnly: Boolean = false,
    val filters: Set<FeedEntryType> = FeedEntryType.entries.toSet(),
    val range: ActivityRange = ActivityRange.LAST_7,
    val scope: GameLogScope = GameLogScope.ALL_ACTIVITY,
)

/** Rows and paging metadata derived together from one set of filter criteria. */
data class GameLogPage(
    val entries: List<FeedEntry> = emptyList(),
    val canLoadMore: Boolean = false,
    val showScopeHint: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameLogViewModel @Inject constructor(
    authRepository: AuthRepository,
    feedRepository: FeedRepository,
    friendRepository: FriendRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _controls = MutableStateFlow(GameLogControls())
    internal val controls: StateFlow<GameLogControls> = _controls.asStateFlow()

    private val visibleCount = MutableStateFlow(PAGE_SIZE)

    private val currentLocation = authRepository.authState
        .map { state -> resolvePresenceLocation((state as? AuthState.LoggedIn)?.user) }

    private val allEntries = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() }
        .flatMapLatest { userId ->
            if (userId.isBlank()) flowOf(emptyList()) else feedRepository.getUnifiedFeed(userId)
        }

    val page: StateFlow<GameLogPage> = combine(
        allEntries,
        controls,
        friendRepository.favoriteFriendIds,
        currentLocation,
        visibleCount,
    ) { all, controls, favoriteFriendIds, currentLocation, visibleCount ->
        val currentWorldId = parseWorldId(currentLocation)
        val cutoffEpochMs = controls.range.cutoffEpochMillis(System.currentTimeMillis())
        val filter = FeedFilter(
            types = controls.filters,
            query = controls.searchQuery,
            vipOnly = controls.vipOnly,
            vipFriendIds = favoriteFriendIds,
        )
        val matching = all.applyFeedFilter(filter)
            .filter { entry ->
                matchesScope(entry, controls.scope, currentLocation, currentWorldId)
            }
            .filter { entry ->
                cutoffEpochMs == null || entry.createdAtEpochMs >= cutoffEpochMs
            }

        GameLogPage(
            entries = matching.take(visibleCount),
            // The repository already bounds every source table, so this limit
            // only has to stop where the merged rows stop.
            canLoadMore = matching.size > visibleCount,
            showScopeHint = controls.scope != GameLogScope.ALL_ACTIVITY &&
                !isTrackableLocation(currentLocation),
        )
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, GameLogPage())

    fun updateSearch(query: String) {
        _controls.update { it.copy(searchQuery = query) }
    }

    fun toggleVipOnly() {
        _controls.update { it.copy(vipOnly = !it.vipOnly) }
    }

    fun toggleFilter(filter: FeedEntryType) {
        _controls.update { controls ->
            val filters = if (filter in controls.filters) {
                controls.filters - filter
            } else {
                controls.filters + filter
            }
            controls.copy(filters = filters)
        }
    }

    fun selectScope(scope: GameLogScope) {
        _controls.update { it.copy(scope = scope) }
    }

    fun loadMore() {
        visibleCount.value += PAGE_SIZE
    }

    fun selectRange(range: ActivityRange) {
        _controls.update { it.copy(range = range) }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

private fun ActivityRange.cutoffEpochMillis(nowEpochMs: Long): Long? =
    days?.let { dayCount -> nowEpochMs - Duration.ofDays(dayCount.toLong()).toMillis() }

private fun matchesScope(
    entry: FeedEntry,
    scope: GameLogScope,
    currentLocation: String,
    currentWorldId: String,
): Boolean = when (scope) {
    GameLogScope.ALL_ACTIVITY -> true

    GameLogScope.CURRENT_INSTANCE -> {
        isTrackableLocation(currentLocation) &&
            entry.location.isNotBlank() &&
            entry.location == currentLocation
    }

    GameLogScope.CURRENT_WORLD -> {
        currentWorldId.isNotBlank() &&
            entry.worldId.isNotBlank() &&
            entry.worldId == currentWorldId
    }
}
