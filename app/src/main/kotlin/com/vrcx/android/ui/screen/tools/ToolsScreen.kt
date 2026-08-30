@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.vrcx.android.ui.screen.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.navigation.VrcxRoutes

@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val targetId by viewModel.targetId.collectAsStateWithLifecycle()
    val backgroundServiceEnabled by viewModel.backgroundServiceEnabled.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val csvExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { viewModel.exportFriends(context, it, FriendExportFormat.CSV) }
        }
    val jsonExporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let { viewModel.exportFriends(context, it, FriendExportFormat.JSON) }
        }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Tools", onBack = onBack)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenByIdCard(targetId, viewModel::updateTargetId, onOpenRoute)
            QuickLinksCard(onOpenRoute)
            FriendExportCard(
                isExporting = isExporting,
                message = exportMessage,
                onExportCsv = { csvExporter.launch("vrcx-friends.csv") },
                onExportJson = { jsonExporter.launch("vrcx-friends.json") },
            )
            DiagnosticsCard(
                backgroundServiceEnabled = backgroundServiceEnabled,
                notificationsGranted = notificationsGranted(context),
            )
        }
    }
}

@Composable
private fun OpenByIdCard(targetId: String, onTargetIdChange: (String) -> Unit, onOpenRoute: (String) -> Unit) {
    val resolvedRoute = resolveOpenByIdRoute(targetId)
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open by ID", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Jump straight to a user, world, avatar, or group by pasting its VRChat ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("ID", style = MaterialTheme.typography.labelLarge)
            VrcxInputField(
                value = targetId,
                onValueChange = onTargetIdChange,
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
}

@Composable
private fun QuickLinksCard(onOpenRoute: (String) -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick Links", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickLink("Dashboard", VrcxRoutes.DASHBOARD, onOpenRoute)
                QuickLink("Activity History", VrcxRoutes.GAME_LOG, onOpenRoute)
                QuickLink("Friends Roster", VrcxRoutes.PLAYER_LIST, onOpenRoute)
                QuickLink("Gallery", VrcxRoutes.GALLERY, onOpenRoute)
                QuickLink("Screenshot Info", VrcxRoutes.SCREENSHOT_METADATA, onOpenRoute)
                QuickLink("Settings", VrcxRoutes.SETTINGS, onOpenRoute)
            }
        }
    }
}

@Composable
private fun QuickLink(label: String, route: String, onOpenRoute: (String) -> Unit) {
    FilledTonalButton(onClick = { onOpenRoute(route) }) {
        Text(label)
    }
}

@Composable
private fun FriendExportCard(
    isExporting: Boolean,
    message: String?,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Friend List Export", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Save the current live friend list, including your local memos in CSV exports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onExportCsv, enabled = !isExporting) {
                    Text("Export CSV")
                }
                OutlinedButton(onClick = onExportJson, enabled = !isExporting) {
                    Text("Export JSON")
                }
            }
            if (isExporting) Text("Exporting…", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DiagnosticsCard(backgroundServiceEnabled: Boolean, notificationsGranted: Boolean) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Android ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text =
                    "Background Service: " +
                        if (backgroundServiceEnabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Notifications: " + if (notificationsGranted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                Text(
                    text =
                        "Android 15+ requires reopening the app after reboot before the " +
                            "background websocket can resume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun resolveOpenByIdRoute(rawId: String): String? {
    val id = rawId.trim()
    val route = ID_ROUTES.entries.firstOrNull { id.startsWith(it.key) }
    return route
        ?.takeIf { VRCHAT_ID_TAIL_REGEX.matches(id.removePrefix(it.key)) }
        ?.value
        ?.invoke(id)
}

private fun notificationsGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED

/** The VRChat id prefixes this screen can open, and where each one goes. */
private val ID_ROUTES =
    mapOf<String, (String) -> String>(
        "usr_" to VrcxRoutes::userDetail,
        "wrld_" to VrcxRoutes::worldDetail,
        "avtr_" to VrcxRoutes::avatarDetail,
        "grp_" to VrcxRoutes::groupDetail,
    )

private val VRCHAT_ID_TAIL_REGEX = Regex("^[A-Za-z0-9_-]+$")
