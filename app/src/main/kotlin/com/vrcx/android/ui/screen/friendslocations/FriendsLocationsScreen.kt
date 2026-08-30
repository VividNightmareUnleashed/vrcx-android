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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.formatInstanceHint
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.model.resolvePresenceLocation
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.common.whileUiSubscribed
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LocationSegment(val label: String, val emptyMessage: String, val emptySubtitle: String? = null) {
    ONLINE("Online", "No friends in public instances"),
    FAVORITE("Favorites", "No favorite friends in public instances"),
    SAME_INSTANCE(
        "Same Instance",
        "No friends share your current instance",
        "This view matches your live VRChat instance when it is available.",
    ),
    ACTIVE("Active", "No friends active on website"),
    OFFLINE(
        "Offline",
        "No offline friends match those filters",
        "Offline groups use recent public world history when the app has it.",
    ),
    ;

    /** Whether [friend] belongs in this segment, given the signed-in user's [activeLocation]. */
    fun matches(friend: FriendContext, activeLocation: String, favoriteIds: Set<String>): Boolean = when (this) {
        ONLINE ->
            friend.state == FriendState.ONLINE &&
                isTrackableLocation(resolvePresenceLocation(friend.ref))

        FAVORITE -> friend.id in favoriteIds && ONLINE.matches(friend, activeLocation, favoriteIds)

        SAME_INSTANCE ->
            friend.state == FriendState.ONLINE &&
                isTrackableLocation(activeLocation) &&
                resolvePresenceLocation(friend.ref) == activeLocation

        ACTIVE -> friend.state == FriendState.ACTIVE

        OFFLINE -> friend.state == FriendState.OFFLINE
    }
}

private const val WORLD_LOOKUP_WORKER_COUNT = 4
private const val WORLD_LOOKUP_QUEUE_CAPACITY = 64

/**
 * How far a world-detail lookup has got. Holding "failed" explicitly is what
 * stops a world whose lookup threw from being re-queued on every later friends
 * or GPS emission.
 */
private sealed interface WorldLookup {
    data object Pending : WorldLookup
    data object Failed : WorldLookup
    data class Loaded(val world: World) : WorldLookup
}

private fun Map<String, WorldLookup>.world(worldId: String): World? = (this[worldId] as? WorldLookup.Loaded)?.world

private data class LastKnownFriendLocation(
    val location: String,
    val worldId: String,
    val worldName: String,
    val createdAt: String,
)

private data class LocationCriteria(
    val segment: LocationSegment,
    val query: String,
    val worlds: Map<String, WorldLookup>,
    val activeLocation: String,
    val lastKnown: Map<String, LastKnownFriendLocation>,
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
@OptIn(ExperimentalCoroutinesApi::class)
class FriendsLocationsViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val worldRepository: WorldRepository,
    private val feedRepository: FeedRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _selectedSegment = MutableStateFlow(LocationSegment.ONLINE)
    val selectedSegment: StateFlow<LocationSegment> = _selectedSegment.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val worldLookups = MutableStateFlow<Map<String, WorldLookup>>(emptyMap())
    private val worldLookupQueue = Channel<String>(WORLD_LOOKUP_QUEUE_CAPACITY)

    private val ownerUserId = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, "")

    private val currentLocation = authRepository.authState
        .map { state ->
            val user = (state as? AuthState.LoggedIn)?.user
            resolvePresenceLocation(user)
        }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, "")

    private val recentGpsEntries = ownerUserId
        .flatMapLatest { userId ->
            if (userId.isBlank()) {
                flowOf(emptyList())
            } else {
                feedRepository.getGpsFeed(userId, OFFLINE_CONTEXT_LIMIT)
            }
        }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, emptyList())

    private val lastKnownLocations = recentGpsEntries
        .map { entries -> buildLastKnownLocations(entries) }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, emptyMap())

    private val locationCriteria = combine(
        _selectedSegment,
        _searchQuery,
        worldLookups,
        currentLocation,
        lastKnownLocations,
    ) { segment, query, worlds, activeLocation, lastKnown ->
        LocationCriteria(segment, query, worlds, activeLocation, lastKnown)
    }

    val locationGroups: StateFlow<List<LocationGroup>> = combine(
        friendRepository.friends,
        locationCriteria,
        friendRepository.favoriteFriendIds,
    ) { friendsMap, criteria, favoriteIds ->
        val segment = criteria.segment

        val filtered = friendsMap.values.filter {
            segment.matches(it, criteria.activeLocation, favoriteIds)
        }

        val groups = when (segment) {
            // Built from the friends, like every other segment, so an empty
            // roster falls through to the segment's empty state.
            LocationSegment.ACTIVE -> if (filtered.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    LocationGroup(
                        location = "active",
                        worldId = "",
                        worldName = "Active on Website",
                        friends = filtered.sortedByName(),
                        locationHint = "Website presence",
                    ),
                )
            }

            LocationSegment.OFFLINE -> buildOfflineGroups(filtered, criteria.lastKnown, criteria.worlds)

            else -> buildWorldGroups(
                friends = filtered,
                worlds = criteria.worlds,
                currentLocation = if (segment == LocationSegment.SAME_INSTANCE) criteria.activeLocation else "",
            )
        }

        if (criteria.query.isBlank()) {
            groups
        } else {
            groups.filter { group ->
                group.worldName.contains(criteria.query, ignoreCase = true) ||
                    group.locationHint.contains(criteria.query, ignoreCase = true) ||
                    group.friends.any { it.name.contains(criteria.query, ignoreCase = true) }
            }
        }
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, emptyList())

    init {
        repeat(WORLD_LOOKUP_WORKER_COUNT) {
            viewModelScope.launch {
                for (worldId in worldLookupQueue) {
                    val result = try {
                        WorldLookup.Loaded(worldRepository.getWorld(worldId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        WorldLookup.Failed
                    }
                    worldLookups.update { it + (worldId to result) }
                }
            }
        }
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
                    if (worldId in worldLookups.value) continue
                    worldLookups.update { it + (worldId to WorldLookup.Pending) }
                    worldLookupQueue.send(worldId)
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
        worlds: Map<String, WorldLookup>,
    ): List<LocationGroup> = friends
        .groupBy { friend -> lastKnown[friend.id]?.location ?: UNKNOWN_LOCATION_KEY }
        .map { (locationKey, groupedFriends) ->
            val orderedFriends = groupedFriends.sortedByName()
            val lastSeen = orderedFriends.firstNotNullOfOrNull { lastKnown[it.id] }
            val worldId = lastSeen?.worldId.orEmpty()
            val world = worlds.world(worldId)
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
                    worldName =
                        world?.name ?: lastSeen.worldName.ifBlank { worldId.ifBlank { "Last seen location" } },
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
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.worldName },
        )

    private fun buildWorldGroups(
        friends: List<FriendContext>,
        worlds: Map<String, WorldLookup>,
        currentLocation: String,
    ): List<LocationGroup> = friends
        .groupBy { resolvePresenceLocation(it.ref) }
        .filterKeys(::isTrackableLocation)
        .map { (location, groupedFriends) ->
            val orderedFriends = groupedFriends.sortedByName()
            val worldId = parseWorldId(location)
            val world = worlds.world(worldId)
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
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.worldName },
        )
}

