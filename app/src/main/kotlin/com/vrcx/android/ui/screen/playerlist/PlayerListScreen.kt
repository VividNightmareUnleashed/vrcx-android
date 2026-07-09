package com.vrcx.android.ui.screen.playerlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Sort order for the Friends Roster view. */
enum class RosterSort(val label: String) {
    PRESENCE("Presence"),
    ALPHABETICAL("A → Z"),
    VIP_FIRST("VIP first"),
}

enum class PlayerListScope(val label: String) {
    SAME_INSTANCE("Instance"),
    SAME_WORLD("World"),
    FRIENDS("Friends"),
}

@HiltViewModel
class PlayerListViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendRepository: FriendRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedStates = MutableStateFlow(setOf(FriendState.ONLINE, FriendState.ACTIVE, FriendState.OFFLINE))
    val selectedStates: StateFlow<Set<FriendState>> = _selectedStates.asStateFlow()

    private val _sort = MutableStateFlow(RosterSort.PRESENCE)
    val sort: StateFlow<RosterSort> = _sort.asStateFlow()

    private val _scope = MutableStateFlow(PlayerListScope.SAME_INSTANCE)
    val scope: StateFlow<PlayerListScope> = _scope.asStateFlow()

    val currentLocation = authRepository.authState
        .map { state -> resolvePresenceLocation((state as? AuthState.LoggedIn)?.user) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val currentWorldId = currentLocation
        .map(::parseWorldId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val helperText: StateFlow<String> = combine(_scope, currentLocation) { scope, location ->
        when (scope) {
            PlayerListScope.SAME_INSTANCE -> {
                if (isTrackableLocation(location)) {
                    "Friends currently matching your active VRChat instance."
                } else {
                    "Switch to Friends if your current instance is not available yet."
                }
            }
            PlayerListScope.SAME_WORLD -> {
                if (isTrackableLocation(location)) {
                    "Friends in your current world, with your exact instance first."
                } else {
                    "Current-world matching needs an active world location."
                }
            }
            PlayerListScope.FRIENDS -> "Your full friend roster — pick a sort order."
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val players: StateFlow<List<FriendContext>> = combine(
        friendRepository.friends,
        _searchQuery,
        _selectedStates,
        _scope,
        _sort,
        currentLocation,
        currentWorldId,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val friends = values[0] as Map<String, FriendContext>
        val query = values[1] as String
        val selectedStates = values[2] as Set<FriendState>
        val scope = values[3] as PlayerListScope
        val sort = values[4] as RosterSort
        val activeLocation = values[5] as String
        val activeWorldId = values[6] as String

        friends.values
            .filter { friend ->
                when (scope) {
                    PlayerListScope.SAME_INSTANCE -> {
                        isTrackableLocation(activeLocation) &&
                            friend.state == FriendState.ONLINE &&
                            resolvePresenceLocation(friend.ref) == activeLocation
                    }
                    PlayerListScope.SAME_WORLD -> {
                        activeWorldId.isNotBlank() &&
                            friend.state == FriendState.ONLINE &&
                            parseWorldId(resolvePresenceLocation(friend.ref)) == activeWorldId
                    }
                    PlayerListScope.FRIENDS -> friend.state in selectedStates
                }
            }
            .filter { friend ->
                query.isBlank() ||
                    friend.name.contains(query, ignoreCase = true) ||
                    describePlayerScope(friend, scope, activeLocation).contains(query, ignoreCase = true) ||
                    friend.ref?.statusDescription.orEmpty().contains(query, ignoreCase = true)
            }
            .sortedWith(
                when (scope) {
                    PlayerListScope.SAME_INSTANCE ->
                        compareBy<FriendContext>({ !it.isVIP }, { it.name.lowercase() })
                    PlayerListScope.SAME_WORLD ->
                        compareBy<FriendContext>(
                            { resolvePresenceLocation(it.ref) != activeLocation },
                            { !it.isVIP },
                            { it.name.lowercase() },
                        )
                    PlayerListScope.FRIENDS -> when (sort) {
                        RosterSort.PRESENCE -> compareBy<FriendContext>(
                            { stateRank(it.state) },
                            { !it.isVIP },
                            { it.name.lowercase() },
                        )
                        RosterSort.ALPHABETICAL -> compareBy<FriendContext> { it.name.lowercase() }
                        RosterSort.VIP_FIRST -> compareBy<FriendContext>(
                            { !it.isVIP },
                            { stateRank(it.state) },
                            { it.name.lowercase() },
                        )
                    }
                }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleState(state: FriendState) {
        val current = _selectedStates.value.toMutableSet()
        if (state in current) current.remove(state) else current.add(state)
        _selectedStates.value = current
    }

    fun selectSort(sort: RosterSort) {
        _sort.value = sort
    }

    fun selectScope(scope: PlayerListScope) {
        _scope.value = scope
    }

    private fun stateRank(state: FriendState): Int = when (state) {
        FriendState.ONLINE -> 0
        FriendState.ACTIVE -> 1
        FriendState.OFFLINE -> 2
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerListScreen(
    viewModel: PlayerListViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedStates by viewModel.selectedStates.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val helperText by viewModel.helperText.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Renamed from "Player List" — Android cannot inspect the user's
        // current VRChat instance, so this screen lists their friend roster
        // with custom sorts and presence filters.
        VrcxDetailTopBar(title = "Friends Roster", onBack = onBack)

        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = when (scope) {
                PlayerListScope.SAME_INSTANCE -> "Search your current instance"
                PlayerListScope.SAME_WORLD -> "Search your current world"
                PlayerListScope.FRIENDS -> "Search friends"
            },
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerListScope.entries.forEach { candidate ->
                FilterChip(
                    selected = scope == candidate,
                    onClick = { viewModel.selectScope(candidate) },
                    label = { Text(candidate.label) },
                )
            }
        }

        if (scope == PlayerListScope.FRIENDS) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FriendState.entries.forEach { state ->
                    FilterChip(
                        selected = state in selectedStates,
                        onClick = { viewModel.toggleState(state) },
                        label = { Text(state.name.lowercase().replaceFirstChar { it.uppercase() }) },
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
                RosterSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { viewModel.selectSort(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        if (players.isEmpty()) {
            EmptyState(
                message = when (scope) {
                    PlayerListScope.SAME_INSTANCE -> "No friends are in your current instance"
                    PlayerListScope.SAME_WORLD -> "No friends are in your current world"
                    PlayerListScope.FRIENDS -> "No friends match those filters"
                },
                icon = Icons.Outlined.Groups,
                subtitle = when (scope) {
                    PlayerListScope.FRIENDS -> "This fallback view keeps the full friend roster available."
                    else -> "Android can match friend presence to your current world even without desktop photon tooling."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(players, key = { it.id }) { player ->
                    UserListItem(
                        avatarUrl = player.ref?.displayAvatarUrl(),
                        displayName = player.name,
                        subtitle = describePlayerScope(player, scope, currentLocation),
                        tags = player.ref?.tags.orEmpty(),
                        onClick = { onUserClick(player.id) },
                    )
                }
            }
        }
    }
}

private fun describePlayerScope(
    player: FriendContext,
    scope: PlayerListScope,
    activeLocation: String,
): String {
    return when (scope) {
        PlayerListScope.SAME_INSTANCE -> "Same instance"
        PlayerListScope.SAME_WORLD -> {
            val friendLocation = resolvePresenceLocation(player.ref)
            when {
                friendLocation.isBlank() -> "Same world"
                activeLocation.isNotBlank() && friendLocation == activeLocation -> "Same instance"
                else -> buildString {
                    append("Same world")
                    val instanceHint = formatInstanceHint(friendLocation)
                    if (instanceHint.isNotBlank()) append(" • $instanceHint")
                }
            }
        }
        PlayerListScope.FRIENDS -> when (player.state) {
            FriendState.ONLINE -> describeOnlineState(player.ref)
            FriendState.ACTIVE -> "Active on website"
            FriendState.OFFLINE -> "Offline"
        }
    }
}

private fun stateRank(state: FriendState): Int = when (state) {
    FriendState.ONLINE -> 0
    FriendState.ACTIVE -> 1
    FriendState.OFFLINE -> 2
}

private fun describeOnlineState(friend: VrcUser?): String {
    val location = resolvePresenceLocation(friend)
    return when {
        isTrackableLocation(location) -> {
            val instanceHint = formatInstanceHint(location)
            if (instanceHint.isBlank()) "In a public instance" else "In $instanceHint"
        }
        friend?.location == "private" -> "Private"
        friend?.location == "traveling" -> "Traveling"
        else -> "Online"
    }
}

private fun resolvePresenceLocation(user: CurrentUser?): String {
    return when (user?.location) {
        "traveling" -> user.travelingToLocation.orEmpty()
        else -> user?.location.orEmpty()
    }
}

private fun resolvePresenceLocation(friend: VrcUser?): String {
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
    return if (instanceLabel.isBlank()) "" else "instance $instanceLabel"
}
