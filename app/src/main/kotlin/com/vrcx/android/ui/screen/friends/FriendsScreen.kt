package com.vrcx.android.ui.screen.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTopBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    viewModel: FriendsViewModel = hiltViewModel(),
    onFriendClick: (String) -> Unit = {},
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val friends by viewModel.filteredFriends.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val onlineCount by viewModel.onlineCount.collectAsState()
    val activeCount by viewModel.activeCount.collectAsState()
    val offlineCount by viewModel.offlineCount.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Friends")

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == FriendsTab.ONLINE,
                onClick = { viewModel.selectTab(FriendsTab.ONLINE) },
                text = { Text("Online ($onlineCount)") },
            )
            Tab(
                selected = selectedTab == FriendsTab.ACTIVE,
                onClick = { viewModel.selectTab(FriendsTab.ACTIVE) },
                text = { Text("Active ($activeCount)") },
            )
            Tab(
                selected = selectedTab == FriendsTab.OFFLINE,
                onClick = { viewModel.selectTab(FriendsTab.OFFLINE) },
                text = { Text("Offline ($offlineCount)") },
            )
        }

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            placeholder = "Search friends",
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (friends.isEmpty()) {
                EmptyState(
                    message = "No ${selectedTab.name.lowercase()} friends",
                    icon = Icons.Outlined.Group,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(friends, key = { it.id }) { friend ->
                        UserListItem(
                            avatarUrl = friend.ref?.currentAvatarThumbnailImageUrl,
                            displayName = friend.name,
                            subtitle = friend.ref?.statusDescription ?: "",
                            tags = friend.ref?.tags ?: emptyList(),
                            status = friend.ref?.status,
                            state = friend.state,
                            onClick = { onFriendClick(friend.id) },
                        )
                    }
                }
            }
        }
    }
}
