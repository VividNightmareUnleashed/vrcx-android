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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.notificationTypeLabel
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
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val visibleTypes by viewModel.visibleTypes.collectAsStateWithLifecycle()
    val inviteResponseDialog by viewModel.inviteResponseDialog.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Notifications")

        if (categoryCounts.isNotEmpty()) {
            TabRow(selectedTabIndex = NotificationCategoryFilter.entries.indexOf(selectedCategory)) {
                categoryCounts.forEach { item ->
                    Tab(
                        selected = item.filter == selectedCategory,
                        onClick = { viewModel.selectCategory(item.filter) },
                        text = { Text("${item.filter.label} (${item.count})") },
                    )
                }
            }
        }

        // Type filter chips
        if (visibleTypes.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleTypes.forEach { type ->
                    FilterChip(
                        selected = type in selectedTypes,
                        onClick = { viewModel.toggleTypeFilter(type) },
                        label = { Text(notificationTypeLabel(type), style = MaterialTheme.typography.labelSmall) },
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
                        subtitle = when (selectedCategory) {
                            NotificationCategoryFilter.ALL -> "Friend requests, invites, and system updates will appear here"
                            NotificationCategoryFilter.FRIEND -> "Friend requests, invites, and boops will appear here"
                            NotificationCategoryFilter.GROUP -> "Group announcements and moderation updates will appear here"
                            NotificationCategoryFilter.OTHER -> "System and activity updates will appear here"
                        },
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
                                            text = notificationTypeLabel(notification.type),
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
                                    if (notification.senderUsername.isNotBlank()) {
                                        Text(
                                            text = "From: ${notification.senderUsername}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    if (notification.message.isNotBlank()) {
                                        Text(
                                            text = notification.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        when {
                                            notification.isV2 -> {
                                                notification.responses.forEach { response ->
                                                    val label = response.text.ifBlank {
                                                        notificationTypeLabel(response.type)
                                                    }
                                                    FilledTonalButton(
                                                        onClick = { viewModel.respond(notification, response.type) },
                                                    ) {
                                                        Text(label)
                                                    }
                                                }
                                            }
                                            notification.type == "friendRequest" -> {
                                                FilledTonalButton(onClick = { viewModel.performPrimaryAction(notification) }) {
                                                    Text("Accept")
                                                }
                                                OutlinedButton(onClick = { viewModel.declineFriendRequest(notification) }) {
                                                    Text("Decline")
                                                }
                                            }
                                            notification.type == "invite" -> {
                                                FilledTonalButton(onClick = { viewModel.openInviteResponseDialog(notification) }) {
                                                    Text("Respond")
                                                }
                                            }
                                            notification.type == "requestInvite" -> {
                                                FilledTonalButton(onClick = { viewModel.performPrimaryAction(notification) }) {
                                                    Text("Invite")
                                                }
                                                OutlinedButton(onClick = { viewModel.openInviteResponseDialog(notification) }) {
                                                    Text("Respond")
                                                }
                                            }
                                        }
                                        // friendRequest already exposes Accept + Decline above; the generic
                                        // Dismiss would be a confusing third option for the same type.
                                        if (notification.type != "friendRequest") {
                                            OutlinedButton(onClick = { viewModel.hide(notification) }) {
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

    inviteResponseDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissInviteResponseDialog,
            title = { Text(dialog.title) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Choose a saved response message to send back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        dialog.isLoading -> {
                            Text("Loading saved messages...")
                        }
                        dialog.templates.isEmpty() -> {
                            Text("No saved messages are available for this response type.")
                        }
                        else -> {
                            dialog.templates.forEach { template ->
                                OutlinedButton(
                                    onClick = { viewModel.sendInviteResponse(template) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "Slot ${template.slot}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = template.message.ifBlank { "Empty message" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.refreshInviteResponseDialog() },
                    enabled = !dialog.isLoading,
                ) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissInviteResponseDialog) {
                    Text("Cancel")
                }
            },
        )
    }
}
