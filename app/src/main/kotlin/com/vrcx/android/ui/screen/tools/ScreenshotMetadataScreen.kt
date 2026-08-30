@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.vrcx.android.ui.screen.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.screenshot.ScreenshotReadResult
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar

@Composable
fun ScreenshotMetadataScreen(
    viewModel: ScreenshotMetadataViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVrcPlusSupporter by viewModel.isVrcPlusSupporter.collectAsStateWithLifecycle()
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(viewModel::loadScreenshot)
        }
    val browsePng = { imagePicker.launch("image/png") }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Screenshot Metadata", onBack = onBack)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenshotBrowserCard(
                onBrowsePng = browsePng,
                onBrowseImage = { imagePicker.launch("image/*") },
            )
            ScreenshotMetadataContent(
                state = uiState,
                isVrcPlusSupporter = isVrcPlusSupporter,
                onUpload = viewModel::uploadSelectedScreenshotToGallery,
                onBrowse = browsePng,
                onUserClick = onUserClick,
                onWorldClick = onWorldClick,
            )
        }
    }
}

@Composable
private fun ScreenshotBrowserCard(onBrowsePng: () -> Unit, onBrowseImage: () -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Inspect VRChat screenshots", style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    "Choose a PNG screenshot to read embedded VRChat or VRCX metadata, " +
                        "including world, author, and player list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onBrowsePng) {
                    Text("Browse PNG")
                }
                OutlinedButton(onClick = onBrowseImage) {
                    Text("Browse Image")
                }
            }
        }
    }
}

@Composable
private fun ScreenshotMetadataContent(
    state: ScreenshotMetadataUiState,
    isVrcPlusSupporter: Boolean,
    onUpload: () -> Unit,
    onBrowse: () -> Unit,
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    when (val result = state.result) {
        is ScreenshotReadResult.Parsed ->
            MetadataDetails(
                state = state,
                parsed = result,
                isVrcPlusSupporter = isVrcPlusSupporter,
                onUpload = onUpload,
                onUserClick = onUserClick,
                onWorldClick = onWorldClick,
            )

        is ScreenshotReadResult.NoMetadata ->
            ErrorMetadataCard(
                message = "Image has no valid VRChat or VRCX metadata.",
                onBrowse = onBrowse,
            )

        is ScreenshotReadResult.Failed ->
            ErrorMetadataCard(message = result.message, onBrowse = onBrowse)

        null -> if (state.isLoading) LoadingMetadataCard() else EmptyMetadataCard()
    }
}

@Composable
private fun EmptyMetadataCard() {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No screenshot selected", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Pick a VRChat PNG screenshot to inspect its embedded metadata.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingMetadataCard() {
    VrcxCard {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorMetadataCard(message: String, onBrowse: () -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Metadata unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onBrowse) {
                Text("Choose Another")
            }
        }
    }
}
