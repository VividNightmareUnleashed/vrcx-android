@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.vrcx.android.ui.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.screenshot.ScreenshotMetadata
import com.vrcx.android.data.screenshot.ScreenshotPlayer
import com.vrcx.android.data.screenshot.ScreenshotReadResult
import com.vrcx.android.ui.common.formatByteCount
import com.vrcx.android.ui.components.VrcxCard

private const val SCREENSHOT_ASPECT_RATIO = 16f / 9f

@Composable
internal fun MetadataDetails(
    state: ScreenshotMetadataUiState,
    parsed: ScreenshotReadResult.Parsed,
    isVrcPlusSupporter: Boolean,
    onUpload: () -> Unit,
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    ScreenshotFileCard(state, parsed, isVrcPlusSupporter, onUpload)
    WorldCard(parsed.metadata, onWorldClick)
    AuthorCard(parsed.metadata, onUserClick)
    PlayersCard(parsed.metadata.players, onUserClick)
}

@Composable
private fun ScreenshotFileCard(
    state: ScreenshotMetadataUiState,
    parsed: ScreenshotReadResult.Parsed,
    isVrcPlusSupporter: Boolean,
    onUpload: () -> Unit,
) {
    val metadata = parsed.metadata
    val uri = state.selectedUri
    val fileName = state.fileName ?: "Selected screenshot"
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            uri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = fileName,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(SCREENSHOT_ASPECT_RATIO)
                            .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ScreenshotMetadataChips(state, parsed)
            val capturedAt =
                parsed.capturedAtEpochMillis?.let(::formatCapturedAt) ?: metadata.timestamp
            capturedAt?.let { MetadataTextRow(label = "Captured", value = it) }
            metadata.note?.let { MetadataTextRow(label = "Note", value = it) }
            if (isVrcPlusSupporter && uri != null) {
                FilledTonalButton(onClick = onUpload, enabled = !state.isUploading) {
                    Text(if (state.isUploading) "Uploading..." else "Upload to Gallery")
                }
            }
            state.uploadMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotMetadataChips(state: ScreenshotMetadataUiState, parsed: ScreenshotReadResult.Parsed) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parsed.resolution?.let { MetadataChip(it) }
        state.fileSizeBytes?.let { MetadataChip(formatByteCount(it)) }
        parsed.metadata.application?.let { MetadataChip(it) }
        parsed.metadata.version?.let { MetadataChip("Schema v$it") }
    }
}

@Composable
private fun WorldCard(metadata: ScreenshotMetadata, onWorldClick: (String) -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("World", style = MaterialTheme.typography.titleMedium)
            ClickableValue(
                label = metadata.world.name ?: metadata.world.id.ifBlank { "Unknown world" },
                value = metadata.world.id,
                onClick =
                    if (metadata.world.id.startsWith("wrld_")) {
                        { onWorldClick(metadata.world.id) }
                    } else {
                        null
                    },
            )
            if (
                metadata.world.instanceId.isNotBlank() &&
                metadata.world.instanceId != metadata.world.id
            ) {
                MetadataTextRow(label = "Instance", value = metadata.world.instanceId)
            }
        }
    }
}

@Composable
private fun AuthorCard(metadata: ScreenshotMetadata, onUserClick: (String) -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Author", style = MaterialTheme.typography.titleMedium)
            ClickableValue(
                label =
                    metadata.author.displayName
                        ?: metadata.author.id.ifBlank { "Unknown author" },
                value = metadata.author.id,
                onClick =
                    if (metadata.author.id.startsWith("usr_")) {
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
private fun PlayersCard(players: List<ScreenshotPlayer>, onUserClick: (String) -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Players (${players.size})", style = MaterialTheme.typography.titleMedium)
            if (players.isEmpty()) {
                Text(
                    text = "No player list was embedded in this screenshot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                players.forEach { player -> PlayerRow(player, onUserClick) }
            }
        }
    }
}

@Composable
private fun PlayerRow(player: ScreenshotPlayer, onUserClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClickableValue(
            label = player.displayName.ifBlank { player.id.ifBlank { "Unknown player" } },
            value = player.id,
            onClick =
                if (player.id.startsWith("usr_")) {
                    { onUserClick(player.id) }
                } else {
                    null
                },
            modifier = Modifier.weight(1f),
        )
        player.pos?.let {
            Text(
                text = it.format(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetadataChip(text: String) {
    ElevatedAssistChip(onClick = {}, enabled = false, label = { Text(text) })
}

@Composable
private fun MetadataTextRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClickableValue(label: String, value: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (onClick != null) {
            TextButton(onClick = onClick, modifier = Modifier.padding(0.dp)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (value.isNotBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
