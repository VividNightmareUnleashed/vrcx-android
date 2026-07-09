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
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.db.entity.FeedAvatarEntity
import com.vrcx.android.data.db.entity.FeedBioEntity
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.db.entity.FeedStatusEntity
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
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

data class GameLogEntryUi(
    val id: Long,
    val type: String,
    val userId: String,
    val displayName: String,
    val headline: String,
    val details: String,
    val previousDetails: String = "",
    val createdAt: String,
    val thumbnailUrl: String = "",
    val location: String = "",
    val worldId: String = "",
)

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
            combine(
                feedRepository.getGpsFeed(userId, maxSize),
                feedRepository.getStatusFeed(userId, maxSize),
                feedRepository.getBioFeed(userId, maxSize),
                feedRepository.getAvatarFeed(userId, maxSize),
                feedRepository.getOnlineOfflineFeed(userId, maxSize),
            ) { gps, status, bio, avatar, onlineOffline ->
                buildList {
                    gps.forEach { add(it.toGameLogEntry()) }
                    status.forEach { add(it.toGameLogEntry()) }
                    bio.forEach { add(it.toGameLogEntry()) }
                    avatar.forEach { add(it.toGameLogEntry()) }
                    onlineOffline.forEach { add(it.toGameLogEntry()) }
                }.sortedByDescending { it.createdAt }
            }
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
        val all = values[0] as List<GameLogEntryUi>
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
            .filter { it.type in filters }
            .filter { entry -> matchesScope(entry, scope, currentLocation, currentWorldId) }
            .filter { entry ->
                cutoffMs == null || parseInstantMs(entry.createdAt) >= cutoffMs
            }
            .filter { entry ->
                query.isBlank() ||
                    entry.displayName.contains(query, ignoreCase = true) ||
                    entry.headline.contains(query, ignoreCase = true) ||
                    entry.details.contains(query, ignoreCase = true) ||
                    entry.previousDetails.contains(query, ignoreCase = true) ||
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

    private fun parseInstantMs(iso: String): Long {
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
    }

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
                items(entries, key = { "${it.type}_${it.id}" }) { entry ->
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
                            Text(entry.headline, style = MaterialTheme.typography.bodyMedium)
                            if (entry.details.isNotBlank()) {
                                Text(
                                    entry.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (entry.previousDetails.isNotBlank()) {
                                Text(
                                    "Previous: ${entry.previousDetails}",
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

private fun FeedGpsEntity.toGameLogEntry(): GameLogEntryUi {
    return GameLogEntryUi(
        id = id,
        type = "gps",
        userId = userId,
        displayName = displayName,
        headline = "Location update",
        details = worldName.ifBlank { formatLocationLabel(location) },
        previousDetails = formatLocationLabel(previousLocation),
        createdAt = createdAt,
        location = location,
        worldId = parseWorldId(location),
    )
}

private fun FeedStatusEntity.toGameLogEntry(): GameLogEntryUi {
    val currentStatus = listOf(status, statusDescription)
        .filter { it.isNotBlank() }
        .joinToString(": ")
    val previousStatusText = listOf(previousStatus, previousStatusDescription)
        .filter { it.isNotBlank() }
        .joinToString(": ")
    return GameLogEntryUi(
        id = id,
        type = "status",
        userId = userId,
        displayName = displayName,
        headline = "Status update",
        details = currentStatus.ifBlank { "Status changed" },
        previousDetails = previousStatusText,
        createdAt = createdAt,
    )
}

private fun FeedBioEntity.toGameLogEntry(): GameLogEntryUi {
    return GameLogEntryUi(
        id = id,
        type = "bio",
        userId = userId,
        displayName = displayName,
        headline = "Bio updated",
        details = bio,
        previousDetails = previousBio,
        createdAt = createdAt,
    )
}

private fun FeedAvatarEntity.toGameLogEntry(): GameLogEntryUi {
    return GameLogEntryUi(
        id = id,
        type = "avatar",
        userId = userId,
        displayName = displayName,
        headline = "Avatar changed",
        details = avatarName.ifBlank { "Avatar updated" },
        createdAt = createdAt,
        thumbnailUrl = currentAvatarThumbnailImageUrl,
    )
}

private fun FeedOnlineOfflineEntity.toGameLogEntry(): GameLogEntryUi {
    val label = when (type) {
        "online" -> "Came online"
        "offline" -> "Went offline"
        else -> type.replaceFirstChar { it.uppercase() }
    }
    return GameLogEntryUi(
        id = id,
        type = type,
        userId = userId,
        displayName = displayName,
        headline = label,
        details = when {
            worldName.isNotBlank() -> worldName
            location.isNotBlank() -> formatLocationLabel(location)
            type == "offline" -> "Offline"
            else -> "Presence update"
        },
        createdAt = createdAt,
        location = location,
        worldId = parseWorldId(location),
    )
}

private fun matchesScope(
    entry: GameLogEntryUi,
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

private fun resolvePresenceLocation(user: CurrentUser?): String {
    return when (user?.location) {
        "traveling" -> user.travelingToLocation.orEmpty()
        else -> user?.location.orEmpty()
    }
}

private fun isTrackableLocation(location: String): Boolean {
    return location.isNotBlank() &&
        location != "offline" &&
        location != "private" &&
        location != "traveling"
}

private fun parseWorldId(location: String): String {
    return location.substringBefore(":").takeIf { it.startsWith("wrld_") }.orEmpty()
}

private fun formatLocationLabel(location: String): String {
    val worldId = parseWorldId(location)
    val instanceHint = formatInstanceHint(location)
    return when {
        worldId.isNotBlank() && instanceHint.isNotBlank() -> "$worldId • $instanceHint"
        worldId.isNotBlank() -> worldId
        location.isBlank() -> ""
        else -> location
    }
}

private fun formatInstanceHint(location: String): String {
    val instanceLabel = location.substringAfter(":", "").substringBefore("~")
    return if (instanceLabel.isBlank()) "" else "instance $instanceLabel"
}
