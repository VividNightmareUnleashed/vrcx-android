@file:OptIn(ExperimentalMaterial3Api::class)

package com.vrcx.android.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.BuildConfig
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.ui.components.VrcxCard

private const val COMPACT_FEED_HISTORY_SIZE = 100
private const val MEDIUM_FEED_HISTORY_SIZE = 250
private const val LARGE_FEED_HISTORY_SIZE = 500
private val FEED_HISTORY_SIZES = listOf(
    COMPACT_FEED_HISTORY_SIZE,
    MEDIUM_FEED_HISTORY_SIZE,
    LARGE_FEED_HISTORY_SIZE,
    PreferenceDefaults.MAX_FEED_SIZE,
)

private const val BACKGROUND_SERVICE_DESCRIPTION =
    "Keep WebSocket connected in the background when Android allows it " +
        "(newer Android versions may require reopening the app after reboot)"

@Composable
internal fun GeneralSettingsSection(
    state: SettingsGeneralState,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
) {
    SettingsSection("General") {
        SettingToggle(
            title = "Background Service",
            subtitle = BACKGROUND_SERVICE_DESCRIPTION,
            checked = state.backgroundServiceEnabled,
            onCheckedChange = {
                onPreferenceUpdate(SettingsPreferenceUpdate.BackgroundService(it))
            },
        )
        SettingToggle(
            title = "Auto Login",
            subtitle = "Automatically retry login with remembered credentials " +
                "when no saved session is available",
            checked = state.autoLogin,
            onCheckedChange = { onPreferenceUpdate(SettingsPreferenceUpdate.AutoLogin(it)) },
        )
        Text("Feed History", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Limit how many feed entries stay queryable for Feed and Game Log style views",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FeedHistorySelector(state.maxFeedSize, onPreferenceUpdate)
    }
}

@Composable
private fun FeedHistorySelector(selectedSize: Int, onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FEED_HISTORY_SIZES.forEachIndexed { index, size ->
            SegmentedButton(
                selected = selectedSize == size,
                onClick = {
                    onPreferenceUpdate(SettingsPreferenceUpdate.FeedHistoryLimit(size))
                },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = FEED_HISTORY_SIZES.size,
                ),
            ) { Text(size.toString()) }
        }
    }
}

@Composable
internal fun NotificationSettingsSection(
    state: SettingsNotificationState,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
) {
    SettingsSection("Notifications") {
        SettingToggle(
            title = "Invites",
            subtitle = "Notify on invite received",
            checked = state.notifyInvite,
            onCheckedChange = {
                onPreferenceUpdate(SettingsPreferenceUpdate.InviteNotifications(it))
            },
        )
        SettingToggle(
            title = "Friend Requests",
            subtitle = "Notify on friend request",
            checked = state.notifyFriendRequest,
            onCheckedChange = {
                onPreferenceUpdate(SettingsPreferenceUpdate.FriendRequestNotifications(it))
            },
        )
        SettingToggle(
            title = "Other Notifications",
            subtitle = "Notify on notification types this app doesn't recognise, " +
                "using the text VRChat sends",
            checked = state.notifyGeneral,
            onCheckedChange = {
                onPreferenceUpdate(SettingsPreferenceUpdate.GeneralNotifications(it))
            },
        )
        Text(
            "Per-friend notifications can be enabled from each friend's profile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StorageSettingsSection(state: SettingsStorageState, onCacheAll: () -> Unit, onClearCache: () -> Unit) {
    SettingsSection("Storage") {
        Text("Profile Picture Cache", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Cached: ${state.cacheSizeText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.cacheAllProgress?.let { progress ->
            Text(
                "Caching: ${progress.completed} / ${progress.total}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) {
                        progress.completed.toFloat() / progress.total
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onCacheAll,
                enabled = state.cacheAllProgress == null,
            ) {
                Text("Cache All Friends")
            }
            OutlinedButton(
                onClick = onClearCache,
                enabled = state.cacheAllProgress == null,
            ) {
                Text("Clear Cache")
            }
        }
    }
}

@Composable
internal fun PrivacySettingsSection(onSignOut: () -> Unit) {
    SettingsSection("Privacy") {
        Text("Sign Out", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Invalidates the session on VRChat's side and clears local cookies. " +
                "You'll need to log in again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onSignOut) {
            Text("Sign Out", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun AboutSettingsSection(onNavigateToCredits: () -> Unit) {
    SettingsSection("About") {
        Text(
            "VRCX Android v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "VRChat Companion App",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToCredits)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "Credits",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    VrcxCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
internal fun SettingToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
