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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.formatInstanceHint
import com.vrcx.android.data.model.isTrackableLocation
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.model.resolvePresenceLocation
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.whileUiSubscribed
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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

data class PlayerListControls(
    val query: String,
    val selectedStates: Set<FriendState>,
    val scope: PlayerListScope,
    val sort: RosterSort,
)

private data class PlayerListLocation(val location: String, val worldId: String)

/**
 * The roster and the criteria used to derive it, published as one value. Editor
 * controls publish separately so input does not wait for background derivation.
 */
data class RosterState(
    val players: List<FriendContext> = emptyList(),
    val helperText: String = "",
    val searchQuery: String = "",
    val selectedStates: Set<FriendState> = defaultPlayerStates,
    val sort: RosterSort = RosterSort.PRESENCE,
    val scope: PlayerListScope = PlayerListScope.SAME_INSTANCE,
    val activeLocation: String = "",
)

private val defaultPlayerStates = setOf(FriendState.ONLINE, FriendState.ACTIVE, FriendState.OFFLINE)

/**
 * A friend plus the two sort keys that are expensive to recompute. Comparators
 * evaluate their selector on both operands of every comparison, so the presence
 * location and the case-folded name are resolved once per friend instead.
 */
private data class SortablePlayer(
    val friend: FriendContext,
    val location: String,
    val nameKey: String,
    val isFavorite: Boolean,
)

private fun helperText(scope: PlayerListScope, location: String): String = when (scope) {
    PlayerListScope.SAME_INSTANCE -> if (isTrackableLocation(location)) {
        "Friends currently matching your active VRChat instance."
    } else {
        "Switch to Friends if your current instance is not available yet."
    }

    PlayerListScope.SAME_WORLD -> if (isTrackableLocation(location)) {
        "Friends in your current world, with your exact instance first."
    } else {
        "Current-world matching needs an active world location."
    }

    PlayerListScope.FRIENDS -> "Your full friend roster — pick a sort order."
}

@HiltViewModel
class PlayerListViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendRepository: FriendRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _controls = MutableStateFlow(
        PlayerListControls(
            query = "",
            selectedStates = defaultPlayerStates,
            scope = PlayerListScope.SAME_INSTANCE,
            sort = RosterSort.PRESENCE,
        ),
    )
    val controls: StateFlow<PlayerListControls> = _controls.asStateFlow()

    private val currentLocation = authRepository.authState
        .map { state -> resolvePresenceLocation((state as? AuthState.LoggedIn)?.user) }

    private val activeLocation = currentLocation.map { PlayerListLocation(it, parseWorldId(it)) }

    val state: StateFlow<RosterState> = combine(
        friendRepository.friends,
        controls,
        activeLocation,
        friendRepository.favoriteFriendIds,
    ) { friends, criteria, active, favoriteIds ->
        val players = friends.values
            .filter { friend ->
                when (criteria.scope) {
                    PlayerListScope.SAME_INSTANCE -> {
                        isTrackableLocation(active.location) &&
                            friend.state == FriendState.ONLINE &&
                            resolvePresenceLocation(friend.ref) == active.location
                    }

                    PlayerListScope.SAME_WORLD -> {
                        active.worldId.isNotBlank() &&
                            friend.state == FriendState.ONLINE &&
                            parseWorldId(resolvePresenceLocation(friend.ref)) == active.worldId
                    }

                    PlayerListScope.FRIENDS -> friend.state in criteria.selectedStates
                }
            }
            .filter { friend ->
                criteria.query.isBlank() ||
                    friend.name.contains(criteria.query, ignoreCase = true) ||
                    describePlayerScope(friend, criteria.scope, active.location)
                        .contains(criteria.query, ignoreCase = true) ||
                    friend.ref?.statusDescription.orEmpty().contains(criteria.query, ignoreCase = true)
            }
            .map { SortablePlayer(it, resolvePresenceLocation(it.ref), it.name.lowercase(), it.id in favoriteIds) }
            .sortedWith(
                when (criteria.scope) {
                    PlayerListScope.SAME_INSTANCE ->
                        compareBy<SortablePlayer>({ !it.isFavorite }, { it.nameKey })

                    PlayerListScope.SAME_WORLD ->
                        compareBy<SortablePlayer>(
                            { it.location != active.location },
                            { !it.isFavorite },
                            { it.nameKey },
                        )

                    PlayerListScope.FRIENDS -> when (criteria.sort) {
                        RosterSort.PRESENCE -> compareBy<SortablePlayer>(
                            { stateRank(it.friend.state) },
                            { !it.isFavorite },
                            { it.nameKey },
                        )

                        RosterSort.ALPHABETICAL -> compareBy<SortablePlayer> { it.nameKey }

                        RosterSort.VIP_FIRST -> compareBy<SortablePlayer>(
                            { !it.isFavorite },
                            { stateRank(it.friend.state) },
                            { it.nameKey },
                        )
                    }
                },
            )
            .map { it.friend }

        RosterState(
            players = players,
            helperText = helperText(criteria.scope, active.location),
            searchQuery = criteria.query,
            selectedStates = criteria.selectedStates,
            sort = criteria.sort,
            scope = criteria.scope,
            activeLocation = active.location,
        )
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, RosterState())

    fun updateSearch(query: String) {
        _controls.update { it.copy(query = query) }
    }

    fun toggleState(state: FriendState) {
        _controls.update { current ->
            current.copy(
                selectedStates = if (state in current.selectedStates) {
                    current.selectedStates - state
                } else {
                    current.selectedStates + state
                },
            )
        }
    }

    fun selectSort(sort: RosterSort) {
        _controls.update { it.copy(sort = sort) }
    }

    fun selectScope(scope: PlayerListScope) {
        _controls.update { it.copy(scope = scope) }
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Friends Roster", onBack = onBack)

        Text(
            text = state.helperText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        VrcxSearchBar(
            query = controls.query,
            onQueryChange = viewModel::updateSearch,
            placeholder = when (controls.scope) {
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
                    selected = controls.scope == candidate,
                    onClick = { viewModel.selectScope(candidate) },
                    label = { Text(candidate.label) },
                )
            }
        }

        if (controls.scope == PlayerListScope.FRIENDS) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FriendState.entries.forEach { friendState ->
                    FilterChip(
                        selected = friendState in controls.selectedStates,
                        onClick = { viewModel.toggleState(friendState) },
                        label = { Text(friendState.label) },
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
                        selected = controls.sort == option,
                        onClick = { viewModel.selectSort(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        if (state.players.isEmpty()) {
            EmptyState(
                message = when (state.scope) {
                    PlayerListScope.SAME_INSTANCE -> "No friends are in your current instance"
                    PlayerListScope.SAME_WORLD -> "No friends are in your current world"
                    PlayerListScope.FRIENDS -> "No friends match those filters"
                },
                icon = Icons.Outlined.Groups,
                subtitle = when (state.scope) {
                    PlayerListScope.FRIENDS -> "This fallback view keeps the full friend roster available."

                    else ->
                        "Android can match friend presence to your current world even without desktop photon tooling."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.players, key = { it.id }) { player ->
                    UserListItem(
                        avatarUrl = player.ref?.displayAvatarUrl(),
                        displayName = player.name,
                        subtitle = describePlayerScope(player, state.scope, state.activeLocation),
                        tags = player.ref?.tags.orEmpty(),
                        state = player.state,
                        onClick = { onUserClick(player.id) },
                    )
                }
            }
        }
    }
}

private fun describePlayerScope(player: FriendContext, scope: PlayerListScope, activeLocation: String): String =
    when (scope) {
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
