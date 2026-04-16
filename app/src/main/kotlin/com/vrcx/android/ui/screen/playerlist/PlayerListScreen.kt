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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
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
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PlayerListViewModel @Inject constructor(
    friendRepository: FriendRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedStates = MutableStateFlow(setOf(FriendState.ONLINE, FriendState.ACTIVE, FriendState.OFFLINE))
    val selectedStates: StateFlow<Set<FriendState>> = _selectedStates.asStateFlow()

    val players: StateFlow<List<FriendContext>> = combine(
        friendRepository.friends,
        _searchQuery,
        _selectedStates,
    ) { friends, query, selectedStates ->
        friends.values
            .filter { it.state in selectedStates }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<FriendContext>(
                    { stateRank(it.state) },
                    { !it.isVIP },
                    { it.name.lowercase() },
                )
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

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Player List", onBack = onBack)

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search friends",
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FriendState.entries.forEach { state ->
                FilterChip(
                    selected = state in selectedStates,
                    onClick = { viewModel.toggleState(state) },
                    label = { Text(state.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (players.isEmpty()) {
            EmptyState(
                message = "No players match those filters",
                icon = Icons.Outlined.Groups,
                subtitle = "Your friend roster will appear here, sorted by presence and VIP state.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(players, key = { it.id }) { player ->
                    UserListItem(
                        avatarUrl = player.ref?.currentAvatarThumbnailImageUrl,
                        displayName = player.name,
                        subtitle = when (player.state) {
                            FriendState.ONLINE -> player.ref?.location?.ifBlank { "Online" } ?: "Online"
                            FriendState.ACTIVE -> "Active"
                            FriendState.OFFLINE -> "Offline"
                        },
                        tags = player.ref?.tags.orEmpty(),
                        onClick = { onUserClick(player.id) },
                    )
                }
            }
        }
    }
}
