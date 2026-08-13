package com.vrcx.android.ui.screen.gamelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.vrcx.android.ui.common.FeedFilter
import com.vrcx.android.ui.common.applyFeedFilter
import com.vrcx.android.ui.common.detailText
import com.vrcx.android.ui.common.headline
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.previousDetailText
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Date-range filter for the Activity History screen. Lets users zoom in on
 * recent windows without scrolling through the full feed.
 */
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

private data class GameLogLocationCriteria(
    val scope: GameLogScope,
    val currentLocation: String,
    val currentWorldId: String,
    val range: ActivityRange,
)

/**
 * Everything Activity History renders, published as one value: the rows, whether
 * older ones are still held back, and whether the selected scope can be matched
 * at all. Derived together so the list, the "Load More" affordance and the scope
 * hint always describe the same page.
 */
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
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    private val _filters = MutableStateFlow(FeedEntryType.entries.toSet())
    val filters: StateFlow<Set<FeedEntryType>> = _filters.asStateFlow()

    private val _range = MutableStateFlow(ActivityRange.LAST_7)
    val range: StateFlow<ActivityRange> = _range.asStateFlow()

    private val _scope = MutableStateFlow(GameLogScope.ALL_ACTIVITY)
    val scope: StateFlow<GameLogScope> = _scope.asStateFlow()

    private val _visibleCount = MutableStateFlow(PAGE_SIZE)

    private val currentLocation = authRepository.authState
        .map { state -> resolvePresenceLocation((state as? AuthState.LoggedIn)?.user) }

    private val allEntries = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() }
        .flatMapLatest { userId ->
            if (userId.isBlank()) flowOf(emptyList()) else feedRepository.getUnifiedFeed(userId)
        }

    private val filter = combine(
        _filters,
        _searchQuery,
        _vipOnly,
        friendRepository.favoriteFriendIds,
        ::FeedFilter,
    )

    private val locationCriteria = combine(
        _scope,
        currentLocation,
        _range,
    ) { scope, location, range ->
        GameLogLocationCriteria(scope, location, parseWorldId(location), range)
    }

    val page: StateFlow<GameLogPage> = combine(
        allEntries,
        filter,
        locationCriteria,
        _visibleCount,
    ) { all, filter, location, visibleCount ->
        val cutoffMs = location.range.days?.let {
            System.currentTimeMillis() - it.toLong() * 24L * 60L * 60L * 1000L
        }

        val matching = all.applyFeedFilter(filter)
            .filter { entry ->
                matchesScope(entry, location.scope, location.currentLocation, location.currentWorldId)
            }
            .filter { entry ->
                cutoffMs == null || entry.createdAtEpochMs >= cutoffMs
            }

        GameLogPage(
            entries = matching.take(visibleCount),
            // The merged list is already bounded — the repository caps each
            // source table at the configured history size — so the page limit
            // only has to stop where the rows do.
            canLoadMore = matching.size > visibleCount,
            showScopeHint = location.scope != GameLogScope.ALL_ACTIVITY &&
                !isTrackableLocation(location.currentLocation),
        )
    }
        .stateIn(derivationScope, SharingStarted.WhileSubscribed(5000), GameLogPage())

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleVipOnly() {
        _vipOnly.value = !_vipOnly.value
    }

    fun toggleFilter(filter: FeedEntryType) {
        val current = _filters.value.toMutableSet()
        if (filter in current) current.remove(filter) else current.add(filter)
        _filters.value = current
    }

    fun selectScope(scope: GameLogScope) {
        _scope.value = scope
    }

    fun loadMore() {
        _visibleCount.value += PAGE_SIZE
    }

    fun selectRange(range: ActivityRange) {
        _range.value = range
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GameLogScreen(
    viewModel: GameLogViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val vipOnly by viewModel.vipOnly.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Renamed from "Game Log" — Android can't tail VRChat's client log so
        // this screen actually holds friend-presence and feed activity history.
        VrcxDetailTopBar(title = "Activity History", onBack = onBack)

        Text(
            text = "Friend activity history built from location, presence, and profile updates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search people, worlds, or activity",
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityRange.entries.forEach { option ->
                FilterChip(
                    selected = range == option,
                    onClick = { viewModel.selectRange(option) },
                    label = { Text(option.label) },
                )
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameLogScope.entries.forEach { candidate ->
                FilterChip(
                    selected = scope == candidate,
                    onClick = { viewModel.selectScope(candidate) },
                    label = { Text(candidate.label) },
                )
            }
        }

        if (page.showScopeHint) {
            Text(
                text = "Scoped views only include entries with world or instance context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = vipOnly,
                onClick = viewModel::toggleVipOnly,
                label = { Text("VIP") },
                leadingIcon = if (vipOnly) {
                    { Icon(Icons.Outlined.Star, contentDescription = null) }
                } else {
                    null
                },
            )
            FeedEntryType.entries.forEach { filter ->
                FilterChip(
                    selected = filter in filters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        if (page.entries.isEmpty()) {
            EmptyState(
                message = when (scope) {
                    GameLogScope.CURRENT_INSTANCE -> "No instance-matched activity in this window"
                    GameLogScope.CURRENT_WORLD -> "No current-world activity in this window"
                    GameLogScope.ALL_ACTIVITY -> "No activity in this window"
                },
                icon = Icons.Outlined.History,
                subtitle = when (scope) {
                    GameLogScope.ALL_ACTIVITY -> "Friend movement, status changes, and avatar updates from the selected range appear here."
                    else -> "Scoped views only show entries that include location context."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(page.entries, key = { it.key }) { entry ->
                    VrcxCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .fillMaxWidth(),
                        onClick = { onUserClick(entry.userId) },
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    relativeTime(entry.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(entry.headline(), style = MaterialTheme.typography.bodyMedium)
                            val detail = entry.detailText()
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val previousDetail = entry.previousDetailText()
                            if (previousDetail.isNotBlank()) {
                                Text(
                                    "Previous: $previousDetail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (page.canLoadMore) {
                    item {
                        OutlinedButton(
                            onClick = viewModel::loadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

private fun matchesScope(
    entry: FeedEntry,
    scope: GameLogScope,
    currentLocation: String,
    currentWorldId: String,
): Boolean {
    return when (scope) {
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
}
