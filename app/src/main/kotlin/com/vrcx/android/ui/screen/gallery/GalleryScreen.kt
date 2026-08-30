package com.vrcx.android.ui.screen.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.api.model.imageUrl
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxScrollableTabRow

private data class GalleryContentState(
    val uiState: GalleryUiState,
    val isUploading: Boolean,
    val isOverflowMenuExpanded: Boolean,
    val galleryImages: List<GalleryImage>,
    val iconImages: List<GalleryImage>,
    val emojiImages: List<GalleryImage>,
    val stickerImages: List<GalleryImage>,
    val prints: List<VrcPrint>,
    val inventoryItems: List<InventoryItem>,
    val inventoryTemplates: List<InventoryTemplate>,
) {
    val selectedTab: GalleryTab get() = uiState.selectedTab
    val selectedTabState: LoadState<Unit> get() = uiState.selectedTabState

    fun itemCount(tab: GalleryTab): Int = when (tab) {
        GalleryTab.GALLERY -> galleryImages.size
        GalleryTab.ICONS -> iconImages.size
        GalleryTab.EMOJIS -> emojiImages.size
        GalleryTab.STICKERS -> stickerImages.size
        GalleryTab.PRINTS -> prints.size
        GalleryTab.INVENTORY -> inventoryItems.size
    }
}

private data class GalleryRouteState(
    val content: GalleryContentState,
    val snackbarMessage: String?,
    val fullscreenImageUrl: String?,
)

private sealed interface GalleryConfirmation {
    data class DeleteFile(val fileId: String, val tab: GalleryTab) : GalleryConfirmation
    data class DeletePrint(val printId: String) : GalleryConfirmation
    data class ConsumeBundle(val itemId: String) : GalleryConfirmation
}

private data class GalleryDialogsState(val fullscreenImageUrl: String?, val confirmation: GalleryConfirmation?)

private sealed interface GalleryAction {
    data object NavigateBack : GalleryAction
    data object OpenOverflowMenu : GalleryAction
    data object DismissOverflowMenu : GalleryAction
    data object ClearProfilePicture : GalleryAction
    data object ClearUserIcon : GalleryAction
    data object Retry : GalleryAction
    data object Refresh : GalleryAction
    data object UploadRequested : GalleryAction
    data object DismissFullscreen : GalleryAction
    data object DismissConfirmation : GalleryAction
    data object ConfirmRequested : GalleryAction
    data class SelectTab(val tab: GalleryTab) : GalleryAction
    data class ShowFullscreen(val imageUrl: String) : GalleryAction
    data class DeleteFileRequested(val fileId: String, val tab: GalleryTab) : GalleryAction
    data class DeletePrintRequested(val printId: String) : GalleryAction
    data class ConsumeBundleRequested(val itemId: String) : GalleryAction
    data class SetProfilePicture(val fileId: String) : GalleryAction
    data class SetUserIcon(val fileId: String) : GalleryAction
}

