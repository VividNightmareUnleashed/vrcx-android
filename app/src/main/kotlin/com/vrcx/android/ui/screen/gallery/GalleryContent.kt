package com.vrcx.android.ui.screen.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxScrollableTabRow

@Composable
internal fun GalleryContent(
    state: GalleryContentState,
    snackbarHostState: SnackbarHostState,
    onAction: (GalleryAction) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryTopBar(state = state, onAction = onAction)
            GalleryTabs(state = state, onAction = onAction)
            GalleryLoadContent(state = state, onAction = onAction)
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        GalleryUploadButton(state = state, onAction = onAction)
    }
}

@Composable
private fun BoxScope.GalleryUploadButton(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    if (state.selectedTab == GalleryTab.INVENTORY || state.selectedTabState is LoadState.Loading) return

    FloatingActionButton(
        onClick = { onAction(GalleryAction.UploadRequested) },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        if (state.isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Add, contentDescription = "Upload")
        }
    }
}

@Composable
private fun GalleryTopBar(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    VrcxDetailTopBar(
        title = "Gallery",
        onBack = { onAction(GalleryAction.NavigateBack) },
        actions = { GalleryOverflowMenu(state = state, onAction = onAction) },
    )
}

@Composable
private fun GalleryOverflowMenu(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    if (state.selectedTab != GalleryTab.GALLERY && state.selectedTab != GalleryTab.ICONS) return

    IconButton(onClick = { onAction(GalleryAction.OpenOverflowMenu) }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(
        expanded = state.isOverflowMenuExpanded,
        onDismissRequest = { onAction(GalleryAction.DismissOverflowMenu) },
    ) {
        if (state.selectedTab == GalleryTab.GALLERY) {
            DropdownMenuItem(
                text = { Text("Clear Profile Picture") },
                onClick = { onAction(GalleryAction.ClearProfilePicture) },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Clear User Icon") },
                onClick = { onAction(GalleryAction.ClearUserIcon) },
            )
        }
    }
}

@Composable
private fun GalleryTabs(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    VrcxScrollableTabRow(
        selectedTabIndex = state.selectedTab.ordinal,
        edgePadding = 16.dp,
    ) {
        GalleryTab.entries.forEach { tab ->
            Tab(
                selected = state.selectedTab == tab,
                onClick = { onAction(GalleryAction.SelectTab(tab)) },
                text = {
                    val count = state.itemCount(tab)
                    Text(if (count > 0) "${tab.label} ($count)" else tab.label)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryLoadContent(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    (state.selectedTabState as? LoadState.Loaded)?.staleError?.let { staleError ->
        GalleryStaleError(message = staleError, onRetry = { onAction(GalleryAction.Retry) })
    }
    UiStateContainer(
        isLoading = state.selectedTabState is LoadState.Loading,
        error = (state.selectedTabState as? LoadState.Failed)?.message,
        isEmpty = false,
        onRetry = { onAction(GalleryAction.Retry) },
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = (state.selectedTabState as? LoadState.Loaded)?.isRefreshing == true,
            onRefresh = { onAction(GalleryAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            GalleryTabContent(state = state, onAction = onAction)
        }
    }
}

@Composable
private fun GalleryStaleError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun GalleryTabContent(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    when (state.selectedTab) {
        GalleryTab.GALLERY -> ImageGridContent(
            images = state.galleryImages,
            emptyMessage = "No gallery images",
            onImageClick = { onAction(GalleryAction.ShowFullscreen(it)) },
            onDeleteClick = { onAction(GalleryAction.DeleteFileRequested(it, GalleryTab.GALLERY)) },
            onSetClick = { onAction(GalleryAction.SetProfilePicture(it)) },
            setLabel = "Set as Profile Pic",
        )

        GalleryTab.ICONS -> ImageGridContent(
            images = state.iconImages,
            emptyMessage = "No icons",
            onImageClick = { onAction(GalleryAction.ShowFullscreen(it)) },
            onDeleteClick = { onAction(GalleryAction.DeleteFileRequested(it, GalleryTab.ICONS)) },
            onSetClick = { onAction(GalleryAction.SetUserIcon(it)) },
            setLabel = "Set as User Icon",
        )

        GalleryTab.EMOJIS -> ImageGridContent(
            images = state.emojiImages,
            emptyMessage = "No emojis",
            onImageClick = { onAction(GalleryAction.ShowFullscreen(it)) },
            onDeleteClick = { onAction(GalleryAction.DeleteFileRequested(it, GalleryTab.EMOJIS)) },
        )

        GalleryTab.STICKERS -> ImageGridContent(
            images = state.stickerImages,
            emptyMessage = "No stickers",
            onImageClick = { onAction(GalleryAction.ShowFullscreen(it)) },
            onDeleteClick = { onAction(GalleryAction.DeleteFileRequested(it, GalleryTab.STICKERS)) },
        )

        GalleryTab.PRINTS -> PrintGridContent(
            prints = state.prints,
            onPrintClick = { onAction(GalleryAction.ShowFullscreen(it)) },
            onDeleteClick = { onAction(GalleryAction.DeletePrintRequested(it)) },
        )

        GalleryTab.INVENTORY -> InventoryGridContent(
            items = state.inventoryItems,
            templates = state.inventoryTemplates,
            onConsume = { onAction(GalleryAction.ConsumeBundleRequested(it)) },
        )
    }
}
