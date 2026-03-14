package com.vrcx.android.ui.screen.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
) {
    val entries by viewModel.feedEntries.collectAsState()
    val filters by viewModel.activeFilters.collectAsState()
    val avatarUrls by viewModel.userAvatarUrls.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Feed")

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("gps", "status", "bio", "avatar", "online", "offline").forEach { filter ->
                FilterChip(
                    selected = filters.contains(filter),
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        val filteredEntries = entries.filter { filters.contains(it.type) }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (filteredEntries.isEmpty()) {
                EmptyState(
                    message = "No feed entries yet",
                    icon = Icons.Outlined.DynamicFeed,
                    subtitle = "Activity from your friends will appear here",
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredEntries, key = { "${it.type}_${it.id}" }) { entry ->
                        FeedItem(
                            entry = entry,
                            avatarUrl = entry.thumbnailUrl.ifEmpty { avatarUrls[entry.userId] },
                            onClick = { onUserClick(entry.userId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedItem(entry: FeedEntry, avatarUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            imageUrl = avatarUrl,
            state = if (entry.type == "offline") FriendState.OFFLINE else FriendState.ONLINE,
            size = 40.dp,
            showStatusDot = false,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (entry.type) {
                    "gps" -> "Moved to ${entry.details}"
                    "status" -> "Status: ${entry.details}"
                    "online" -> "Came online"
                    "offline" -> "Went offline"
                    "bio" -> "Updated bio"
                    "avatar" -> "Changed avatar"
                    else -> entry.details
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = relativeTime(entry.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
