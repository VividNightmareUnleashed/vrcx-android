package com.vrcx.android.ui.screen.friendslocations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LocationSegment { ONLINE, FAVORITE, ACTIVE }

data class LocationGroup(
    val location: String,
    val worldId: String,
    val worldName: String,
    val worldThumbnailUrl: String = "",
    val worldCapacity: Int = 0,
    val friends: List<FriendContext>,
)

@HiltViewModel
class FriendsLocationsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
    private val worldRepository: WorldRepository,
) : ViewModel() {
    private val _selectedSegment = MutableStateFlow(LocationSegment.ONLINE)
    val selectedSegment: StateFlow<LocationSegment> = _selectedSegment.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _worldNames = MutableStateFlow<Map<String, World>>(emptyMap())

    val locationGroups: StateFlow<List<LocationGroup>> = combine(
        friendRepository.friends,
        _selectedSegment,
        _searchQuery,
        _worldNames,
    ) { friendsMap, segment, query, worlds ->
        val filtered = when (segment) {
            LocationSegment.ONLINE -> friendsMap.values.filter {
                it.state == FriendState.ONLINE && it.ref?.location != null &&
                    it.ref.location != "private" && it.ref.location != "offline"
            }
            LocationSegment.FAVORITE -> friendsMap.values.filter {
                it.isVIP && it.state == FriendState.ONLINE && it.ref?.location != null &&
                    it.ref.location != "private" && it.ref.location != "offline"
            }
            LocationSegment.ACTIVE -> friendsMap.values.filter { it.state == FriendState.ACTIVE }
        }

        val groups = if (segment == LocationSegment.ACTIVE) {
            listOf(LocationGroup("active", "", "Active on Website", friends = filtered.sortedBy { it.name.lowercase() }))
        } else {
            filtered
                .groupBy { it.ref?.location ?: "" }
                .filter { it.key.isNotEmpty() }
                .map { (location, friends) ->
                    val worldId = location.substringBefore(":")
                    val world = worlds[worldId]
                    LocationGroup(
                        location = location,
                        worldId = worldId,
                        worldName = world?.name ?: worldId,
                        worldThumbnailUrl = world?.thumbnailImageUrl ?: "",
                        worldCapacity = world?.capacity ?: 0,
                        friends = friends.sortedBy { it.name.lowercase() },
                    )
                }
                .sortedByDescending { it.friends.size }
        }

        if (query.isBlank()) groups
        else groups.filter { group ->
            group.worldName.contains(query, ignoreCase = true) ||
                group.friends.any { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Resolve world names as friends come online
        viewModelScope.launch {
            friendRepository.friends.collect { friendsMap ->
                val worldIds = friendsMap.values
                    .filter { it.state == FriendState.ONLINE && it.ref?.location != null }
                    .mapNotNull { it.ref?.location?.substringBefore(":") }
                    .filter { it.startsWith("wrld_") }
                    .distinct()

                for (worldId in worldIds) {
                    if (!_worldNames.value.containsKey(worldId)) {
                        launch {
                            try {
                                val world = worldRepository.getWorld(worldId)
                                _worldNames.update { it + (worldId to world) }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    fun selectSegment(segment: LocationSegment) { _selectedSegment.value = segment }
    fun updateSearch(query: String) { _searchQuery.value = query }
}

@OptIn(ExperimentalMaterial3Api::class)
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

        // Segment tabs
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            LocationSegment.entries.forEachIndexed { index, segment ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, LocationSegment.entries.size),
                    selected = selectedSegment == segment,
                    onClick = { viewModel.selectSegment(segment) },
                ) { Text(segment.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }

        // Search
        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (groups.isEmpty()) {
            EmptyState(
                message = when (selectedSegment) {
                    LocationSegment.ONLINE -> "No friends in public instances"
                    LocationSegment.FAVORITE -> "No favorite friends online"
                    LocationSegment.ACTIVE -> "No friends active on website"
                },
                icon = Icons.Outlined.LocationOn,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(groups, key = { it.location }) { group ->
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            // World header
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (group.worldId.startsWith("wrld_")) onWorldClick(group.worldId)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (group.worldThumbnailUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = group.worldThumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(group.worldName, style = MaterialTheme.typography.titleSmall)
                                    val subtitle = buildString {
                                        append("${group.friends.size} friends")
                                        if (group.worldCapacity > 0) append(" | capacity ${group.worldCapacity}")
                                    }
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Friend list
                            group.friends.forEach { friend ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onUserClick(friend.id) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UserAvatar(imageUrl = friend.ref?.currentAvatarThumbnailImageUrl, status = friend.ref?.status, state = friend.state, size = 32.dp)
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
