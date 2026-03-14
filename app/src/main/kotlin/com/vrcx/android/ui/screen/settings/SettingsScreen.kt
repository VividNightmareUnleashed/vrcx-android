package com.vrcx.android.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
) : ViewModel() {
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val themeMode: StateFlow<String> = preferences.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val notifyFriendOnline: StateFlow<Boolean> = preferences.notifyFriendOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val notifyFriendOffline: StateFlow<Boolean> = preferences.notifyFriendOffline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val notifyInvite: StateFlow<Boolean> = preferences.notifyInvite.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val notifyFriendRequest: StateFlow<Boolean> = preferences.notifyFriendRequest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setThemeMode(mode: String) { viewModelScope.launch { preferences.setThemeMode(mode) } }
    fun setDynamicColors(enabled: Boolean) { viewModelScope.launch { preferences.setDynamicColors(enabled) } }
    fun setNotifyFriendOnline(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_ONLINE, v) } }
    fun setNotifyFriendOffline(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_OFFLINE, v) } }
    fun setNotifyInvite(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_INVITE, v) } }
    fun setNotifyFriendRequest(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_REQUEST, v) } }

    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun setWallpaperUri(uri: String?) { viewModelScope.launch { preferences.setWallpaperUri(uri) } }

    val backgroundServiceEnabled: StateFlow<Boolean> = preferences.backgroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setBackgroundServiceEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setBackgroundServiceEnabled(enabled) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToCredits: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val notifyOnline by viewModel.notifyFriendOnline.collectAsState()
    val notifyOffline by viewModel.notifyFriendOffline.collectAsState()
    val notifyInvite by viewModel.notifyInvite.collectAsState()
    val notifyFriendRequest by viewModel.notifyFriendRequest.collectAsState()
    val wallpaperUri by viewModel.wallpaperUri.collectAsState()
    val backgroundServiceEnabled by viewModel.backgroundServiceEnabled.collectAsState()

    val context = LocalContext.current
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        viewModel.setWallpaperUri(uri.toString())
    }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Settings", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Text("Choose light, dark, or system default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                .forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = themeMode == value,
                        onClick = { viewModel.setThemeMode(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    ) { Text(label) }
                }
        }
        Spacer(Modifier.height(8.dp))

        SettingToggle("Dynamic Colors", "Use Material You colors", dynamicColors, viewModel::setDynamicColors)

        Spacer(Modifier.height(12.dp))
        Text("Wallpaper", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (wallpaperUri != null) "Custom wallpaper set" else "No wallpaper",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (wallpaperUri != null) "Change Wallpaper" else "Set Wallpaper")
            }
            if (wallpaperUri != null) {
                OutlinedButton(onClick = { viewModel.setWallpaperUri(null) }) {
                    Text("Remove")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("General", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SettingToggle(
            "Background Service",
            "Keep WebSocket connected when app is in background",
            backgroundServiceEnabled,
            viewModel::setBackgroundServiceEnabled,
        )

        Spacer(Modifier.height(24.dp))
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SettingToggle("Friend Online", "Notify when friends come online", notifyOnline, viewModel::setNotifyFriendOnline)
        SettingToggle("Friend Offline", "Notify when friends go offline", notifyOffline, viewModel::setNotifyFriendOffline)
        SettingToggle("Invites", "Notify on invite received", notifyInvite, viewModel::setNotifyInvite)
        SettingToggle("Friend Requests", "Notify on friend request", notifyFriendRequest, viewModel::setNotifyFriendRequest)

        Spacer(Modifier.height(24.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("VRCX Android v1.0.0", style = MaterialTheme.typography.bodyMedium)
        Text("VRChat Companion App", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToCredits() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(12.dp))
            Text("Credits", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
