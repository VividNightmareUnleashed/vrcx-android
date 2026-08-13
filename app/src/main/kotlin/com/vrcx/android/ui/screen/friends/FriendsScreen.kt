package com.vrcx.android.ui.screen.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    viewModel: FriendsViewModel = hiltViewModel(),
    onFriendClick: (String) -> Unit = {},
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val vipOnly by viewModel.vipOnly.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Friends")

        VrcxTabRow(selectedTabIndex = FriendState.entries.indexOf(selectedTab)) {
            FriendState.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text("${tab.label} (${state.counts[tab] ?: 0})") },
                )
            }
        }

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search friends",
        )

        // Sort + VIP row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = vipOnly,
                onClick = { viewModel.toggleVipOnly() },
                label = { Text("VIP") },
                leadingIcon = if (vipOnly) {{ Icon(Icons.Outlined.Star, contentDescription = null) }} else null,
            )

            var sortMenuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { sortMenuExpanded = true }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                FriendsSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            val label = when (option) {
                                FriendsSortOption.NAME -> "Name"
                                FriendsSortOption.LAST_SEEN -> "Last Seen"
                                FriendsSortOption.TRUST_RANK -> "Trust Rank"
                            }
                            Text(if (sortOption == option) "$label  ✓" else label)
                        },
                        onClick = { viewModel.setSortOption(option); sortMenuExpanded = false },
                    )
                }
            }
        }

        error?.let { message ->
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
                TextButton(onClick = { viewModel.consumeError(); viewModel.refresh() }) { Text("Retry") }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // The empty state sits inside the lazy list so the pull gesture still
            // reaches PullToRefreshBox when there is nothing to show.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.friends.isEmpty()) {
                    item {
                        EmptyState(
                            message = "No ${selectedTab.label.lowercase()} friends",
                            icon = Icons.Outlined.Group,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    items(state.friends, key = { it.id }) { friend ->
                        var showMenu by remember { mutableStateOf(false) }
                        val notifyEnabled = friend.id in state.notifyEnabledIds
                        Box {
                            UserListItem(
                                avatarUrl = friend.ref?.displayAvatarUrl(),
                                displayName = friend.name,
                                subtitle = friend.ref?.statusDescription ?: "",
                                tags = friend.ref?.tags ?: emptyList(),
                                status = friend.ref?.status,
                                state = friend.state,
                                onClick = { onFriendClick(friend.id) },
                                onLongClick = { showMenu = true },
                                trailing = if (notifyEnabled) {{
                                    Icon(
                                        Icons.Outlined.NotificationsActive,
                                        contentDescription = "Notifications enabled",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }} else null,
                            )
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (notifyEnabled) "Disable Notifications" else "Enable Notifications")
                                    },
                                    onClick = {
                                        viewModel.toggleFriendNotify(friend.id)
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (notifyEnabled) Icons.Outlined.NotificationsOff
                                            else Icons.Outlined.NotificationsActive,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
