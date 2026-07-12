@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.vrcx.android.ui.screen.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.screenshot.ScreenshotMetadata
import com.vrcx.android.data.screenshot.ScreenshotMetadataReader
import com.vrcx.android.data.screenshot.ScreenshotMetadataResult
import com.vrcx.android.data.screenshot.ScreenshotPlayer
import com.vrcx.android.data.screenshot.ScreenshotPosition
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import com.vrcx.android.data.util.UploadBytesResult
import com.vrcx.android.data.util.readUploadBytesBounded
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenshotMetadataUiState(
    val selectedUri: Uri? = null,
    val fileName: String? = null,
    val fileSize: String? = null,
    val fileSizeBytes: Long? = null,
    val result: ScreenshotMetadataResult? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val uploadMessage: String? = null,
)

@HiltViewModel
class ScreenshotMetadataViewModel @Inject constructor(
    private val galleryRepository: GalleryRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScreenshotMetadataUiState())
    val uiState: StateFlow<ScreenshotMetadataUiState> = _uiState.asStateFlow()
    val isVrcPlusSupporter: StateFlow<Boolean> = authRepository.authState
        .map { state -> (state as? AuthState.LoggedIn)?.user?.tags?.contains("system_supporter") == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var selectionGeneration = 0L
    private var loadJob: Job? = null
    private var uploadJob: Job? = null

    fun loadScreenshot(context: Context, uri: Uri) {
        loadJob?.cancel()
        uploadJob?.cancel()
        val generation = ++selectionGeneration
        _uiState.value = ScreenshotMetadataUiState(selectedUri = uri, isLoading = true)
        loadJob = viewModelScope.launch {
            val appContext = context.applicationContext
            try {
                val details = withContext(Dispatchers.IO) { appContext.queryOpenableFile(uri) }
                val result = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        ScreenshotMetadataReader.read(input, details.fileName)
                    } ?: ScreenshotMetadataResult(
                        error = "Unable to open the selected image.",
                        fileName = details.fileName,
                    )
                }
                updateIfCurrent(uri, generation) { current ->
                    current.copy(
                        fileName = details.fileName ?: result.fileName,
                        fileSize = details.fileSize,
                        fileSizeBytes = details.fileSizeBytes,
                        result = result,
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateIfCurrent(uri, generation) { current ->
                    current.copy(
                        result = ScreenshotMetadataResult(error = "Unable to read the selected image."),
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun uploadSelectedScreenshotToGallery(context: Context) {
        val state = _uiState.value
        val uri = state.selectedUri ?: return
        val generation = selectionGeneration
        if (!isVrcPlusSupporter.value) {
            _uiState.value = state.copy(uploadMessage = "VRC+ is required to upload gallery images.")
            return
        }
        if (state.fileSizeBytes != null && state.fileSizeBytes > MAX_UPLOAD_SIZE_BYTES) {
            _uiState.value = state.copy(uploadMessage = "Image too large (max 10 MB).")
            return
        }

        uploadJob?.cancel()
        updateIfCurrent(uri, generation) { it.copy(isUploading = true, uploadMessage = null) }
        uploadJob = viewModelScope.launch {
            val appContext = context.applicationContext
            try {
                val mimeType = appContext.contentResolver.getType(uri) ?: "image/png"
                val fileName = state.fileName?.takeIf { it.isNotBlank() }
                    ?: GalleryRepository.defaultFileNameFor(mimeType)
                val readResult = withContext(Dispatchers.IO) {
                    try {
                        readUploadBytesBounded(appContext.contentResolver.openInputStream(uri))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        UploadBytesResult.Unreadable
                    }
                }

                val bytes = when (readResult) {
                    UploadBytesResult.Unreadable -> {
                        updateIfCurrent(uri, generation) {
                            it.copy(isUploading = false, uploadMessage = "Unable to open the selected image.")
                        }
                        return@launch
                    }
                    UploadBytesResult.TooLarge -> {
                        updateIfCurrent(uri, generation) {
                            it.copy(isUploading = false, uploadMessage = "Image too large (max 10 MB).")
                        }
                        return@launch
                    }
                    is UploadBytesResult.Success -> readResult.bytes
                }
                if (!isCurrent(uri, generation)) return@launch
                withContext(Dispatchers.IO) {
                    galleryRepository.uploadFile("gallery", bytes, mimeType, fileName)
                    galleryRepository.loadGallery()
                }
                updateIfCurrent(uri, generation) {
                    it.copy(isUploading = false, uploadMessage = "Image uploaded to gallery.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateIfCurrent(uri, generation) {
                    it.copy(
                        isUploading = false,
                        uploadMessage = "Upload failed: ${e.message ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun isCurrent(uri: Uri, generation: Long): Boolean =
        generation == selectionGeneration && _uiState.value.selectedUri == uri

    private fun updateIfCurrent(
        uri: Uri,
        generation: Long,
        transform: (ScreenshotMetadataUiState) -> ScreenshotMetadataUiState,
    ) {
        if (generation != selectionGeneration) return
        _uiState.update { current ->
            if (current.selectedUri == uri) transform(current) else current
        }
    }
}

@Composable
fun ScreenshotMetadataScreen(
    viewModel: ScreenshotMetadataViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVrcPlusSupporter by viewModel.isVrcPlusSupporter.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.loadScreenshot(context, uri)
    }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Screenshot Metadata", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VrcxCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Inspect VRChat screenshots", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose a PNG screenshot to read embedded VRChat or VRCX metadata, including world, author, and player list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { imagePicker.launch("image/png") }) {
                            Text("Browse PNG")
                        }
                        OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                            Text("Browse Image")
                        }
                    }
                }
            }

            when {
                uiState.isLoading -> LoadingMetadataCard()
                uiState.result?.metadata != null -> MetadataDetails(
                    uri = uiState.selectedUri,
                    result = uiState.result ?: ScreenshotMetadataResult(),
                    fileName = uiState.fileName,
                    fileSize = uiState.fileSize,
                    isVrcPlusSupporter = isVrcPlusSupporter,
                    isUploading = uiState.isUploading,
                    uploadMessage = uiState.uploadMessage,
                    onUpload = { viewModel.uploadSelectedScreenshotToGallery(context) },
                    onUserClick = onUserClick,
                    onWorldClick = onWorldClick,
                )
                uiState.result?.error != null -> ErrorMetadataCard(
                    message = uiState.result?.error.orEmpty(),
                    onBrowse = { imagePicker.launch("image/png") },
                )
                else -> EmptyMetadataCard()
            }
        }
    }
}

@Composable
private fun EmptyMetadataCard() {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No screenshot selected", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a VRChat PNG screenshot to inspect its embedded metadata.",
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorMetadataCard(
    message: String,
    onBrowse: () -> Unit,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Metadata unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onBrowse) {
                Text("Choose Another")
            }
        }
    }
}

@Composable
private fun MetadataDetails(
    uri: Uri?,
    result: ScreenshotMetadataResult,
    fileName: String?,
    fileSize: String?,
    isVrcPlusSupporter: Boolean,
    isUploading: Boolean,
    uploadMessage: String?,
    onUpload: () -> Unit,
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    val metadata = result.metadata ?: return
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            uri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = fileName ?: "Selected screenshot",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = fileName ?: result.fileName ?: "Selected screenshot",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.resolution?.let { MetadataChip(it) }
                fileSize?.let { MetadataChip(it) }
                metadata.application?.let { MetadataChip(it) }
                metadata.version?.let { MetadataChip("Schema v$it") }
            }
            val capturedAt = result.capturedAtEpochMillis?.let(::formatCapturedAt)
                ?: metadata.timestamp
            capturedAt?.let { timestamp ->
                MetadataTextRow(label = "Captured", value = timestamp)
            }
            metadata.note?.let { note ->
                MetadataTextRow(label = "Note", value = note)
            }
            if (isVrcPlusSupporter && uri != null) {
                FilledTonalButton(
                    onClick = onUpload,
                    enabled = !isUploading,
                ) {
                    Text(if (isUploading) "Uploading..." else "Upload to Gallery")
                }
            }
            uploadMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    WorldCard(metadata, onWorldClick)
    AuthorCard(metadata, onUserClick)
    PlayersCard(metadata.players, onUserClick)
}

