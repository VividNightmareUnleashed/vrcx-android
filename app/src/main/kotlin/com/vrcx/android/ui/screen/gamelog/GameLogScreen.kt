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
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
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

    private val _limit = MutableStateFlow(100)
    val limit: StateFlow<Int> = _limit.asStateFlow()

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
        if (userId.isBlank()) return@flatMapLatest flowOf(emptyList())
            combine(
                feedRepository.getGpsFeed(userId, maxSize),
                feedRepository.getStatusFeed(userId, maxSize),
                feedRepository.getBioFeed(userId, maxSize),
                feedRepository.getAvatarFeed(userId, maxSize),
                feedRepository.getOnlineOfflineFeed(userId, maxSize),
            ) { gps, status, bio, avatar, onlineOffline ->
                buildList {
                    gps.forEach { add(FeedEntry(it.id, "gps", it.userId, it.displayName, it.worldName, it.previousLocation, it.createdAt)) }
                    status.forEach {
                        add(
                            FeedEntry(
                                it.id,
                                "status",
                                it.userId,
                                it.displayName,
                                "${it.status}: ${it.statusDescription}",
                                "${it.previousStatus}: ${it.previousStatusDescription}",
                                it.createdAt,
                            )
                        )
                    }
                    bio.forEach { add(FeedEntry(it.id, "bio", it.userId, it.displayName, it.bio, it.previousBio, it.createdAt)) }
                    avatar.forEach {
                        add(
                            FeedEntry(
                                it.id,
                                "avatar",
                                it.userId,
                                it.displayName,
                                it.avatarName.ifEmpty { "Avatar changed" },
                                "",
                                it.createdAt,
                                it.currentAvatarThumbnailImageUrl,
                            )
                        )
                    }
                    onlineOffline.forEach { add(FeedEntry(it.id, it.type, it.userId, it.displayName, it.worldName, "", it.createdAt)) }
                }.sortedByDescending { it.createdAt }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val entries = combine(
        listOf(allEntries, _filters, _searchQuery, _vipOnly, vipFriendIds, _limit, _range),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<FeedEntry>
        val filters = values[1] as Set<*>
        val query = values[2] as String
        val vipOnly = values[3] as Boolean
        val vipIds = values[4] as Set<*>
        val limit = values[5] as Int
        val range = values[6] as ActivityRange

        // Drop entries older than the chosen window. ALL skips the date filter.
        val cutoffMs = range.days?.let {
            System.currentTimeMillis() - it.toLong() * 24L * 60L * 60L * 1000L
        }

        all
            .filter { it.type in filters }
            .filter { entry ->
                cutoffMs == null || parseInstantMs(entry.createdAt) >= cutoffMs
            }
            .filter { entry ->
                query.isBlank() ||
                    entry.displayName.contains(query, ignoreCase = true) ||
                    entry.details.contains(query, ignoreCase = true)
            }
            .filter { if (vipOnly) it.userId in vipIds else true }
            .take(limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun parseInstantMs(iso: String): Long {
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
    }

    val canLoadMore = combine(entries, allEntries, _limit, maxFeedSize) { visible, all, limit, maxSize ->
        visible.size >= limit && all.size >= limit && limit < maxSize
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            maxFeedSize.collect { maxSize ->
                _limit.value = _limit.value.coerceAtMost(maxSize)
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

    fun loadMore() {
        _limit.value = (_limit.value + 100).coerceAtMost(maxFeedSize.value)
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
    val canLoadMore by viewModel.canLoadMore.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Renamed from "Game Log" — Android can't tail VRChat's client log so
        // this screen actually holds friend-presence and feed activity history.
        VrcxDetailTopBar(title = "Activity History", onBack = onBack)

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search activity history",
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
            listOf("gps", "status", "bio", "avatar", "online", "offline").forEach { filter ->
                FilterChip(
                    selected = filter in filters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (entries.isEmpty()) {
            EmptyState(
                message = "No activity in this window",
                icon = Icons.Outlined.History,
                subtitle = "Friend movement, status changes, and avatar updates from the selected range appear here.",
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
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    relativeTime(entry.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when (entry.type) {
                                    "gps" -> "Location: ${entry.details}"
                                    "status" -> entry.details
                                    "bio" -> "Bio updated"
                                    "avatar" -> "Avatar: ${entry.details}"
                                    "online" -> "Friend came online"
                                    "offline" -> "Friend went offline"
                                    else -> entry.details
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
