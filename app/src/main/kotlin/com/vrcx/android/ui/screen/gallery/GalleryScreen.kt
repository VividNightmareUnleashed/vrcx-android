package com.vrcx.android.ui.screen.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.api.model.imageUrl
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxDetailTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val fullscreenImageUrl by viewModel.fullscreenImageUrl.collectAsState()

    val galleryImages by viewModel.galleryImages.collectAsState()
    val iconImages by viewModel.iconImages.collectAsState()
    val emojiImages by viewModel.emojiImages.collectAsState()
    val stickerImages by viewModel.stickerImages.collectAsState()
    val prints by viewModel.prints.collectAsState()
    val inventoryItems by viewModel.inventoryItems.collectAsState()
    val inventoryTemplates by viewModel.inventoryTemplates.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var deleteFileTarget by remember { mutableStateOf<Pair<String, GalleryTab>?>(null) }
    var deletePrintTarget by remember { mutableStateOf<String?>(null) }
    var consumeTarget by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (selectedTab == GalleryTab.PRINTS) {
            viewModel.uploadPrint(uri, null)
        } else {
            viewModel.uploadFile(uri, selectedTab)
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            viewModel.clearSnackbar()
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedTab != GalleryTab.INVENTORY && !isLoading) {
                FloatingActionButton(
                    onClick = { if (!isUploading) imagePicker.launch("image/*") },
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "Upload")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            VrcxDetailTopBar(
                title = "Gallery",
                onBack = onBack,
                actions = {
                    if (selectedTab == GalleryTab.GALLERY || selectedTab == GalleryTab.ICONS) {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            if (selectedTab == GalleryTab.GALLERY) {
                                DropdownMenuItem(
                                    text = { Text("Clear Profile Picture") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.clearProfilePic()
                                    },
                                )
                            }
                            if (selectedTab == GalleryTab.ICONS) {
                                DropdownMenuItem(
                                    text = { Text("Clear User Icon") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.clearUserIcon()
                                    },
                                )
                            }
                        }
                    }
                },
            )

            val tabLabels = listOf(
                "Gallery" to galleryImages.size,
                "Icons" to iconImages.size,
                "Emojis" to emojiImages.size,
                "Stickers" to stickerImages.size,
                "Prints" to prints.size,
                "Inventory" to inventoryItems.size,
            )

            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 16.dp,
            ) {
                GalleryTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            val (label, count) = tabLabels[index]
                            Text(if (count > 0) "$label ($count)" else label)
                        },
                    )
                }
            }

            when {
                isLoading -> LoadingState()
                error != null -> ErrorState(message = error!!, onRetry = viewModel::refresh)
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (selectedTab) {
                            GalleryTab.GALLERY -> ImageGridContent(
                                images = galleryImages,
                                emptyMessage = "No gallery images",
                                onImageClick = { viewModel.showFullscreen(it) },
                                onDeleteClick = { deleteFileTarget = it to GalleryTab.GALLERY },
                                onSetClick = { viewModel.setProfilePic(it) },
                                setLabel = "Set as Profile Pic",
                            )
                            GalleryTab.ICONS -> ImageGridContent(
                                images = iconImages,
                                emptyMessage = "No icons",
                                onImageClick = { viewModel.showFullscreen(it) },
                                onDeleteClick = { deleteFileTarget = it to GalleryTab.ICONS },
                                onSetClick = { viewModel.setUserIcon(it) },
                                setLabel = "Set as User Icon",
                            )
                            GalleryTab.EMOJIS -> ImageGridContent(
                                images = emojiImages,
                                emptyMessage = "No emojis",
                                onImageClick = { viewModel.showFullscreen(it) },
                                onDeleteClick = { deleteFileTarget = it to GalleryTab.EMOJIS },
                            )
                            GalleryTab.STICKERS -> ImageGridContent(
                                images = stickerImages,
                                emptyMessage = "No stickers",
                                onImageClick = { viewModel.showFullscreen(it) },
                                onDeleteClick = { deleteFileTarget = it to GalleryTab.STICKERS },
                            )
                            GalleryTab.PRINTS -> PrintGridContent(
                                prints = prints,
                                onPrintClick = { viewModel.showFullscreen(it) },
                                onDeleteClick = { deletePrintTarget = it },
                            )
                            GalleryTab.INVENTORY -> InventoryGridContent(
                                items = inventoryItems,
                                templates = inventoryTemplates,
                                onConsume = { consumeTarget = it },
                            )
                        }
                    }
                }
            }
        }
    }

    // Fullscreen image dialog
    fullscreenImageUrl?.let { url ->
        FullscreenImageDialog(
            imageUrl = url,
            onDismiss = { viewModel.dismissFullscreen() },
        )
    }

    // Confirm delete file dialog
    deleteFileTarget?.let { (fileId, tab) ->
        ConfirmDialog(
            title = "Delete Image",
            message = "Are you sure you want to delete this image?",
            onConfirm = {
                viewModel.deleteFile(fileId, tab)
                deleteFileTarget = null
            },
            onDismiss = { deleteFileTarget = null },
        )
    }

    // Confirm delete print dialog
    deletePrintTarget?.let { printId ->
        ConfirmDialog(
            title = "Delete Print",
            message = "Are you sure you want to delete this print?",
            onConfirm = {
                viewModel.deletePrint(printId)
                deletePrintTarget = null
            },
            onDismiss = { deletePrintTarget = null },
        )
    }

    // Confirm consume bundle dialog
    consumeTarget?.let { itemId ->
        ConfirmDialog(
            title = "Consume Bundle",
            message = "Consume this bundle? This cannot be undone.",
            onConfirm = {
                viewModel.consumeBundle(itemId)
                consumeTarget = null
            },
            onDismiss = { consumeTarget = null },
        )
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
private fun PrintGridContent(
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
                            Icon(Icons.Default.Fullscreen, contentDescription = "View full size", modifier = Modifier.size(18.dp))
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
                val template = templateMap[item.itemId]
                val displayImageUrl = item.imageUrl.ifBlank { template?.imageUrl ?: "" }
                val displayName = item.name.ifBlank { template?.name ?: item.itemId }
                val displayDescription = item.description.ifBlank { template?.description ?: "" }

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
private fun FullscreenImageDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
) {
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
                    .fillMaxSize()
                    .padding(16.dp)
                    .clickable(enabled = false, onClick = {}),
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

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Confirm", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