@Composable
private fun WorldCard(
    metadata: ScreenshotMetadata,
    onWorldClick: (String) -> Unit,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("World", style = MaterialTheme.typography.titleMedium)
            ClickableValue(
                label = metadata.world.name ?: metadata.world.id.ifBlank { "Unknown world" },
                value = metadata.world.id,
                onClick = if (metadata.world.id.startsWith("wrld_")) {
                    { onWorldClick(metadata.world.id) }
                } else {
                    null
                },
            )
            if (metadata.world.instanceId.isNotBlank() && metadata.world.instanceId != metadata.world.id) {
                MetadataTextRow(label = "Instance", value = metadata.world.instanceId)
            }
        }
    }
}

@Composable
private fun AuthorCard(
    metadata: ScreenshotMetadata,
    onUserClick: (String) -> Unit,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Author", style = MaterialTheme.typography.titleMedium)
            ClickableValue(
                label = metadata.author.displayName ?: metadata.author.id.ifBlank { "Unknown author" },
                value = metadata.author.id,
                onClick = if (metadata.author.id.startsWith("usr_")) {
                    { onUserClick(metadata.author.id) }
                } else {
                    null
                },
            )
            metadata.pos?.let { MetadataTextRow(label = "Position", value = it.format()) }
        }
    }
}

@Composable
private fun PlayersCard(
    players: List<ScreenshotPlayer>,
    onUserClick: (String) -> Unit,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Players (${players.size})", style = MaterialTheme.typography.titleMedium)
            if (players.isEmpty()) {
                Text(
                    "No player list was embedded in this screenshot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                players.forEach { player ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ClickableValue(
                            label = player.displayName.ifBlank { player.id.ifBlank { "Unknown player" } },
                            value = player.id,
                            onClick = if (player.id.startsWith("usr_")) {
                                { onUserClick(player.id) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        player.pos?.let {
                            Text(
                                it.format(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataChip(text: String) {
    ElevatedAssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text) },
    )
}

@Composable
private fun MetadataTextRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClickableValue(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (onClick != null) {
            TextButton(
                onClick = onClick,
                modifier = Modifier.padding(0.dp),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class OpenableFileDetails(
    val fileName: String?,
    val fileSize: String?,
    val fileSizeBytes: Long?,
)

private fun Context.queryOpenableFile(uri: Uri): OpenableFileDetails {
    var name: String? = null
    var size: String? = null
    var sizeBytes: Long? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                size = sizeBytes?.let(::formatByteCount)
            }
        }
    }
    return OpenableFileDetails(fileName = name, fileSize = size, fileSizeBytes = sizeBytes)
}

private fun formatByteCount(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

private fun ScreenshotPosition.format(): String {
    return String.format(Locale.US, "(%.2f, %.2f, %.2f)", x, y, z)
}

private fun formatCapturedAt(epochMillis: Long): String {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}

