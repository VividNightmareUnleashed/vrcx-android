package com.vrcx.android.ui.screen.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.vrcx.android.ui.components.ConfirmDialog

@Composable
internal fun GalleryDialogs(state: GalleryDialogsState, onAction: (GalleryAction) -> Unit) {
    state.fullscreenImageUrl?.let { imageUrl ->
        FullscreenImageDialog(
            imageUrl = imageUrl,
            onDismiss = { onAction(GalleryAction.DismissFullscreen) },
        )
    }
    state.confirmation?.let { confirmation ->
        GalleryConfirmationDialog(confirmation = confirmation, onAction = onAction)
    }
}

@Composable
private fun GalleryConfirmationDialog(confirmation: GalleryConfirmation, onAction: (GalleryAction) -> Unit) {
    val (title, message) = when (confirmation) {
        is GalleryConfirmation.DeleteFile -> "Delete Image" to "Are you sure you want to delete this image?"
        is GalleryConfirmation.DeletePrint -> "Delete Print" to "Are you sure you want to delete this print?"
        is GalleryConfirmation.ConsumeBundle -> "Consume Bundle" to "Consume this bundle? This cannot be undone."
    }
    ConfirmDialog(
        title = title,
        message = message,
        onConfirm = { onAction(GalleryAction.ConfirmRequested) },
        onDismiss = { onAction(GalleryAction.DismissConfirmation) },
    )
}

@Composable
private fun FullscreenImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(onClick = {}),
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
