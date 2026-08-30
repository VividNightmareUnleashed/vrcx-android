package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationSource
import com.vrcx.android.data.repository.notificationTypeLabel
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val loadState = state.loadState
    val actionError = state.action.error
    val transientError = if (actionError != null) {
        NotificationsTransientError(
            source = NotificationsErrorSource.ACTION,
            id = state.action.errorId,
            message = actionError,
        )
    } else {
        (loadState as? LoadState.Loaded)?.staleError?.let { message ->
            NotificationsTransientError(
                source = NotificationsErrorSource.LOAD,
                id = state.loadErrorId,
                message = message,
            )
        }
    }

    LaunchedEffect(transientError) {
        transientError?.let { error ->
            snackbarHostState.showSnackbar(error.message)
            when (error.source) {
                NotificationsErrorSource.ACTION -> viewModel.consumeActionError(error.id)
                NotificationsErrorSource.LOAD -> viewModel.consumeLoadError(error.id)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            VrcxTopBar(title = "Notifications")

            if (state.categoryCounts.isNotEmpty()) {
                VrcxTabRow(selectedTabIndex = NotificationCategoryFilter.entries.indexOf(state.selectedCategory)) {
                    state.categoryCounts.forEach { item ->
                        Tab(
                            selected = item.filter == state.selectedCategory,
                            onClick = { viewModel.selectCategory(item.filter) },
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
                            onClick = { viewModel.toggleTypeFilter(type) },
                            label = { Text(notificationTypeLabel(type), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            when {
                (loadState == LoadState.NotLoaded || loadState == LoadState.Loading) &&
                    state.notifications.isEmpty() -> LoadingState()

                loadState is LoadState.Failed && state.notifications.isEmpty() -> ErrorState(
                    message = loadState.message,
                    onRetry = { viewModel.refresh() },
                )

                else -> PullToRefreshBox(
                    isRefreshing = (loadState as? LoadState.Loaded)?.isRefreshing == true,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.notifications.isEmpty()) {
                        EmptyState(
                            message = "No notifications",
                            icon = Icons.Outlined.Notifications,
                            subtitle = when (state.selectedCategory) {
                                NotificationCategoryFilter.ALL ->
                                    "Friend requests, invites, and system updates will appear here"

                                NotificationCategoryFilter.FRIEND ->
                                    "Friend requests, invites, and boops will appear here"

                                NotificationCategoryFilter.GROUP ->
                                    "Group announcements and moderation updates will appear here"

                                NotificationCategoryFilter.OTHER ->
                                    "System and activity updates will appear here"
                            },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.notifications, key = { it.id }) { notification ->
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
                                        // V2 notifications are answered through the responses the
                                        // server supplies; the accept/invite/saved-response
                                        // endpoints below are V1-only.
                                        val isV2 = notification.source == NotificationSource.V2
                                        val showFriendRequestActions =
                                            notification.kind == NotificationKind.FRIEND_REQUEST && !isV2
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            if (isV2) {
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
                                            } else {
                                                when (notification.kind) {
                                                    NotificationKind.FRIEND_REQUEST -> {
                                                        FilledTonalButton(
                                                            onClick = { viewModel.performPrimaryAction(notification) },
                                                        ) {
                                                            Text("Accept")
                                                        }
                                                        OutlinedButton(
                                                            onClick = { viewModel.declineFriendRequest(notification) },
                                                        ) {
                                                            Text("Decline")
                                                        }
                                                    }

                                                    NotificationKind.INVITE -> {
                                                        FilledTonalButton(
                                                            onClick = {
                                                                viewModel.openInviteResponseDialog(notification)
                                                            },
                                                        ) {
                                                            Text("Respond")
                                                        }
                                                    }

                                                    NotificationKind.REQUEST_INVITE -> {
                                                        FilledTonalButton(
                                                            onClick = { viewModel.performPrimaryAction(notification) },
                                                        ) {
                                                            Text("Invite")
                                                        }
                                                        OutlinedButton(
                                                            onClick = {
                                                                viewModel.openInviteResponseDialog(notification)
                                                            },
                                                        ) {
                                                            Text("Respond")
                                                        }
                                                    }

                                                    else -> Unit
                                                }
                                            }
                                            // Only a card that already offers Accept + Decline can
                                            // skip Dismiss — a V2 card with no server responses
                                            // would otherwise be unclearable from the inbox.
                                            if (!showFriendRequestActions) {
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
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    state.action.inviteResponseDialog?.let { dialog ->
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
                        dialog.isSending -> {
                            Text("Sending response...")
                        }

                        dialog.isLoading -> {
                            Text("Loading saved messages...")
                        }

                        dialog.templates.isEmpty() -> {
                            Text("No saved messages are available for this response type.")
                        }

                        else -> {
                            dialog.templates.forEach { template ->
                                OutlinedButton(
                                    onClick = { viewModel.sendInviteResponse(dialog.requestId, template) },
                                    enabled = !dialog.isSending,
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
                    enabled = !dialog.isLoading && !dialog.isSending,
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

private enum class NotificationsErrorSource { ACTION, LOAD }

private data class NotificationsTransientError(val source: NotificationsErrorSource, val id: Long, val message: String)
