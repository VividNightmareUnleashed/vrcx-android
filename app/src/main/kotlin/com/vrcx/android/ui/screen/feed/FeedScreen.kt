package com.vrcx.android.ui.screen.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.ui.common.activityLabel
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val filters by viewModel.activeFilters.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val vipOnly by viewModel.vipOnly.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Feed")

        // Search bar
        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Filter chips
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // VIP chip
            FilterChip(
                selected = vipOnly,
                onClick = { viewModel.toggleVipOnly() },
                label = { Text("VIP") },
                leadingIcon = if (vipOnly) {{ Icon(Icons.Outlined.Star, contentDescription = null, Modifier.padding(0.dp)) }} else null,
            )
            // Type chips
            FeedEntryType.entries.forEach { filter ->
                FilterChip(
                    selected = filter in filters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // The empty state lives inside the lazy list too: PullToRefreshBox only
            // sees the gesture when a scrollable child dispatches it.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (page.entries.isEmpty()) {
                    item {
                        EmptyState(
                            message = "No feed entries yet",
                            icon = Icons.Outlined.DynamicFeed,
                            subtitle = "Activity from your friends will appear here",
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    items(page.entries, key = { it.key }) { entry ->
                        FeedItem(
                            entry = entry,
                            avatarUrl = entry.thumbnailUrl.ifEmpty {
                                friends[entry.userId]?.ref?.displayAvatarUrl()?.takeIf { it.isNotEmpty() }
                            },
                            onClick = { onUserClick(entry.userId) },
                        )
                    }
                    // Load More button
                    if (page.canLoadMore) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) { Text("Load More") }
                        }
                    }
                }
            }
        }
      }
      SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
            state = if (entry.type == FeedEntryType.OFFLINE) FriendState.OFFLINE else FriendState.ONLINE,
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
                text = entry.activityLabel(),
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
