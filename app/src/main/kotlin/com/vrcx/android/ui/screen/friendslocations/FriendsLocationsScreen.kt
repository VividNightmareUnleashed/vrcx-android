package com.vrcx.android.ui.screen.friendslocations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.db.entity.FeedGpsEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LocationSegment(val label: String) {
    ONLINE("Online"),
    FAVORITE("Favorites"),
    SAME_INSTANCE("Same Instance"),
    ACTIVE("Active"),
    OFFLINE("Offline"),
}

private data class LastKnownFriendLocation(
    val location: String,
    val worldId: String,
    val worldName: String,
    val createdAt: String,
)

data class LocationGroup(
    val location: String,
    val worldId: String,
    val worldName: String,
    val worldThumbnailUrl: String = "",
    val worldCapacity: Int = 0,
    val friends: List<FriendContext>,
    val updatedAt: String = "",
    val locationHint: String = "",
)

@HiltViewModel
class FriendsLocationsViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val worldRepository: WorldRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {
    private val _selectedSegment = MutableStateFlow(LocationSegment.ONLINE)
    val selectedSegment: StateFlow<LocationSegment> = _selectedSegment.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _worldNames = MutableStateFlow<Map<String, World>>(emptyMap())

    private val ownerUserId = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val currentLocation = authRepository.authState
        .map { state ->
            val user = (state as? AuthState.LoggedIn)?.user
            resolvePresenceLocation(user)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val recentGpsEntries = ownerUserId
        .flatMapLatest { userId ->
            if (userId.isBlank()) {
                flowOf(emptyList())
            } else {
                feedRepository.getGpsFeed(userId, OFFLINE_CONTEXT_LIMIT)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val lastKnownLocations = recentGpsEntries
        .map { entries -> buildLastKnownLocations(entries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val locationGroups: StateFlow<List<LocationGroup>> = combine(
        friendRepository.friends,
        _selectedSegment,
        _searchQuery,
        _worldNames,
        currentLocation,
        lastKnownLocations,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val friendsMap = values[0] as Map<String, FriendContext>
        val segment = values[1] as LocationSegment
        val query = values[2] as String
        val worlds = values[3] as Map<String, World>
        val activeLocation = values[4] as String
        val lastKnown = values[5] as Map<String, LastKnownFriendLocation>

        val filtered = when (segment) {
            LocationSegment.ONLINE -> friendsMap.values.filter { friend ->
                friend.state == FriendState.ONLINE &&
                    isTrackableLocation(resolvePresenceLocation(friend.ref))
            }
            LocationSegment.FAVORITE -> friendsMap.values.filter { friend ->
                friend.isVIP &&
                    friend.state == FriendState.ONLINE &&
                    isTrackableLocation(resolvePresenceLocation(friend.ref))
            }
            LocationSegment.SAME_INSTANCE -> {
                if (!isTrackableLocation(activeLocation)) {
                    emptyList()
                } else {
                    friendsMap.values.filter { friend ->
                        friend.state == FriendState.ONLINE &&
                            resolvePresenceLocation(friend.ref) == activeLocation
                    }
                }
            }
            LocationSegment.ACTIVE -> friendsMap.values.filter { it.state == FriendState.ACTIVE }
            LocationSegment.OFFLINE -> friendsMap.values.filter { it.state == FriendState.OFFLINE }
        }

        val groups = when (segment) {
            LocationSegment.ACTIVE -> listOf(
                LocationGroup(
                    location = "active",
                    worldId = "",
                    worldName = "Active on Website",
                    friends = filtered.sortedBy { it.name.lowercase() },
                    locationHint = "Website presence",
                )
            )
            LocationSegment.OFFLINE -> buildOfflineGroups(filtered, lastKnown, worlds)
            else -> buildWorldGroups(
                friends = filtered,
                worlds = worlds,
                currentLocation = if (segment == LocationSegment.SAME_INSTANCE) activeLocation else "",
            )
        }

        if (query.isBlank()) {
            groups
        } else {
            groups.filter { group ->
                group.worldName.contains(query, ignoreCase = true) ||
                    group.locationHint.contains(query, ignoreCase = true) ||
                    group.friends.any { it.name.contains(query, ignoreCase = true) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(friendRepository.friends, recentGpsEntries) { friendsMap, gpsEntries ->
                buildSet {
                    friendsMap.values
                        .map { resolvePresenceLocation(it.ref) }
                        .filter(::isTrackableLocation)
                        .mapTo(this, ::parseWorldId)
                    gpsEntries
                        .map { it.location }
                        .filter(::isTrackableLocation)
                        .mapTo(this, ::parseWorldId)
                }.filter { it.isNotBlank() }
            }.collect { worldIds ->
                for (worldId in worldIds) {
                    if (_worldNames.value.containsKey(worldId)) continue
                    launch {
                        runCatching { worldRepository.getWorld(worldId) }
                            .onSuccess { world ->
                                _worldNames.update { it + (worldId to world) }
                            }
                    }
                }
            }
        }
    }

    fun selectSegment(segment: LocationSegment) {
        _selectedSegment.value = segment
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    private fun buildOfflineGroups(
        friends: List<FriendContext>,
        lastKnown: Map<String, LastKnownFriendLocation>,
        worlds: Map<String, World>,
    ): List<LocationGroup> {
        return friends
            .groupBy { friend -> lastKnown[friend.id]?.location ?: UNKNOWN_LOCATION_KEY }
            .map { (locationKey, groupedFriends) ->
                val orderedFriends = groupedFriends.sortedBy { it.name.lowercase() }
                val lastSeen = orderedFriends.firstNotNullOfOrNull { lastKnown[it.id] }
                val worldId = lastSeen?.worldId.orEmpty()
                val world = worlds[worldId]
                when {
                    lastSeen == null -> LocationGroup(
                        location = locationKey,
                        worldId = "",
                        worldName = "Last location unknown",
                        friends = orderedFriends,
                        locationHint = "No recent public world location recorded",
                    )

                    else -> LocationGroup(
                        location = lastSeen.location,
                        worldId = worldId,
                        worldName = world?.name ?: lastSeen.worldName.ifBlank { worldId.ifBlank { "Last seen location" } },
                        worldThumbnailUrl = world?.thumbnailImageUrl.orEmpty(),
                        worldCapacity = world?.capacity ?: 0,
                        friends = orderedFriends,
                        updatedAt = lastSeen.createdAt,
                        locationHint = formatInstanceHint(lastSeen.location),
                    )
                }
            }
            .sortedWith(
                compareByDescending<LocationGroup> { it.updatedAt }
                    .thenByDescending { it.friends.size }
                    .thenBy { it.worldName.lowercase() }
            )
    }

    private fun buildWorldGroups(
        friends: List<FriendContext>,
        worlds: Map<String, World>,
        currentLocation: String,
    ): List<LocationGroup> {
        return friends
            .groupBy { resolvePresenceLocation(it.ref) }
            .filterKeys(::isTrackableLocation)
            .map { (location, groupedFriends) ->
                val orderedFriends = groupedFriends.sortedBy { it.name.lowercase() }
                val worldId = parseWorldId(location)
                val world = worlds[worldId]
                LocationGroup(
                    location = location,
                    worldId = worldId,
                    worldName = world?.name ?: worldId.ifBlank { "Unknown world" },
                    worldThumbnailUrl = world?.thumbnailImageUrl.orEmpty(),
                    worldCapacity = world?.capacity ?: 0,
                    friends = orderedFriends,
                    locationHint = when {
                        currentLocation.isNotBlank() && location == currentLocation -> "Your current instance"
                        else -> formatInstanceHint(location)
                    },
                )
            }
            .sortedWith(
                compareByDescending<LocationGroup> { it.location == currentLocation }
                    .thenByDescending { it.friends.size }
                    .thenBy { it.worldName.lowercase() }
            )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FriendsLocationsScreen(
    viewModel: FriendsLocationsViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val groups by viewModel.locationGroups.collectAsStateWithLifecycle()
    val selectedSegment by viewModel.selectedSegment.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Friends Locations", onBack = onBack)

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocationSegment.entries.forEach { segment ->
                FilterChip(
                    selected = selectedSegment == segment,
                    onClick = { viewModel.selectSegment(segment) },
                    label = { Text(segment.label) },
                )
            }
        }

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search worlds or friends",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (groups.isEmpty()) {
            EmptyState(
                message = when (selectedSegment) {
                    LocationSegment.ONLINE -> "No friends in public instances"
                    LocationSegment.FAVORITE -> "No favorite friends in public instances"
                    LocationSegment.SAME_INSTANCE -> "No friends share your current instance"
                    LocationSegment.ACTIVE -> "No friends active on website"
                    LocationSegment.OFFLINE -> "No offline friends match those filters"
                },
                icon = Icons.Outlined.LocationOn,
                subtitle = when (selectedSegment) {
                    LocationSegment.SAME_INSTANCE -> "This view matches your live VRChat instance when it is available."
                    LocationSegment.OFFLINE -> "Offline groups use recent public world history when the app has it."
                    else -> null
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(groups, key = { it.location }) { group ->
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = group.worldId.startsWith("wrld_")) {
                                        onWorldClick(group.worldId)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (group.worldThumbnailUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = group.worldThumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(group.worldName, style = MaterialTheme.typography.titleSmall)
                                    val subtitleParts = buildList {
                                        add("${group.friends.size} friends")
                                        if (group.worldCapacity > 0) add("capacity ${group.worldCapacity}")
                                        if (group.updatedAt.isNotBlank()) add("last seen ${relativeTime(group.updatedAt)}")
                                        if (group.locationHint.isNotBlank()) add(group.locationHint)
                                    }
                                    Text(
                                        subtitleParts.joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            group.friends.forEach { friend ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onUserClick(friend.id) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UserAvatar(
                                        imageUrl = friend.ref?.currentAvatarThumbnailImageUrl,
                                        status = friend.ref?.status,
                                        state = friend.state,
                                        size = 32.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(friend.name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val OFFLINE_CONTEXT_LIMIT = 500
private const val UNKNOWN_LOCATION_KEY = "__unknown_last_location__"

private fun buildLastKnownLocations(entries: List<FeedGpsEntity>): Map<String, LastKnownFriendLocation> {
    val latestByUser = linkedMapOf<String, LastKnownFriendLocation>()
    entries.forEach { entry ->
        if (!isTrackableLocation(entry.location) || entry.userId in latestByUser) return@forEach
        latestByUser[entry.userId] = LastKnownFriendLocation(
            location = entry.location,
            worldId = parseWorldId(entry.location),
            worldName = entry.worldName,
            createdAt = entry.createdAt,
        )
    }
    return latestByUser
}

private fun resolvePresenceLocation(user: CurrentUser?): String {
    return when (user?.location) {
        "traveling" -> user.travelingToLocation.orEmpty()
        else -> user?.location.orEmpty()
    }
}

private fun resolvePresenceLocation(friend: com.vrcx.android.data.api.model.VrcUser?): String {
    return when (friend?.location) {
        "traveling" -> friend.travelingToLocation.orEmpty()
        else -> friend?.location.orEmpty()
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

private fun formatInstanceHint(location: String): String {
    val instanceLabel = location.substringAfter(":", "").substringBefore("~")
    return if (instanceLabel.isBlank()) "" else "Instance $instanceLabel"
}
