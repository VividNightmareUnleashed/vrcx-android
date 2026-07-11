package com.vrcx.android.ui.screen.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.navigation.VrcxRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FriendExportFormat { CSV, JSON }

@HiltViewModel
class ToolsViewModel @Inject constructor(
    preferences: VrcxPreferences,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val memoDao: MemoDao,
) : ViewModel() {
    private val _targetId = MutableStateFlow("")
    val targetId: StateFlow<String> = _targetId.asStateFlow()

    val backgroundServiceEnabled = preferences.backgroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun updateTargetId(value: String) {
        _targetId.value = value
    }

    internal fun exportFriends(context: Context, uri: Uri, format: FriendExportFormat) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            _exportMessage.value = null
            try {
                val initialUser = (authRepository.authState.value as? AuthState.LoggedIn)?.user
                    ?: error("No logged-in user")
                val ownerUserId = initialUser.id
                val memos = withContext(Dispatchers.IO) { memoDao.getMemos(ownerUserId) }
                val currentUser = (authRepository.authState.value as? AuthState.LoggedIn)?.user
                    ?.takeIf { it.id == ownerUserId }
                    ?: error("Account changed while preparing export")
                val prefix = "$ownerUserId:"
                val memoMap = memos
                    .asSequence()
                    .filter { it.odUserId.startsWith(prefix) }
                    .associate { it.odUserId.removePrefix(prefix) to it.memo }
                val rows = FriendListExport.rows(
                    currentUserFriends = currentUser.friends,
                    friends = friendRepository.friends.value,
                    memos = memoMap,
                )
                val content = when (format) {
                    FriendExportFormat.CSV -> FriendListExport.toCsv(rows)
                    FriendExportFormat.JSON -> FriendListExport.toJson(rows)
                }
                withContext(Dispatchers.IO) {
                    val stream = context.applicationContext.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Unable to open the selected destination")
                    OutputStreamWriter(stream, StandardCharsets.UTF_8).use { it.write(content) }
                }
                _exportMessage.value = "Exported ${rows.size} friends."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportMessage.value = "Export failed: ${e.message ?: "Unknown error"}"
            } finally {
                _isExporting.value = false
            }
        }
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
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportFriends(context, it, FriendExportFormat.CSV) }
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportFriends(context, it, FriendExportFormat.JSON) }
    }

    val resolvedRoute = resolveOpenByIdRoute(targetId)

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
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.SCREENSHOT_METADATA) }) { Text("Screenshot Info") }
                        FilledTonalButton(onClick = { onOpenRoute(VrcxRoutes.SETTINGS) }) { Text("Settings") }
                    }
                }
            }

            VrcxCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Friend List Export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save the current live friend list, including your local memos in CSV exports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { csvExporter.launch("vrcx-friends.csv") },
                            enabled = !isExporting,
                        ) { Text("Export CSV") }
                        OutlinedButton(
                            onClick = { jsonExporter.launch("vrcx-friends.json") },
                            enabled = !isExporting,
                        ) { Text("Export JSON") }
                    }
                    if (isExporting) Text("Exporting…", style = MaterialTheme.typography.bodySmall)
                    exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    if (!VRCHAT_ID_REGEX.matches(id)) return null
    return when {
        id.startsWith("usr_") -> VrcxRoutes.userDetail(id)
        id.startsWith("wrld_") -> VrcxRoutes.worldDetail(id)
        id.startsWith("avtr_") -> VrcxRoutes.avatarDetail(id)
        id.startsWith("grp_") -> VrcxRoutes.groupDetail(id)
        else -> null
    }
}

private val VRCHAT_ID_REGEX = Regex("^(usr_|wrld_|avtr_|grp_)[A-Za-z0-9_-]+$")
