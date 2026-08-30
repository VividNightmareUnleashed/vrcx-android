package com.vrcx.android.ui.screen.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.api.model.imageUrl
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState

private const val PRINT_ASPECT_RATIO = 16f / 9f

@Composable
internal fun ImageGridContent(
    images: List<GalleryImage>,
    emptyMessage: String,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onSetClick: ((String) -> Unit)? = null,
    setLabel: String? = null,
) {
    if (images.isEmpty()) {
        EmptyState(message = emptyMessage, icon = Icons.Outlined.Image)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = galleryGridPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(images, key = { it.id }) { image ->
                ImageGridCell(
                    imageUrl = image.imageUrl(),
                    fileId = image.id,
                    onImageClick = onImageClick,
                    onDeleteClick = onDeleteClick,
                    onSetClick = onSetClick,
                    setLabel = setLabel,
                )
            }
        }
    }
}

@Composable
private fun ImageGridCell(
    imageUrl: String?,
    fileId: String,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onSetClick: ((String) -> Unit)?,
    setLabel: String?,
) {
    Column {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable { imageUrl?.let(onImageClick) },
            contentScale = ContentScale.Crop,
        )
        ImageGridActions(
            imageUrl = imageUrl,
            fileId = fileId,
            onImageClick = onImageClick,
            onDeleteClick = onDeleteClick,
            onSetClick = onSetClick,
            setLabel = setLabel,
        )
    }
}

@Composable
private fun ImageGridActions(
    imageUrl: String?,
    fileId: String,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onSetClick: ((String) -> Unit)?,
    setLabel: String?,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        onSetClick?.let { setImage ->
            IconButton(onClick = { setImage(fileId) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Person, contentDescription = setLabel, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(
            onClick = { imageUrl?.let(onImageClick) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.Default.Fullscreen, contentDescription = "View full size", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onDeleteClick(fileId) }, modifier = Modifier.size(32.dp)) {
            DeleteIcon()
        }
    }
}

@Composable
internal fun PrintGridContent(
    prints: List<VrcPrint>,
    onPrintClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
) {
    if (prints.isEmpty()) {
        EmptyState(message = "No prints", icon = Icons.Outlined.Image)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = galleryGridPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(prints, key = { it.id }) { print ->
                PrintGridCell(print = print, onPrintClick = onPrintClick, onDeleteClick = onDeleteClick)
            }
        }
    }
}

@Composable
private fun PrintGridCell(print: VrcPrint, onPrintClick: (String) -> Unit, onDeleteClick: (String) -> Unit) {
    Column {
        AsyncImage(
            model = print.files.image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PRINT_ASPECT_RATIO)
                .clip(MaterialTheme.shapes.medium)
                .clickable { onPrintClick(print.files.image) },
            contentScale = ContentScale.Crop,
        )
        PrintDetails(print)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onPrintClick(print.files.image) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "View full size",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { onDeleteClick(print.id) }, modifier = Modifier.size(32.dp)) {
                DeleteIcon()
            }
        }
    }
}

@Composable
private fun PrintDetails(print: VrcPrint) {
    if (print.note.isNotBlank()) {
        Text(
            text = print.note,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (print.worldName.isNotBlank()) {
        Text(
            text = print.worldName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (print.createdAt.isNotBlank()) {
        Text(
            text = relativeTime(print.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteIcon() {
    Icon(
        Icons.Default.Delete,
        contentDescription = "Delete",
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.error,
    )
}

internal fun galleryGridPadding(): PaddingValues = PaddingValues(
    start = 8.dp,
    end = 8.dp,
    top = 8.dp,
    bottom = 80.dp,
)
