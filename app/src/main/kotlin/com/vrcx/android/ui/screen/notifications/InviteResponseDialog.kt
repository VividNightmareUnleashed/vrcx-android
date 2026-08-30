package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.InviteMessageTemplate

@Composable
internal fun InviteResponseDialog(dialog: InviteResponseDialogState, actions: NotificationsActionDispatcher) {
    AlertDialog(
        onDismissRequest = { actions(NotificationsUiAction.DismissInviteResponseDialog) },
        title = { Text(dialog.title) },
        text = { InviteResponseDialogBody(dialog = dialog, actions = actions) },
        confirmButton = {
            TextButton(
                onClick = { actions(NotificationsUiAction.RefreshInviteResponseDialog) },
                enabled = !dialog.isLoading && !dialog.isSending,
            ) {
                Text("Refresh")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { actions(NotificationsUiAction.DismissInviteResponseDialog) },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InviteResponseDialogBody(dialog: InviteResponseDialogState, actions: NotificationsActionDispatcher) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Choose a saved response message to send back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            dialog.isSending -> Text("Sending response...")

            dialog.isLoading -> Text("Loading saved messages...")

            dialog.templates.isEmpty() -> Text("No saved messages are available for this response type.")

            else -> dialog.templates.forEach { template ->
                InviteResponseTemplateButton(
                    requestId = dialog.requestId,
                    template = template,
                    enabled = !dialog.isSending,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun InviteResponseTemplateButton(
    requestId: Long,
    template: InviteMessageTemplate,
    enabled: Boolean,
    actions: NotificationsActionDispatcher,
) {
    OutlinedButton(
        onClick = {
            actions(NotificationsUiAction.SendInviteResponse(requestId, template))
        },
        enabled = enabled,
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
