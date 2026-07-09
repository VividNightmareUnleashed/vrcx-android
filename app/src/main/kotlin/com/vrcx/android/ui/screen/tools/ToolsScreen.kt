package com.vrcx.android.ui.screen.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.navigation.VrcxRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ToolsViewModel @Inject constructor(
    preferences: VrcxPreferences,
) : ViewModel() {
    private val _targetId = MutableStateFlow("")
    val targetId: StateFlow<String> = _targetId.asStateFlow()

    val backgroundServiceEnabled = preferences.backgroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun updateTargetId(value: String) {
        _targetId.value = value
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val targetId by viewModel.targetId.collectAsStateWithLifecycle()
    val backgroundServiceEnabled by viewModel.backgroundServiceEnabled.collectAsStateWithLifecycle()

    val resolvedRoute by remember {
        derivedStateOf { resolveOpenByIdRoute(targetId) }
    }

    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Tools", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VrcxCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Open by ID", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Jump straight to a user, world, avatar, or group by pasting its VRChat ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("ID", style = MaterialTheme.typography.labelLarge)
                    VrcxInputField(
                        value = targetId,
                        onValueChange = viewModel::updateTargetId,
                        placeholder = "usr_..., wrld_..., avtr_..., grp_...",
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { resolvedRoute?.let(onOpenRoute) },
                            enabled = resolvedRoute != null,
                        ) {
                            Text("Open")
                        }
                    }
                }
            }

            VrcxCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quick Links", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.DASHBOARD) }) { Text("Dashboard") }
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.GAME_LOG) }) { Text("Activity History") }
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.PLAYER_LIST) }) { Text("Friends Roster") }
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.GALLERY) }) { Text("Gallery") }
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.SETTINGS) }) { Text("Settings") }
                    }
                }
            }

            VrcxCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android ${Build.VERSION.SDK_INT}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Background Service: ${if (backgroundServiceEnabled) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Notifications: ${if (notificationsGranted) "Granted" else "Not granted"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        Text(
                            "Android 15+ requires reopening the app after reboot before the background websocket can resume.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun resolveOpenByIdRoute(rawId: String): String? {
    val id = rawId.trim()
    return when {
        id.startsWith("usr_") -> VrcxRoutes.userDetail(id)
        id.startsWith("wrld_") -> VrcxRoutes.worldDetail(id)
        id.startsWith("avtr_") -> VrcxRoutes.avatarDetail(id)
        id.startsWith("grp_") -> VrcxRoutes.groupDetail(id)
        else -> null
    }
}
