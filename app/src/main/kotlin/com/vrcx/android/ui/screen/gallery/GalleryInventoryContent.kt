package com.vrcx.android.ui.screen.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.ui.components.EmptyState

private data class InventoryPresentation(
    val imageUrl: String,
    val name: String,
    val description: String,
    val typeLabel: String,
    val isBundle: Boolean,
)

@Composable
internal fun InventoryGridContent(
    items: List<InventoryItem>,
    templates: List<InventoryTemplate>,
    onConsume: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(message = "No inventory items", icon = Icons.Outlined.Inventory2)
    } else {
        val templateMap = remember(templates) { templates.associateBy { it.id } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = galleryGridPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                InventoryGridCell(
                    item = item,
                    template = templateMap[item.templateId],
                    onConsume = onConsume,
                )
            }
        }
    }
}

@Composable
private fun InventoryGridCell(item: InventoryItem, template: InventoryTemplate?, onConsume: (String) -> Unit) {
    val presentation = item.presentation(template)
    Column {
        InventoryImage(presentation)
        InventoryDetails(presentation)
        if (presentation.isBundle) {
            FilledTonalButton(
                onClick = { onConsume(item.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text("Consume")
            }
        }
    }
}

@Composable
private fun InventoryImage(presentation: InventoryPresentation) {
    if (presentation.imageUrl.isNotBlank()) {
        AsyncImage(
            model = presentation.imageUrl,
            contentDescription = presentation.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InventoryDetails(presentation: InventoryPresentation) {
    Text(
        text = presentation.name,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (presentation.description.isNotBlank()) {
        Text(
            text = presentation.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        text = presentation.typeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun InventoryItem.presentation(template: InventoryTemplate?): InventoryPresentation = InventoryPresentation(
    imageUrl = imageUrl.ifBlank { template?.imageUrl.orEmpty() },
    name = name.ifBlank { template?.name ?: templateId },
    description = description.ifBlank { template?.description.orEmpty() },
    typeLabel = itemTypeLabel(itemType),
    isBundle = itemType == "bundle",
)

private fun itemTypeLabel(type: String): String = when (type) {
    "prop" -> "Item"
    "sticker" -> "Sticker"
    "droneskin" -> "Drone Skin"
    "emoji" -> "Emoji"
    "bundle" -> "Bundle"
    else -> type.replaceFirstChar { it.uppercase() }
}
