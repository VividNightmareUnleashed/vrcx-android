package com.vrcx.android.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.VrcxDetailTopBar

private const val IMAGE_MIME_TYPE = "image/*"

private enum class SettingsDialog {
    CACHE_ALL,
    SIGN_OUT,
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToCredits: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val selectWallpaper = rememberWallpaperPicker { uri ->
        viewModel.updatePreference(SettingsPreferenceUpdate.Wallpaper(uri))
    }

    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Settings", onBack = onBack)
        SettingsContent(
            state = state,
            onPreferenceUpdate = viewModel::updatePreference,
            onSelectWallpaper = selectWallpaper,
            onClearCache = viewModel::clearProfilePicCache,
            onShowCacheAllDialog = { dialog = SettingsDialog.CACHE_ALL },
            onShowSignOutDialog = { dialog = SettingsDialog.SIGN_OUT },
            onNavigateToCredits = onNavigateToCredits,
        )
    }

    SettingsDialogHost(
        dialog = dialog,
        onDismiss = { dialog = null },
        onCacheAllConfirmed = {
            dialog = null
            viewModel.cacheAllFriends()
        },
        onSignOutConfirmed = {
            dialog = null
            viewModel.signOut()
        },
    )
}

@Composable
private fun rememberWallpaperPicker(onWallpaperSelected: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    // OpenDocument provides a persistable SAF grant; never retain a URI whose grant failed.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) onWallpaperSelected(selectedUri.toString())
        }
    }
    return { picker.launch(arrayOf(IMAGE_MIME_TYPE)) }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
    onSelectWallpaper: () -> Unit,
    onClearCache: () -> Unit,
    onShowCacheAllDialog: () -> Unit,
    onShowSignOutDialog: () -> Unit,
    onNavigateToCredits: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppearanceSettingsSection(
            state = state.appearance,
            onPreferenceUpdate = onPreferenceUpdate,
            onSelectWallpaper = onSelectWallpaper,
        )
        GeneralSettingsSection(state.general, onPreferenceUpdate)
        NotificationSettingsSection(state.notifications, onPreferenceUpdate)
        StorageSettingsSection(
            state = state.storage,
            onCacheAll = onShowCacheAllDialog,
            onClearCache = onClearCache,
        )
        PrivacySettingsSection(onSignOut = onShowSignOutDialog)
        AboutSettingsSection(onNavigateToCredits = onNavigateToCredits)
    }
}

@Composable
private fun SettingsDialogHost(
    dialog: SettingsDialog?,
    onDismiss: () -> Unit,
    onCacheAllConfirmed: () -> Unit,
    onSignOutConfirmed: () -> Unit,
) {
    when (dialog) {
        null -> Unit

        SettingsDialog.CACHE_ALL -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Cache All Friends' Pictures") },
            text = {
                Text(
                    "This will download profile pictures for all your friends. " +
                        "Depending on how many friends you have, this may consume significant " +
                        "mobile data and storage. Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = onCacheAllConfirmed) { Text("Cache All") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )

        SettingsDialog.SIGN_OUT -> ConfirmDialog(
            title = "Sign Out?",
            message = "Your session will be invalidated on VRChat's side and you'll need " +
                "to enter your credentials again to sign back in.",
            confirmLabel = "Sign Out",
            onConfirm = onSignOutConfirmed,
            onDismiss = onDismiss,
        )
    }
}
