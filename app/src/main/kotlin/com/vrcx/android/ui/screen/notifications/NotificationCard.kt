@file:OptIn(ExperimentalLayoutApi::class)

package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationSource
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.repository.notificationTypeLabel
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.VrcxCard

@Composable
internal fun NotificationCard(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    VrcxCard {
        Column(modifier = Modifier.padding(16.dp)) {
            NotificationHeader(notification)
            Spacer(modifier = Modifier.height(4.dp))
            NotificationText(notification)
            Spacer(modifier = Modifier.height(8.dp))
            NotificationActionButtons(notification = notification, actions = actions)
        }
    }
}

@Composable
private fun NotificationHeader(notification: UnifiedNotification) {
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
}

@Composable
private fun NotificationText(notification: UnifiedNotification) {
    if (notification.title.isNotBlank()) {
        Text(text = notification.title, style = MaterialTheme.typography.bodyMedium)
    }
    if (notification.senderUsername.isNotBlank()) {
        Text(text = "From: ${notification.senderUsername}", style = MaterialTheme.typography.bodyMedium)
    }
    if (notification.message.isNotBlank()) {
        Text(
            text = notification.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationActionButtons(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    val isV2 = notification.source == NotificationSource.V2
    val hasV1FriendRequestActions = notification.kind == NotificationKind.FRIEND_REQUEST && !isV2
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // V2 actions must use the response types supplied by the server; the
        // accept/invite/saved-message endpoints are V1-only.
        if (isV2) {
            V2ResponseButtons(notification = notification, actions = actions)
        } else {
            V1ActionButtons(notification = notification, actions = actions)
        }
        // A response-less V2 card still needs a way out of the local inbox.
        if (!hasV1FriendRequestActions) {
            OutlinedButton(onClick = { actions(NotificationsUiAction.Hide(notification)) }) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun V2ResponseButtons(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    notification.responses.forEach { response ->
        FilledTonalButton(
            onClick = {
                actions(NotificationsUiAction.Respond(notification, response.type))
            },
        ) {
            Text(response.text.ifBlank { notificationTypeLabel(response.type) })
        }
    }
}

@Composable
private fun V1ActionButtons(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    when (notification.kind) {
        NotificationKind.FRIEND_REQUEST -> FriendRequestButtons(notification, actions)
        NotificationKind.INVITE -> InviteButton(notification, actions)
        NotificationKind.REQUEST_INVITE -> RequestInviteButtons(notification, actions)
        NotificationKind.OTHER -> Unit
    }
}

@Composable
private fun FriendRequestButtons(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    FilledTonalButton(
        onClick = { actions(NotificationsUiAction.PerformPrimary(notification)) },
    ) {
        Text("Accept")
    }
    OutlinedButton(
        onClick = { actions(NotificationsUiAction.DeclineFriendRequest(notification)) },
    ) {
        Text("Decline")
    }
}

@Composable
private fun InviteButton(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    FilledTonalButton(
        onClick = { actions(NotificationsUiAction.OpenInviteResponseDialog(notification)) },
    ) {
        Text("Respond")
    }
}

@Composable
private fun RequestInviteButtons(notification: UnifiedNotification, actions: NotificationsActionDispatcher) {
    FilledTonalButton(
        onClick = { actions(NotificationsUiAction.PerformPrimary(notification)) },
    ) {
        Text("Invite")
    }
    OutlinedButton(
        onClick = { actions(NotificationsUiAction.OpenInviteResponseDialog(notification)) },
    ) {
        Text("Respond")
    }
}
