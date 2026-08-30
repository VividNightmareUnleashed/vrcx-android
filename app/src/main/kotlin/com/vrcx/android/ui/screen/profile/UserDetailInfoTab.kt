package com.vrcx.android.ui.screen.profile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.resolvedWorldId
import com.vrcx.android.ui.components.VrcxCard

private const val DATE_PREFIX_LENGTH = 10

@Composable
internal fun UserDetailInfoTab(
    state: UserDetailUiState,
    user: VrcUser,
    onAction: (UserDetailUiAction) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (user.bio.isNotBlank()) {
            UserBioCard(user.bio)
            Spacer(Modifier.height(12.dp))
        }

        resolvedWorldId(user.location, user.travelingToLocation)?.let { worldId ->
            UserLocationCard(user.location.orEmpty(), onClick = { onWorldClick(worldId) })
            Spacer(Modifier.height(12.dp))
        }

        UserProfileInfoCard(user)
        Spacer(Modifier.height(12.dp))

        EditableProfileTextCard(
            title = "VRChat Note",
            text = state.note,
            emptyLabel = "No note set",
            editContentDescription = "Edit VRChat note",
            onEdit = { onAction(UserDetailUiAction.EditNote) },
        )
        Spacer(Modifier.height(12.dp))

        EditableProfileTextCard(
            title = "Memo",
            text = state.memo,
            emptyLabel = "No memo set",
            editContentDescription = "Edit memo",
            onEdit = { onAction(UserDetailUiAction.EditMemo) },
        )
        Spacer(Modifier.height(16.dp))

        if (!state.isSelf) {
            UserSocialActions(user.isFriend, state.notifyEnabled, onAction)
        }
    }
}

@Composable
private fun UserBioCard(bio: String) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text("Bio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(bio, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UserLocationCard(location: String, onClick: () -> Unit) {
    VrcxCard(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(location, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UserProfileInfoCard(user: VrcUser) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text("Info", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Platform", user.lastPlatform)
            if (user.dateJoined.isNotEmpty()) InfoRow("Joined", user.dateJoined)
            if (user.lastLogin.isNotEmpty()) InfoRow("Last Login", user.lastLogin.take(DATE_PREFIX_LENGTH))
        }
    }
}

@Composable
private fun EditableProfileTextCard(
    title: String,
    text: String?,
    emptyLabel: String,
    editContentDescription: String,
    onEdit: () -> Unit,
) {
    VrcxCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text.orEmpty().ifBlank { emptyLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = editContentDescription)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserSocialActions(isFriend: Boolean, notifyEnabled: Boolean, onAction: (UserDetailUiAction) -> Unit) {
    Text("Actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RelationshipActions(isFriend, notifyEnabled, onAction)
        FilledTonalButton(onClick = { onAction(UserDetailUiAction.SendInvite) }) {
            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Invite")
        }
        OutlinedButton(onClick = { onAction(UserDetailUiAction.RequestInvite) }) {
            Text("Request Invite")
        }
        SafetyActions(onAction)
    }
}

@Composable
private fun RelationshipActions(isFriend: Boolean, notifyEnabled: Boolean, onAction: (UserDetailUiAction) -> Unit) {
    if (isFriend) {
        FilledTonalButton(onClick = { onAction(UserDetailUiAction.ToggleNotify) }) {
            Icon(
                if (notifyEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                null,
                Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (notifyEnabled) "Notifications On" else "Notifications Off")
        }
        OutlinedButton(
            onClick = {
                onAction(UserDetailUiAction.RequestDestructiveConfirmation(UserDestructiveAction.Unfriend))
            },
        ) {
            Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Unfriend")
        }
        OutlinedButton(onClick = { onAction(UserDetailUiAction.SendBoop) }) {
            Text("Boop")
        }
    } else {
        FilledTonalButton(onClick = { onAction(UserDetailUiAction.SendFriendRequest) }) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Friend")
        }
        // VRChat does not expose outbound friend-request status, so cancellation
        // remains available and a missing request harmlessly produces a 404.
        OutlinedButton(onClick = { onAction(UserDetailUiAction.CancelFriendRequest) }) {
            Text("Cancel Pending Request")
        }
    }
}

@Composable
private fun SafetyActions(onAction: (UserDetailUiAction) -> Unit) {
    OutlinedButton(
        onClick = { onAction(UserDetailUiAction.RequestDestructiveConfirmation(UserDestructiveAction.Block)) },
    ) {
        Icon(Icons.Default.Block, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Block")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailUiAction.RequestDestructiveConfirmation(UserDestructiveAction.Mute)) },
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Mute")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailUiAction.RequestDestructiveConfirmation(UserDestructiveAction.ShowAvatar)) },
    ) {
        Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Show Avatar")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailUiAction.RequestDestructiveConfirmation(UserDestructiveAction.HideAvatar)) },
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Hide Avatar")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
