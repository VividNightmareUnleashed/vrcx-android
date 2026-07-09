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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
    private val profilePicCacheManager: ProfilePicCacheManager,
    private val friendRepository: FriendRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val themeMode: StateFlow<String> = preferences.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")
    val notifyInvite: StateFlow<Boolean> = preferences.notifyInvite.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val notifyFriendRequest: StateFlow<Boolean> = preferences.notifyFriendRequest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val maxFeedSize: StateFlow<Int> = preferences.maxFeedSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1000)
    val autoLogin: StateFlow<Boolean> = preferences.autoLogin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setThemeMode(mode: String) { viewModelScope.launch { preferences.setThemeMode(mode) } }
    fun setDynamicColors(enabled: Boolean) { viewModelScope.launch { preferences.setDynamicColors(enabled) } }
    fun setNotifyInvite(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_INVITE, v) } }
    fun setNotifyFriendRequest(v: Boolean) { viewModelScope.launch { preferences.setNotifySetting(VrcxPreferences.NOTIFY_FRIEND_REQUEST, v) } }
    fun setMaxFeedSize(size: Int) { viewModelScope.launch { preferences.setMaxFeedSize(size) } }
    fun setAutoLogin(enabled: Boolean) { viewModelScope.launch { preferences.setAutoLogin(enabled) } }

    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun setWallpaperUri(uri: String?) { viewModelScope.launch { preferences.setWallpaperUri(uri) } }

    val wallpaperScaleMode: StateFlow<String> = preferences.wallpaperScaleMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "crop")
    fun setWallpaperScaleMode(mode: String) { viewModelScope.launch { preferences.setWallpaperScaleMode(mode) } }

    val backgroundServiceEnabled: StateFlow<Boolean> = preferences.backgroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setBackgroundServiceEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setBackgroundServiceEnabled(enabled) }
    }

    // Profile picture cache
    private val _cacheSizeText = MutableStateFlow("")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()

    private val _cacheAllProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val cacheAllProgress: StateFlow<Pair<Int, Int>?> = _cacheAllProgress.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = profilePicCacheManager.getCacheSizeBytes()
            _cacheSizeText.value = formatBytes(bytes)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun clearProfilePicCache() {
        viewModelScope.launch(Dispatchers.IO) {
            profilePicCacheManager.clearCache()
            refreshCacheSize()
        }
    }

    fun cacheAllFriends() {
        viewModelScope.launch(Dispatchers.IO) {
            val friends = friendRepository.friends.value
            profilePicCacheManager.cacheAllFriends(friends) { completed, total ->
                _cacheAllProgress.value = completed to total
            }
            _cacheAllProgress.value = null
            refreshCacheSize()
        }
    }

    fun signOut() {
        // authRepository.logout() is the single source of truth for teardown —
        // it clears session state, drops the bulk favorites cache, and stops
        // WebSocketForegroundService.
        viewModelScope.launch { authRepository.logout() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToCredits: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val notifyInvite by viewModel.notifyInvite.collectAsStateWithLifecycle()
    val notifyFriendRequest by viewModel.notifyFriendRequest.collectAsStateWithLifecycle()
    val wallpaperUri by viewModel.wallpaperUri.collectAsStateWithLifecycle()
    val wallpaperScaleMode by viewModel.wallpaperScaleMode.collectAsStateWithLifecycle()
    val backgroundServiceEnabled by viewModel.backgroundServiceEnabled.collectAsStateWithLifecycle()
    val maxFeedSize by viewModel.maxFeedSize.collectAsStateWithLifecycle()
    val autoLogin by viewModel.autoLogin.collectAsStateWithLifecycle()
    val cacheSizeText by viewModel.cacheSizeText.collectAsStateWithLifecycle()
    val cacheAllProgress by viewModel.cacheAllProgress.collectAsStateWithLifecycle()
    var showCacheAllDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

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
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("Appearance") {
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
                SettingToggle("Dynamic Colors", "Use Material You colors", dynamicColors, viewModel::setDynamicColors)

                Text("Wallpaper", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (wallpaperUri != null) "Custom wallpaper set" else "No wallpaper",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text(if (wallpaperUri != null) "Change Wallpaper" else "Set Wallpaper")
                    }
                    if (wallpaperUri != null) {
                        OutlinedButton(onClick = { viewModel.setWallpaperUri(null) }) { Text("Remove") }
                    }
                }
                if (wallpaperUri != null) {
                    Text("Scale Mode", style = MaterialTheme.typography.bodyLarge)
                    Text("How the wallpaper image is scaled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("crop" to "Crop", "fit" to "Fit", "fill_width" to "Fill W", "fill_height" to "Fill H")
                            .forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = wallpaperScaleMode == value,
                                    onClick = { viewModel.setWallpaperScaleMode(value) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                                ) { Text(label) }
                            }
                    }
                }
            }

            SettingsSection("General") {
                SettingToggle(
                    "Background Service",
                    "Keep WebSocket connected in the background when Android allows it (newer Android versions may require reopening the app after reboot)",
                    backgroundServiceEnabled,
                    viewModel::setBackgroundServiceEnabled,
                )
                SettingToggle(
                    "Auto Login",
                    "Automatically retry login with remembered credentials when no saved session is available",
                    autoLogin,
                    viewModel::setAutoLogin,
                )
                Text("Feed History", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Limit how many feed entries stay queryable for Feed and Game Log style views",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(100, 250, 500, 1000).forEachIndexed { index, size ->
                        SegmentedButton(
                            selected = maxFeedSize == size,
                            onClick = { viewModel.setMaxFeedSize(size) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        ) { Text(size.toString()) }
                    }
                }
            }

            SettingsSection("Notifications") {
                SettingToggle("Invites", "Notify on invite received", notifyInvite, viewModel::setNotifyInvite)
                SettingToggle("Friend Requests", "Notify on friend request", notifyFriendRequest, viewModel::setNotifyFriendRequest)
                Text(
                    "Per-friend notifications can be enabled from each friend's profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Storage") {
                Text("Profile Picture Cache", style = MaterialTheme.typography.bodyLarge)
                Text("Cached: $cacheSizeText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cacheAllProgress != null) {
                    val (completed, total) = cacheAllProgress!!
                    Text("Caching: $completed / $total", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (total > 0) completed.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { showCacheAllDialog = true },
                        enabled = cacheAllProgress == null,
                    ) { Text("Cache All Friends") }
                    OutlinedButton(
                        onClick = { viewModel.clearProfilePicCache() },
                        enabled = cacheAllProgress == null,
                    ) { Text("Clear Cache") }
                }
            }

            SettingsSection("Privacy") {
                Text("Sign Out", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Invalidates the session on VRChat's side and clears local cookies. You'll need to log in again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showSignOutDialog = true }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            }

            SettingsSection("About") {
                Text(
                    "VRCX Android v${com.vrcx.android.BuildConfig.VERSION_NAME}",
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
                        .clickable { onNavigateToCredits() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(12.dp))
                    Text("Credits", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showCacheAllDialog) {
            AlertDialog(
                onDismissRequest = { showCacheAllDialog = false },
                title = { Text("Cache All Friends' Pictures") },
                text = {
                    Text("This will download profile pictures for all your friends. " +
                         "Depending on how many friends you have, this may consume significant " +
                         "mobile data and storage. Continue?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCacheAllDialog = false
                        viewModel.cacheAllFriends()
                    }) { Text("Cache All") }
                },
                dismissButton = {
                    TextButton(onClick = { showCacheAllDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Sign Out?") },
                text = {
                    Text(
                        "Your session will be invalidated on VRChat's side and you'll need " +
                            "to enter your credentials again to sign back in.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                    }) {
                        Text("Sign Out", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
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
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
