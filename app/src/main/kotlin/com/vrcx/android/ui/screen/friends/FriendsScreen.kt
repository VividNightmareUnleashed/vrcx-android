package com.vrcx.android.ui.screen.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar

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

        OutlinedTextField(
            value = searchQuery,
            onValueChange = viewModel::updateSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search friends") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(friends, key = { it.id }) { friend ->
                    FriendItem(
                        friend = friend,
                        onClick = { onFriendClick(friend.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendItem(
    friend: FriendContext,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            imageUrl = friend.ref?.currentAvatarThumbnailImageUrl,
            status = friend.ref?.status,
            state = friend.state,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                friend.ref?.tags?.let { tags ->
                    TrustRankBadge(tags = tags)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = friend.ref?.statusDescription ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
