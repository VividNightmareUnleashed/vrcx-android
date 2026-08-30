package com.vrcx.android.ui.screen.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(viewModel: FriendsViewModel = hiltViewModel(), onFriendClick: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Friends")

        VrcxTabRow(selectedTabIndex = FriendState.entries.indexOf(controls.selectedTab)) {
            FriendState.entries.forEach { tab ->
                Tab(
                    selected = controls.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text("${tab.label} (${state.counts[tab] ?: 0})") },
                )
            }
        }

        VrcxSearchBar(
            query = controls.searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search friends",
        )
        FriendsControlsRow(controls, viewModel)
        state.error?.let { FriendsError(it, viewModel::consumeError, viewModel::refresh) }
        FriendsList(state, viewModel::refresh, viewModel::toggleFriendNotify, onFriendClick)
    }
}

@Composable
private fun FriendsControlsRow(controls: FriendsControls, viewModel: FriendsViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = controls.vipOnly,
            onClick = viewModel::toggleVipOnly,
            label = { Text("VIP") },
            leadingIcon =
                if (controls.vipOnly) {
                    { Icon(Icons.Outlined.Star, contentDescription = null) }
                } else {
                    null
                },
        )
        var sortMenuExpanded by remember { mutableStateOf(false) }
        IconButton(onClick = { sortMenuExpanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
        }
        DropdownMenu(
            expanded = sortMenuExpanded,
            onDismissRequest = { sortMenuExpanded = false },
        ) {
            FriendsSortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        val label =
                            when (option) {
                                FriendsSortOption.NAME -> "Name"
                                FriendsSortOption.LAST_SEEN -> "Last Seen"
                                FriendsSortOption.TRUST_RANK -> "Trust Rank"
                            }
                        Text(if (controls.sortOption == option) "$label  ✓" else label)
                    },
                    onClick = {
                        viewModel.setSortOption(option)
                        sortMenuExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FriendsError(message: String, onConsume: () -> Unit, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                onConsume()
                onRetry()
            },
        ) {
            Text("Retry")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsList(
    state: FriendsUiState,
    onRefresh: () -> Unit,
    onToggleNotify: (String) -> Unit,
    onFriendClick: (String) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Keeping the empty state inside the list preserves the pull gesture.
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.friends.isEmpty()) {
                item {
                    EmptyState(
                        message = "No ${state.selectedTab.label.lowercase()} friends",
                        icon = Icons.Outlined.Group,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            } else {
                items(state.friends, key = { it.id }) { friend ->
                    FriendRow(
                        friend = friend,
                        notifyEnabled = friend.id in state.notifyEnabledIds,
                        onToggleNotify = onToggleNotify,
                        onFriendClick = onFriendClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendContext,
    notifyEnabled: Boolean,
    onToggleNotify: (String) -> Unit,
    onFriendClick: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        UserListItem(
            avatarUrl = friend.ref?.displayAvatarUrl(),
            displayName = friend.name,
            subtitle = friend.ref?.statusDescription.orEmpty(),
            tags = friend.ref?.tags.orEmpty(),
            status = friend.ref?.status,
            state = friend.state,
            onClick = { onFriendClick(friend.id) },
            onLongClick = { showMenu = true },
            trailing =
                if (notifyEnabled) {
                    {
                        Icon(
                            Icons.Outlined.NotificationsActive,
                            contentDescription = "Notifications enabled",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = {
                    Text(if (notifyEnabled) "Disable Notifications" else "Enable Notifications")
                },
                onClick = {
                    onToggleNotify(friend.id)
                    showMenu = false
                },
                leadingIcon = {
                    Icon(
                        if (notifyEnabled) {
                            Icons.Outlined.NotificationsOff
                        } else {
                            Icons.Outlined.NotificationsActive
                        },
                        contentDescription = null,
                    )
                },
            )
        }
    }
}
