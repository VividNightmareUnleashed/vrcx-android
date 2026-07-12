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
import androidx.compose.foundation.layout.width
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
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.formatInstanceHint
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.model.resolvePresenceLocation
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.detailText
import com.vrcx.android.data.repository.headline
import com.vrcx.android.data.repository.previousDetailText
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
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameLogViewModel @Inject constructor(
    authRepository: AuthRepository,
    feedRepository: FeedRepository,
    friendRepository: FriendRepository,
    preferences: VrcxPreferences,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _vipOnly = MutableStateFlow(false)
    val vipOnly: StateFlow<Boolean> = _vipOnly.asStateFlow()

    private val _filters = MutableStateFlow(setOf("gps", "status", "bio", "avatar", "online", "offline"))
    val filters: StateFlow<Set<String>> = _filters.asStateFlow()

    private val _range = MutableStateFlow(ActivityRange.LAST_7)
    val range: StateFlow<ActivityRange> = _range.asStateFlow()

    private val _scope = MutableStateFlow(GameLogScope.ALL_ACTIVITY)
    val scope: StateFlow<GameLogScope> = _scope.asStateFlow()

    private val _limit = MutableStateFlow(100)
    val limit: StateFlow<Int> = _limit.asStateFlow()

    val currentLocation = authRepository.authState
        .map { state -> resolvePresenceLocation((state as? AuthState.LoggedIn)?.user) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val currentWorldId = currentLocation
        .map(::parseWorldId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val maxFeedSize = preferences.maxFeedSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1000)

    private val vipFriendIds = friendRepository.friends
        .map { friends -> friends.values.filter { it.isVIP }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val allEntries = combine(
        authRepository.authState.map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() },
        maxFeedSize,
    ) { userId, maxSize ->
        userId to maxSize
    }.flatMapLatest { (userId, maxSize) ->
        if (userId.isBlank()) {
            flowOf(emptyList())
        } else {
            feedRepository.getUnifiedFeed(userId, maxSize).map { it.entries }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val filteredEntries = combine(
        allEntries,
        _filters,
        _searchQuery,
        _vipOnly,
        vipFriendIds,
        _scope,
        currentLocation,
        currentWorldId,
        _range,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<FeedEntry>
        val filters = values[1] as Set<*>
        val query = values[2] as String
        val vipOnly = values[3] as Boolean
        val vipIds = values[4] as Set<*>
        val scope = values[5] as GameLogScope
        val currentLocation = values[6] as String
        val currentWorldId = values[7] as String
        val range = values[8] as ActivityRange

        val cutoffMs = range.days?.let {
            System.currentTimeMillis() - it.toLong() * 24L * 60L * 60L * 1000L
        }

        all
            .filter { it.type.id in filters }
            .filter { entry -> matchesScope(entry, scope, currentLocation, currentWorldId) }
            .filter { entry ->
                cutoffMs == null || entry.createdAtEpochMs >= cutoffMs
            }
            .filter { entry ->
                query.isBlank() ||
                    entry.displayName.contains(query, ignoreCase = true) ||
                    entry.headline().contains(query, ignoreCase = true) ||
                    entry.detailText().contains(query, ignoreCase = true) ||
                    entry.previousDetailText().contains(query, ignoreCase = true) ||
                    formatInstanceHint(entry.location).contains(query, ignoreCase = true)
            }
            .filter { entry -> if (vipOnly) entry.userId in vipIds else true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val entries = combine(filteredEntries, _limit) { filtered, limit ->
        filtered.take(limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val canLoadMore = combine(filteredEntries, _limit, maxFeedSize) { filtered, limit, maxSize ->
        filtered.size > limit && limit < maxSize * GAME_LOG_SOURCE_COUNT
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            maxFeedSize.collect { maxSize ->
                _limit.value = _limit.value.coerceAtMost(maxSize * GAME_LOG_SOURCE_COUNT)
            }
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleVipOnly() {
        _vipOnly.value = !_vipOnly.value
    }

    fun toggleFilter(filter: String) {
        val current = _filters.value.toMutableSet()
        if (filter in current) current.remove(filter) else current.add(filter)
        _filters.value = current
    }

    fun selectScope(scope: GameLogScope) {
        _scope.value = scope
    }

    fun loadMore() {
        _limit.value = (_limit.value + 100).coerceAtMost(maxFeedSize.value * GAME_LOG_SOURCE_COUNT)
    }

    fun selectRange(range: ActivityRange) {
        _range.value = range
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GameLogScreen(
    viewModel: GameLogViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val vipOnly by viewModel.vipOnly.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val canLoadMore by viewModel.canLoadMore.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()

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

        if (scope != GameLogScope.ALL_ACTIVITY && !isTrackableLocation(currentLocation)) {
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
            listOf(
                "gps" to "Location",
                "status" to "Status",
                "bio" to "Bio",
                "avatar" to "Avatar",
                "online" to "Online",
                "offline" to "Offline",
            ).forEach { (filter, label) ->
                FilterChip(
                    selected = filter in filters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(label) },
                )
            }
        }

        if (entries.isEmpty()) {
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
                items(entries, key = { "${it.type.id}_${it.id}" }) { entry ->
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
                if (canLoadMore) {
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

private const val GAME_LOG_SOURCE_COUNT = 5

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
