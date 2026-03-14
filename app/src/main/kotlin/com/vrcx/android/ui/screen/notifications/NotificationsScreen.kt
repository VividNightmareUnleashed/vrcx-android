package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val notifications by viewModel.notifications.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Notifications")

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
                                    Text(
                                        text = notification.type.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
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
                                        if (notification.type == "friendRequest") {
                                            FilledTonalButton(onClick = { viewModel.accept(notification.id) }) {
                                                Text("Accept")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        OutlinedButton(onClick = { viewModel.hide(notification.id) }) {
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