@Composable
fun GalleryScreen(viewModel: GalleryViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    var confirmation by remember { mutableStateOf<GalleryConfirmation?>(null) }
    var isOverflowMenuExpanded by remember { mutableStateOf(false) }
    val state = galleryRouteState(viewModel, isOverflowMenuExpanded)
    val snackbarHostState = remember { SnackbarHostState() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (state.content.selectedTab == GalleryTab.PRINTS) {
            viewModel.uploadPrint(uri, null)
        } else {
            viewModel.uploadFile(uri, state.content.selectedTab)
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    val onAction: (GalleryAction) -> Unit = { action ->
        when (action) {
            GalleryAction.NavigateBack -> onBack()

            GalleryAction.OpenOverflowMenu -> isOverflowMenuExpanded = true

            GalleryAction.DismissOverflowMenu -> isOverflowMenuExpanded = false

            GalleryAction.ClearProfilePicture -> {
                isOverflowMenuExpanded = false
                viewModel.clearProfilePic()
            }

            GalleryAction.ClearUserIcon -> {
                isOverflowMenuExpanded = false
                viewModel.clearUserIcon()
            }

            GalleryAction.Retry -> viewModel.retry()

            GalleryAction.Refresh -> viewModel.refresh()

            GalleryAction.UploadRequested -> {
                if (!state.content.isUploading) imagePicker.launch("image/*")
            }

            GalleryAction.DismissFullscreen -> viewModel.dismissFullscreen()

            GalleryAction.DismissConfirmation -> confirmation = null

            GalleryAction.ConfirmRequested -> {
                when (val target = confirmation) {
                    is GalleryConfirmation.DeleteFile -> viewModel.deleteFile(target.fileId, target.tab)
                    is GalleryConfirmation.DeletePrint -> viewModel.deletePrint(target.printId)
                    is GalleryConfirmation.ConsumeBundle -> viewModel.consumeBundle(target.itemId)
                    null -> Unit
                }
                confirmation = null
            }

            is GalleryAction.SelectTab -> viewModel.selectTab(action.tab)

            is GalleryAction.ShowFullscreen -> viewModel.showFullscreen(action.imageUrl)

            is GalleryAction.DeleteFileRequested -> {
                confirmation = GalleryConfirmation.DeleteFile(action.fileId, action.tab)
            }

            is GalleryAction.DeletePrintRequested -> {
                confirmation = GalleryConfirmation.DeletePrint(action.printId)
            }

            is GalleryAction.ConsumeBundleRequested -> {
                confirmation = GalleryConfirmation.ConsumeBundle(action.itemId)
            }

            is GalleryAction.SetProfilePicture -> viewModel.setProfilePic(action.fileId)

            is GalleryAction.SetUserIcon -> viewModel.setUserIcon(action.fileId)
        }
    }

    GalleryContent(
        state = state.content,
        snackbarHostState = snackbarHostState,
        onAction = onAction,
    )
    GalleryDialogs(
        state = GalleryDialogsState(state.fullscreenImageUrl, confirmation),
        onAction = onAction,
    )
}

@Composable
private fun galleryRouteState(viewModel: GalleryViewModel, isOverflowMenuExpanded: Boolean): GalleryRouteState {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isUploading by viewModel.isUploading.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val fullscreenImageUrl by viewModel.fullscreenImageUrl.collectAsStateWithLifecycle()
    val galleryImages by viewModel.galleryImages.collectAsStateWithLifecycle()
    val iconImages by viewModel.iconImages.collectAsStateWithLifecycle()
    val emojiImages by viewModel.emojiImages.collectAsStateWithLifecycle()
    val stickerImages by viewModel.stickerImages.collectAsStateWithLifecycle()
    val prints by viewModel.prints.collectAsStateWithLifecycle()
    val inventoryItems by viewModel.inventoryItems.collectAsStateWithLifecycle()
    val inventoryTemplates by viewModel.inventoryTemplates.collectAsStateWithLifecycle()

    return GalleryRouteState(
        content = GalleryContentState(
            uiState = uiState,
            isUploading = isUploading,
            isOverflowMenuExpanded = isOverflowMenuExpanded,
            galleryImages = galleryImages,
            iconImages = iconImages,
            emojiImages = emojiImages,
            stickerImages = stickerImages,
            prints = prints,
            inventoryItems = inventoryItems,
            inventoryTemplates = inventoryTemplates,
        ),
        snackbarMessage = snackbarMessage,
        fullscreenImageUrl = fullscreenImageUrl,
    )
}

@Composable
private fun GalleryContent(
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
        if (state.selectedTab != GalleryTab.INVENTORY && state.selectedTabState !is LoadState.Loading) {
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
    }
}

@Composable
private fun GalleryTopBar(state: GalleryContentState, onAction: (GalleryAction) -> Unit) {
    VrcxDetailTopBar(
        title = "Gallery",
        onBack = { onAction(GalleryAction.NavigateBack) },
        actions = {
            if (state.selectedTab == GalleryTab.GALLERY || state.selectedTab == GalleryTab.ICONS) {
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
                    }
                    if (state.selectedTab == GalleryTab.ICONS) {
                        DropdownMenuItem(
                            text = { Text("Clear User Icon") },
                            onClick = { onAction(GalleryAction.ClearUserIcon) },
                        )
                    }
                }
            }
        },
    )
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = staleError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAction(GalleryAction.Retry) }) { Text("Retry") }
        }
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

