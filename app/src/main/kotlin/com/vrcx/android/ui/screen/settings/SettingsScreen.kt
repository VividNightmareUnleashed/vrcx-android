package com.vrcx.android.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
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

    fun setDynamicColors(enabled: Boolean) { viewModelScope.launch { preferences.setDynamicColors(enabled) } }
    fun setNotifyFriendOnline(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_ONLINE, v) } }
    fun setNotifyFriendOffline(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_OFFLINE, v) } }
    fun setNotifyInvite(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_INVITE, v) } }
    fun setNotifyFriendRequest(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_REQUEST, v) } }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val notifyOnline by viewModel.notifyFriendOnline.collectAsState()
    val notifyOffline by viewModel.notifyFriendOffline.collectAsState()
    val notifyInvite by viewModel.notifyInvite.collectAsState()
    val notifyFriendRequest by viewModel.notifyFriendRequest.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SettingToggle("Dynamic Colors", "Use Material You colors", dynamicColors, viewModel::setDynamicColors)

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