private fun List<FriendContext>.sortedByName(): List<FriendContext> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

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
        FriendsLocationControls(
            selectedSegment = selectedSegment,
            searchQuery = searchQuery,
            onSegmentClick = viewModel::selectSegment,
            onSearchChange = viewModel::updateSearch,
        )
        FriendsLocationContent(
            groups = groups,
            selectedSegment = selectedSegment,
            onUserClick = onUserClick,
            onWorldClick = onWorldClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FriendsLocationControls(
    selectedSegment: LocationSegment,
    searchQuery: String,
    onSegmentClick: (LocationSegment) -> Unit,
    onSearchChange: (String) -> Unit,
) {
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
                onClick = { onSegmentClick(segment) },
                label = { Text(segment.label) },
            )
        }
    }

    VrcxSearchBar(
        query = searchQuery,
        onQueryChange = onSearchChange,
        placeholder = "Search worlds or friends",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FriendsLocationContent(
    groups: List<LocationGroup>,
    selectedSegment: LocationSegment,
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState(
            message = selectedSegment.emptyMessage,
            icon = Icons.Outlined.LocationOn,
            subtitle = selectedSegment.emptySubtitle,
        )
    } else {
        // Headers and friend rows stay separate so large groups virtualize per friend.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            groups.forEach { group ->
                item(key = "header_${group.location}") {
                    LocationGroupHeader(
                        group = group,
                        onWorldClick = { onWorldClick(group.worldId) },
                    )
                }
                items(group.friends, key = { "${group.location}_${it.id}" }) { friend ->
                    FriendLocationRow(friend, onUserClick)
                }
            }
        }
    }
}

@Composable
private fun FriendLocationRow(friend: FriendContext, onUserClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(friend.id) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            imageUrl = friend.ref?.displayAvatarUrl(),
            status = friend.ref?.status,
            state = friend.state,
            size = 32.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(friend.name, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The world card that opens a flattened group and carries its clickable world link. */
@Composable
private fun LocationGroupHeader(group: LocationGroup, onWorldClick: () -> Unit) {
    VrcxCard(
        modifier = Modifier.padding(top = 12.dp),
        onClick = if (group.worldId.startsWith("wrld_")) onWorldClick else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
    }
}

private const val OFFLINE_CONTEXT_LIMIT = 500
private const val UNKNOWN_LOCATION_KEY = "__unknown_last_location__"

private fun buildLastKnownLocations(entries: List<FeedEntry>): Map<String, LastKnownFriendLocation> {
    val latestByUser = linkedMapOf<String, LastKnownFriendLocation>()
    entries.forEach { entry ->
        if (!isTrackableLocation(entry.location) || entry.userId in latestByUser) return@forEach
        latestByUser[entry.userId] = LastKnownFriendLocation(
            location = entry.location,
            worldId = entry.worldId,
            worldName = entry.worldName,
            createdAt = entry.createdAt,
        )
    }
    return latestByUser
}
