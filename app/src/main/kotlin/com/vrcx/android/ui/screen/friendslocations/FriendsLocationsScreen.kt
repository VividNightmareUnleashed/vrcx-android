package com.vrcx.android.ui.screen.friendslocations

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FriendRepository
import androidx.compose.foundation.clickable
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LocationGroup(
    val location: String,
    val worldName: String,
    val friends: List<FriendContext>,
)

@HiltViewModel
class FriendsLocationsViewModel @Inject constructor(
    friendRepository: FriendRepository,
) : ViewModel() {
    val locationGroups: StateFlow<List<LocationGroup>> = friendRepository.friends.map { friendsMap ->
        friendsMap.values
            .filter { it.state == FriendState.ONLINE && it.ref?.location != null && it.ref.location != "private" && it.ref.location != "offline" }
            .groupBy { it.ref?.location ?: "" }
            .filter { it.key.isNotEmpty() }
            .map { (location, friends) ->
                val worldName = location.split(":").firstOrNull()?.let { worldId ->
                    friends.firstOrNull()?.ref?.let { "Instance" } ?: worldId
                } ?: location
                LocationGroup(location, worldName, friends.sortedBy { it.name.lowercase() })
            }
            .sortedByDescending { it.friends.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun FriendsLocationsScreen(viewModel: FriendsLocationsViewModel = hiltViewModel(), onUserClick: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val groups by viewModel.locationGroups.collectAsState()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Friends Locations", onBack = onBack)
        if (groups.isEmpty()) {
            EmptyState(message = "No friends in public instances", icon = Icons.Outlined.LocationOn, subtitle = "Friends in joinable instances will appear here")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                items(groups, key = { it.location }) { group ->
                    VrcxCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("${group.friends.size} friends", style = MaterialTheme.typography.titleSmall)
                        Text(group.location.split(":").first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        group.friends.forEach { friend ->
                            Row(Modifier.fillMaxWidth().clickable { onUserClick(friend.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
