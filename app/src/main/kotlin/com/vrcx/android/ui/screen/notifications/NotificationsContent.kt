@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.vrcx.android.ui.screen.notifications

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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.notificationTypeLabel
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar

@Composable
internal fun NotificationsContent(state: NotificationsUiState, actions: NotificationsActionDispatcher) {
    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Notifications")
        NotificationFilters(state = state, actions = actions)
        NotificationsBody(state = state, actions = actions)
    }
}

@Composable
private fun NotificationFilters(state: NotificationsUiState, actions: NotificationsActionDispatcher) {
    if (state.categoryCounts.isNotEmpty()) {
        VrcxTabRow(
            selectedTabIndex = NotificationCategoryFilter.entries.indexOf(state.selectedCategory),
        ) {
            state.categoryCounts.forEach { item ->
                Tab(
                    selected = item.filter == state.selectedCategory,
                    onClick = { actions(NotificationsUiAction.SelectCategory(item.filter)) },
                    text = { Text("${item.filter.label} (${item.count})") },
                )
            }
        }
    }
    if (state.visibleTypes.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.visibleTypes.forEach { type ->
                FilterChip(
                    selected = type in state.selectedTypes,
                    onClick = { actions(NotificationsUiAction.ToggleType(type)) },
                    label = {
                        Text(
                            text = notificationTypeLabel(type),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationsBody(state: NotificationsUiState, actions: NotificationsActionDispatcher) {
    val loadState = state.loadState
    when {
        loadState.isInitialLoad && state.notifications.isEmpty() -> LoadingState()

        loadState is LoadState.Failed && state.notifications.isEmpty() -> ErrorState(
            message = loadState.message,
            onRetry = { actions(NotificationsUiAction.Refresh) },
        )

        else -> PullToRefreshBox(
            isRefreshing = (loadState as? LoadState.Loaded)?.isRefreshing == true,
            onRefresh = { actions(NotificationsUiAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            NotificationsInbox(state = state, actions = actions)
        }
    }
}

private val LoadState<Unit>.isInitialLoad: Boolean
    get() = this == LoadState.NotLoaded || this == LoadState.Loading

@Composable
private fun NotificationsInbox(state: NotificationsUiState, actions: NotificationsActionDispatcher) {
    if (state.notifications.isEmpty()) {
        EmptyState(
            message = "No notifications",
            icon = Icons.Outlined.Notifications,
            subtitle = emptyInboxSubtitle(state.selectedCategory),
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.notifications, key = { it.id }) { notification ->
                NotificationCard(notification = notification, actions = actions)
            }
        }
    }
}

private fun emptyInboxSubtitle(category: NotificationCategoryFilter): String = when (category) {
    NotificationCategoryFilter.ALL -> "Friend requests, invites, and system updates will appear here"
    NotificationCategoryFilter.FRIEND -> "Friend requests, invites, and boops will appear here"
    NotificationCategoryFilter.GROUP -> "Group announcements and moderation updates will appear here"
    NotificationCategoryFilter.OTHER -> "System and activity updates will appear here"
}
