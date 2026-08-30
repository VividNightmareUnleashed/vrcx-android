package com.vrcx.android.ui.screen.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun GalleryScreen(viewModel: GalleryViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    var confirmation by remember { mutableStateOf<GalleryConfirmation?>(null) }
    var isOverflowMenuExpanded by remember { mutableStateOf(false) }
    val state = galleryRouteState(viewModel, isOverflowMenuExpanded)
    val snackbarHostState = remember { SnackbarHostState() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.uploadImage(it, state.content.selectedTab) }
    }
    GallerySnackbarEffect(
        message = state.snackbarMessage,
        snackbarHostState = snackbarHostState,
        onShown = { viewModel.handle(GalleryCommand.ClearSnackbar) },
    )

    val actionHandler = GalleryActionHandler(
        viewModel = viewModel,
        onBack = onBack,
        environment = GalleryActionEnvironment(
            launchUpload = { imagePicker.launch("image/*") },
            isUploading = { state.content.isUploading },
            confirmation = { confirmation },
            updateConfirmation = { confirmation = it },
            updateOverflowMenu = { isOverflowMenuExpanded = it },
        ),
    )
    GalleryContent(
        state = state.content,
        snackbarHostState = snackbarHostState,
        onAction = actionHandler::handle,
    )
    GalleryDialogs(
        state = GalleryDialogsState(state.fullscreenImageUrl, confirmation),
        onAction = actionHandler::handle,
    )
}

private fun GalleryViewModel.uploadImage(uri: Uri, selectedTab: GalleryTab) {
    if (selectedTab == GalleryTab.PRINTS) {
        handle(GalleryCommand.UploadPrint(uri, null))
    } else {
        handle(GalleryCommand.UploadFile(uri, selectedTab))
    }
}

@Composable
private fun GallerySnackbarEffect(message: String?, snackbarHostState: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            onShown()
        }
    }
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