@Composable
private fun GalleryDialogs(state: GalleryDialogsState, onAction: (GalleryAction) -> Unit) {
    state.fullscreenImageUrl?.let { imageUrl ->
        FullscreenImageDialog(
            imageUrl = imageUrl,
            onDismiss = { onAction(GalleryAction.DismissFullscreen) },
        )
    }

    when (val confirmation = state.confirmation) {
        is GalleryConfirmation.DeleteFile -> ConfirmDialog(
            title = "Delete Image",
            message = "Are you sure you want to delete this image?",
            onConfirm = { onAction(GalleryAction.ConfirmRequested) },
            onDismiss = { onAction(GalleryAction.DismissConfirmation) },
        )

        is GalleryConfirmation.DeletePrint -> ConfirmDialog(
            title = "Delete Print",
            message = "Are you sure you want to delete this print?",
            onConfirm = { onAction(GalleryAction.ConfirmRequested) },
            onDismiss = { onAction(GalleryAction.DismissConfirmation) },
        )

        is GalleryConfirmation.ConsumeBundle -> ConfirmDialog(
            title = "Consume Bundle",
            message = "Consume this bundle? This cannot be undone.",
            onConfirm = { onAction(GalleryAction.ConfirmRequested) },
            onDismiss = { onAction(GalleryAction.DismissConfirmation) },
        )

        null -> Unit
    }
}

@Composable
private fun ImageGridContent(
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
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
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
    onSetClick: ((String) -> Unit)? = null,
    setLabel: String? = null,
) {
    Column {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable { imageUrl?.let { onImageClick(it) } },
            contentScale = ContentScale.Crop,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (onSetClick != null) {
                IconButton(
                    onClick = { onSetClick(fileId) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Person, contentDescription = setLabel, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(
                onClick = { imageUrl?.let { onImageClick(it) } },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = "View full size", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { onDeleteClick(fileId) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PrintGridContent(prints: List<VrcPrint>, onPrintClick: (String) -> Unit, onDeleteClick: (String) -> Unit) {
    if (prints.isEmpty()) {
        EmptyState(message = "No prints", icon = Icons.Outlined.Image)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(prints, key = { it.id }) { print ->
                Column {
                    AsyncImage(
                        model = print.files.image,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onPrintClick(print.files.image) },
                        contentScale = ContentScale.Crop,
                    )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = { onPrintClick(print.files.image) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = "View full size",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { onDeleteClick(print.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryGridContent(
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
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val template = templateMap[item.templateId]
                val displayImageUrl = item.imageUrl.ifBlank { template?.imageUrl.orEmpty() }
                val displayName = item.name.ifBlank { template?.name ?: item.templateId }
                val displayDescription = item.description.ifBlank { template?.description.orEmpty() }

                Column {
                    if (displayImageUrl.isNotBlank()) {
                        AsyncImage(
                            model = displayImageUrl,
                            contentDescription = displayName,
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
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (displayDescription.isNotBlank()) {
                        Text(
                            text = displayDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = itemTypeLabel(item.itemType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (item.itemType == "bundle") {
                        FilledTonalButton(
                            onClick = { onConsume(item.id) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Text("Consume")
                        }
                    }
                }
            }
        }
    }
}

private fun itemTypeLabel(type: String): String = when (type) {
    "prop" -> "Item"
    "sticker" -> "Sticker"
    "droneskin" -> "Drone Skin"
    "emoji" -> "Emoji"
    "bundle" -> "Bundle"
    else -> type.replaceFirstChar { it.uppercase() }
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
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}
