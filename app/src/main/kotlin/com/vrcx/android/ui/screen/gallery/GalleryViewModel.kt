package com.vrcx.android.ui.screen.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.gallery.GalleryImageCategory
import com.vrcx.android.data.gallery.GalleryUploadCoordinator
import com.vrcx.android.data.gallery.GalleryUploadResult
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.isLoaded
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GalleryTab(val label: String) {
    GALLERY("Gallery"),
    ICONS("Icons"),
    EMOJIS("Emojis"),
    STICKERS("Stickers"),
    PRINTS("Prints"),
    INVENTORY("Inventory"),
}

data class GalleryUiState(
    val selectedTab: GalleryTab = GalleryTab.GALLERY,
    /** Each tab's rows live in [GalleryRepository], so the state carries no value. */
    val tabs: Map<GalleryTab, LoadState<Unit>> =
        GalleryTab.entries.associateWith { LoadState.NotLoaded },
) {
    val selectedTabState: LoadState<Unit> get() = tabs.getValue(selectedTab)
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val galleryRepository: GalleryRepository,
    private val authRepository: AuthRepository,
    private val uploadCoordinator: GalleryUploadCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _fullscreenImageUrl = MutableStateFlow<String?>(null)
    val fullscreenImageUrl: StateFlow<String?> = _fullscreenImageUrl.asStateFlow()

    val galleryImages: StateFlow<List<GalleryImage>> = galleryRepository.galleryImages
    val iconImages: StateFlow<List<GalleryImage>> = galleryRepository.iconImages
    val emojiImages: StateFlow<List<GalleryImage>> = galleryRepository.emojiImages
    val stickerImages: StateFlow<List<GalleryImage>> = galleryRepository.stickerImages
    val prints: StateFlow<List<VrcPrint>> = galleryRepository.prints
    val inventoryItems: StateFlow<List<InventoryItem>> = galleryRepository.inventoryItems
    val inventoryTemplates: StateFlow<List<InventoryTemplate>> = galleryRepository.inventoryTemplates

    init {
        loadTab(GalleryTab.GALLERY)
    }

    private val currentUserId: String?
        get() = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id

    internal fun handle(command: GalleryCommand) {
        when (command) {
            is GalleryCommand.State -> handleState(command)
            is GalleryCommand.Mutation -> handleMutation(command)
            is GalleryCommand.Upload -> handleUpload(command)
        }
    }

    private fun handleState(command: GalleryCommand.State) {
        when (command) {
            GalleryCommand.Refresh -> loadTab(_uiState.value.selectedTab, forceRefresh = true)

            is GalleryCommand.SelectTab -> {
                loadTab(command.tab)
                _uiState.update { it.copy(selectedTab = command.tab) }
            }

            GalleryCommand.ClearSnackbar -> _snackbarMessage.value = null

            is GalleryCommand.ShowFullscreen -> _fullscreenImageUrl.value = command.imageUrl

            GalleryCommand.DismissFullscreen -> _fullscreenImageUrl.value = null
        }
    }

    private fun handleMutation(command: GalleryCommand.Mutation) {
        when (command) {
            is GalleryCommand.DeleteFile -> mutate(
                successMessage = "Image deleted",
                failurePrefix = "Delete failed",
                action = { galleryRepository.deleteFile(command.fileId) },
                refresh = { reloadTab(command.tab) },
            )

            is GalleryCommand.DeletePrint -> mutate(
                successMessage = "Print deleted",
                failurePrefix = "Delete failed",
                action = { galleryRepository.deletePrint(command.printId) },
                refresh = { currentUserId?.let { galleryRepository.loadPrints(it) } },
            )

            is GalleryCommand.SetProfilePicture -> setImage("Profile picture updated") {
                galleryRepository.setProfilePicOverride(it, command.fileId)
            }

            GalleryCommand.ClearProfilePicture -> setImage("Profile picture cleared") {
                galleryRepository.setProfilePicOverride(it, "")
            }

            is GalleryCommand.SetUserIcon -> setImage("User icon updated") {
                galleryRepository.setUserIcon(it, command.fileId)
            }

            GalleryCommand.ClearUserIcon -> setImage("User icon cleared") {
                galleryRepository.setUserIcon(it, "")
            }

            is GalleryCommand.ConsumeBundle -> mutate(
                successMessage = "Bundle consumed",
                failurePrefix = "Failed",
                action = { galleryRepository.consumeBundle(command.itemId) },
                refresh = { galleryRepository.loadInventory() },
            )
        }
    }

    private fun handleUpload(command: GalleryCommand.Upload) {
        when (command) {
            is GalleryCommand.UploadFile -> {
                val category = when (command.tab) {
                    GalleryTab.GALLERY -> GalleryImageCategory.GALLERY
                    GalleryTab.ICONS -> GalleryImageCategory.ICON
                    GalleryTab.EMOJIS -> GalleryImageCategory.EMOJI
                    GalleryTab.STICKERS -> GalleryImageCategory.STICKER
                    else -> return
                }
                upload(
                    successMessage = "Image uploaded",
                    failurePrefix = "Upload failed",
                    action = { uploadCoordinator.uploadImage(command.uri, category) },
                )
            }

            is GalleryCommand.UploadPrint -> upload(
                successMessage = "Print uploaded",
                failurePrefix = "Upload failed",
                action = { uploadCoordinator.uploadPrint(command.uri, command.note) },
            )
        }
    }

    private fun loadTab(tab: GalleryTab, forceRefresh: Boolean = false) {
        val current = _uiState.value.tabs.getValue(tab)
        if (current.isBusy || (!forceRefresh && current.isLoaded)) return

        updateTab(tab) { it.startLoad() }
        viewModelScope.launch {
            try {
                runCatchingCancellable { reloadTab(tab) }
                    .fold(
                        onSuccess = { updateTab(tab) { state -> state.completeLoad(Unit) } },
                        onFailure = { failure ->
                            updateTab(tab) { state ->
                                state.failLoad(
                                    failure.message ?: "Failed to load ${tab.label.lowercase()}",
                                )
                            }
                        },
                    )
            } finally {
                // Runs on cancellation too — otherwise the tab stays busy and
                // this method's own guard blocks every later retry.
                updateTab(tab) { it.settleLoad() }
            }
        }
    }

    private fun updateTab(tab: GalleryTab, transform: (LoadState<Unit>) -> LoadState<Unit>) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs + (tab to transform(state.tabs.getValue(tab))))
        }
    }

    private fun mutate(
        successMessage: String,
        failurePrefix: String,
        action: suspend () -> Unit,
        refresh: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            val actionFailure = runCatchingCancellable { action() }.exceptionOrNull()
            if (actionFailure == null) {
                _snackbarMessage.value = successMessage
                runCatchingCancellable { refresh() }.exceptionOrNull()?.let { refreshFailure ->
                    _snackbarMessage.value =
                        "$successMessage, but refresh failed: ${refreshFailure.message}"
                }
            } else {
                _snackbarMessage.value = "$failurePrefix: ${actionFailure.message}"
            }
        }
    }

    private fun upload(successMessage: String, failurePrefix: String, action: suspend () -> GalleryUploadResult) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                runCatchingCancellable { action() }
                    .fold(
                        onSuccess = { result ->
                            val message = when (result) {
                                GalleryUploadResult.Uploaded -> successMessage

                                GalleryUploadResult.TooLarge -> "Image too large (max 10 MB)"

                                GalleryUploadResult.Unreadable -> "Unable to open or read this image"

                                GalleryUploadResult.Obsolete -> null

                                GalleryUploadResult.RefreshRequiresAuthentication ->
                                    "$successMessage, but refresh requires signing in again"

                                is GalleryUploadResult.RefreshFailed ->
                                    "$successMessage, but refresh failed: ${result.error.message}"
                            }
                            message?.let { _snackbarMessage.value = it }
                        },
                        onFailure = { failure ->
                            _snackbarMessage.value = "$failurePrefix: ${failure.message}"
                        },
                    )
            } finally {
                _isUploading.value = false
            }
        }
    }

    private fun setImage(successMessage: String, call: suspend (uid: String) -> Unit) {
        viewModelScope.launch {
            val uid = currentUserId ?: return@launch
            runCatchingCancellable { call(uid) }
                .fold(
                    onSuccess = { _snackbarMessage.value = successMessage },
                    onFailure = { failure ->
                        _snackbarMessage.value = "Failed: ${failure.message}"
                    },
                )
        }
    }

    private suspend fun reloadTab(tab: GalleryTab) {
        when (tab) {
            GalleryTab.GALLERY -> galleryRepository.loadGallery()

            GalleryTab.ICONS -> galleryRepository.loadIcons()

            GalleryTab.EMOJIS -> galleryRepository.loadEmojis()

            GalleryTab.STICKERS -> galleryRepository.loadStickers()

            GalleryTab.PRINTS -> {
                val uid = currentUserId ?: error("Not logged in")
                galleryRepository.loadPrints(uid)
            }

            GalleryTab.INVENTORY -> galleryRepository.loadInventory()
        }
    }
}
