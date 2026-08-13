package com.vrcx.android.ui.screen.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.isLoaded
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    @ApplicationContext private val context: Context,
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

    private fun currentUserId(): String? =
        (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id

    fun refresh() = loadTab(_uiState.value.selectedTab, forceRefresh = true)

    fun retry() = refresh()

    fun selectTab(tab: GalleryTab) {
        loadTab(tab)
        _uiState.update { it.copy(selectedTab = tab) }
    }

    private fun loadTab(tab: GalleryTab, forceRefresh: Boolean = false) {
        val current = _uiState.value.tabs.getValue(tab)
        if (current.isBusy || (!forceRefresh && current.isLoaded)) return

        updateTab(tab) { it.startLoad() }
        viewModelScope.launch {
            try {
                reloadTab(tab)
                updateTab(tab) { it.completeLoad(Unit) }
            } catch (e: CancellationException) {
                // Covers AccountChangedException, which GalleryRepository raises
                // when the signed-in account changes mid-load: a deliberate
                // staleness signal, not something to show the user.
                throw e
            } catch (e: Exception) {
                updateTab(tab) { it.failLoad(e.message ?: "Failed to load ${tab.label.lowercase()}") }
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

    fun showFullscreen(url: String) {
        _fullscreenImageUrl.value = url
    }

    fun dismissFullscreen() {
        _fullscreenImageUrl.value = null
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun deleteFile(fileId: String, tab: GalleryTab) = mutate(
        successMessage = "Image deleted",
        failurePrefix = "Delete failed",
        action = { galleryRepository.deleteFile(fileId); true },
        refresh = { reloadTab(tab) },
    )

    fun deletePrint(printId: String) = mutate(
        successMessage = "Print deleted",
        failurePrefix = "Delete failed",
        action = { galleryRepository.deletePrint(printId); true },
        refresh = { currentUserId()?.let { galleryRepository.loadPrints(it) } },
    )

    fun uploadFile(uri: Uri, tab: GalleryTab) {
        val tag = when (tab) {
            GalleryTab.GALLERY -> "gallery"
            GalleryTab.ICONS -> "icon"
            GalleryTab.EMOJIS -> "emoji"
            GalleryTab.STICKERS -> "sticker"
            else -> return
        }
        mutate(
            successMessage = "Image uploaded",
            failurePrefix = "Upload failed",
            uploading = true,
            action = {
                val upload = prepareUpload(uri)
                if (upload != null) {
                    galleryRepository.uploadFile(tag, upload.bytes, upload.mimeType, upload.fileName)
                }
                upload != null
            },
            refresh = { reloadTab(tab) },
        )
    }

    fun uploadPrint(uri: Uri, note: String?) = mutate(
        successMessage = "Print uploaded",
        failurePrefix = "Upload failed",
        uploading = true,
        action = {
            val upload = prepareUpload(uri)
            if (upload != null) {
                galleryRepository.uploadPrint(
                    upload.bytes,
                    note?.ifBlank { null },
                    upload.mimeType,
                    upload.fileName,
                )
            }
            upload != null
        },
        refresh = {
            val uid = currentUserId()
            if (uid == null) {
                _snackbarMessage.value = "Print uploaded, but refresh requires signing in again"
            } else {
                galleryRepository.loadPrints(uid)
            }
        },
    )

    /**
     * Runs one gallery mutation and then reloads what it changed.
     *
     * The two failures are kept apart on purpose: a refresh that fails after the
     * server already accepted the action is never announced as the action
     * failing, or the user re-taps a delete that actually succeeded. [action]
     * returns false to abandon the mutation quietly, which is how the upload
     * paths bow out after reporting an unusable image.
     */
    private fun mutate(
        successMessage: String,
        failurePrefix: String,
        uploading: Boolean = false,
        action: suspend () -> Boolean,
        refresh: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            if (uploading) _isUploading.value = true
            try {
                if (!action()) return@launch
                _snackbarMessage.value = successMessage
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    _snackbarMessage.value = "$successMessage, but refresh failed: ${error.message}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = "$failurePrefix: ${e.message}"
            } finally {
                if (uploading) _isUploading.value = false
            }
        }
    }

    private suspend fun prepareUpload(uri: Uri): PreparedUpload? =
        when (val result = prepareGalleryUpload(context, uri)) {
            is UploadReadResult.Success -> result.upload
            UploadReadResult.TooLarge -> {
                _snackbarMessage.value = "Image too large (max 10 MB)"
                null
            }
            UploadReadResult.Unreadable -> {
                _snackbarMessage.value = "Unable to open or read this image"
                null
            }
        }

    private fun setImage(successMessage: String, call: suspend (uid: String) -> Unit) {
        viewModelScope.launch {
            try {
                val uid = currentUserId() ?: return@launch
                call(uid)
                _snackbarMessage.value = successMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun setProfilePic(fileId: String) =
        setImage("Profile picture updated") { galleryRepository.setProfilePicOverride(it, fileId) }

    fun clearProfilePic() =
        setImage("Profile picture cleared") { galleryRepository.setProfilePicOverride(it, "") }

    fun setUserIcon(fileId: String) =
        setImage("User icon updated") { galleryRepository.setUserIcon(it, fileId) }

    fun clearUserIcon() =
        setImage("User icon cleared") { galleryRepository.setUserIcon(it, "") }

    fun consumeBundle(itemId: String) = mutate(
        successMessage = "Bundle consumed",
        failurePrefix = "Failed",
        action = { galleryRepository.consumeBundle(itemId); true },
        refresh = { galleryRepository.loadInventory() },
    )

    private suspend fun reloadTab(tab: GalleryTab) {
        when (tab) {
            GalleryTab.GALLERY -> galleryRepository.loadGallery()
            GalleryTab.ICONS -> galleryRepository.loadIcons()
            GalleryTab.EMOJIS -> galleryRepository.loadEmojis()
            GalleryTab.STICKERS -> galleryRepository.loadStickers()
            GalleryTab.PRINTS -> {
                val uid = currentUserId() ?: error("Not logged in")
                galleryRepository.loadPrints(uid)
            }
            GalleryTab.INVENTORY -> galleryRepository.loadInventory()
        }
    }
}
