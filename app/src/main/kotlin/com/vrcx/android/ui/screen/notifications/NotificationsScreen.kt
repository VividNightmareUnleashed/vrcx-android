package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val notifications by viewModel.notifications.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedTypes by viewModel.selectedTypes.collectAsState()
    val allTypes by viewModel.allTypes.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Notifications")

        // Type filter chips
        if (allTypes.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allTypes.forEach { type ->
                    FilterChip(
                        selected = type in selectedTypes,
                        onClick = { viewModel.toggleTypeFilter(type) },
                        label = { Text(type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        when {
            isLoading -> LoadingState()
            error != null && notifications.isEmpty() -> ErrorState(
                message = error ?: "Error",
                onRetry = { viewModel.refresh() },
            )
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (notifications.isEmpty()) {
                    EmptyState(
                        message = "No notifications",
                        icon = Icons.Outlined.Notifications,
                        subtitle = "Friend requests and invites will appear here",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(notifications, key = { it.id }) { notification ->
                            VrcxCard {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = notification.type.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = relativeTime(notification.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (notification.title.isNotBlank()) {
                                        Text(
                                            text = notification.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Text(
                                        text = "From: ${notification.senderUsername}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (notification.message.isNotBlank()) {
                                        Text(
                                            text = notification.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row {
                                        val isActionable = notification.type in setOf("friendRequest", "invite", "requestInvite")
                                        if (isActionable) {
                                            FilledTonalButton(onClick = { viewModel.accept(notification.id, notification.isV2) }) {
                                                Text("Accept")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        OutlinedButton(onClick = { viewModel.hide(notification.id, notification.isV2) }) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
