package com.vrcx.android.ui.screen.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class GalleryTab { GALLERY, ICONS, EMOJIS, STICKERS, PRINTS, INVENTORY }

private const val MAX_UPLOAD_SIZE = 10 * 1024 * 1024 // 10 MB

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val galleryRepository: GalleryRepository,
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(GalleryTab.GALLERY)
    val selectedTab: StateFlow<GalleryTab> = _selectedTab.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _fullscreenImageUrl = MutableStateFlow<String?>(null)
    val fullscreenImageUrl: StateFlow<String?> = _fullscreenImageUrl.asStateFlow()

    val galleryImages: StateFlow<List<GalleryImage>> = galleryRepository.galleryImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val iconImages: StateFlow<List<GalleryImage>> = galleryRepository.iconImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emojiImages: StateFlow<List<GalleryImage>> = galleryRepository.emojiImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stickerImages: StateFlow<List<GalleryImage>> = galleryRepository.stickerImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val prints: StateFlow<List<VrcPrint>> = galleryRepository.prints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryItems: StateFlow<List<InventoryItem>> = galleryRepository.inventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryTemplates: StateFlow<List<InventoryTemplate>> = galleryRepository.inventoryTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadAll()
    }

    private fun currentUserId(): String? =
        (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id

    private fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val uid = currentUserId()
                if (uid == null) {
                    _error.value = "Not logged in"
                    return@launch
                }
                galleryRepository.loadAll(uid)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load gallery"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                val uid = currentUserId()
                if (uid == null) {
                    _error.value = "Not logged in"
                    return@launch
                }
                galleryRepository.loadAll(uid)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun selectTab(tab: GalleryTab) {
        _selectedTab.value = tab
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

    fun deleteFile(fileId: String, tab: GalleryTab) {
        viewModelScope.launch {
            try {
                galleryRepository.deleteFile(fileId)
                reloadTab(tab)
                _snackbarMessage.value = "Image deleted"
            } catch (e: Exception) {
                _snackbarMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun deletePrint(printId: String) {
        viewModelScope.launch {
            try {
                galleryRepository.deletePrint(printId)
                val uid = currentUserId() ?: return@launch
                galleryRepository.loadPrints(uid)
                _snackbarMessage.value = "Print deleted"
            } catch (e: Exception) {
                _snackbarMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun uploadFile(uri: Uri, tab: GalleryTab) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                val fileName = resolveFileName(uri, mimeType)
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                if (bytes.size > MAX_UPLOAD_SIZE) {
                    _snackbarMessage.value = "Image too large (max 10 MB)"
                    return@launch
                }
                val tag = when (tab) {
                    GalleryTab.GALLERY -> "gallery"
                    GalleryTab.ICONS -> "icon"
                    GalleryTab.EMOJIS -> "emoji"
                    GalleryTab.STICKERS -> "sticker"
                    else -> return@launch
                }
                galleryRepository.uploadFile(tag, bytes, mimeType, fileName)
                reloadTab(tab)
                _snackbarMessage.value = "Image uploaded"
            } catch (e: Exception) {
                _snackbarMessage.value = "Upload failed: ${e.message}"
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun uploadPrint(uri: Uri, note: String?) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                val fileName = resolveFileName(uri, mimeType)
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                if (bytes.size > MAX_UPLOAD_SIZE) {
                    _snackbarMessage.value = "Image too large (max 10 MB)"
                    return@launch
                }
                galleryRepository.uploadPrint(bytes, note?.ifBlank { null }, mimeType, fileName)
                val uid = currentUserId() ?: return@launch
                galleryRepository.loadPrints(uid)
                _snackbarMessage.value = "Print uploaded"
            } catch (e: Exception) {
                _snackbarMessage.value = "Upload failed: ${e.message}"
            } finally {
                _isUploading.value = false
            }
        }
    }

    /**
     * Pulls the original file name from the URI's OpenableColumns when the
     * source provides it (gallery picker does), so the multipart upload reports
     * the user's actual file name. Falls back to a MIME-appropriate default
     * name so the extension matches the bytes regardless.
     */
    private fun resolveFileName(uri: Uri, mimeType: String): String {
        val pickerName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
        return pickerName?.takeIf { it.isNotBlank() }
            ?: GalleryRepository.defaultFileNameFor(mimeType)
    }

    fun setProfilePic(fileId: String) {
        viewModelScope.launch {
            try {
                val uid = currentUserId() ?: return@launch
                galleryRepository.setProfilePicOverride(uid, fileId)
                _snackbarMessage.value = "Profile picture updated"
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearProfilePic() {
        viewModelScope.launch {
            try {
                val uid = currentUserId() ?: return@launch
                galleryRepository.setProfilePicOverride(uid, "")
                _snackbarMessage.value = "Profile picture cleared"
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun setUserIcon(fileId: String) {
        viewModelScope.launch {
            try {
                val uid = currentUserId() ?: return@launch
                galleryRepository.setUserIcon(uid, fileId)
                _snackbarMessage.value = "User icon updated"
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearUserIcon() {
        viewModelScope.launch {
            try {
                val uid = currentUserId() ?: return@launch
                galleryRepository.setUserIcon(uid, "")
                _snackbarMessage.value = "User icon cleared"
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun consumeBundle(itemId: String) {
        viewModelScope.launch {
            try {
                galleryRepository.consumeBundle(itemId)
                galleryRepository.loadInventory()
                _snackbarMessage.value = "Bundle consumed"
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed: ${e.message}"
            }
        }
    }

    private suspend fun reloadTab(tab: GalleryTab) {
        when (tab) {
            GalleryTab.GALLERY -> galleryRepository.loadGallery()
            GalleryTab.ICONS -> galleryRepository.loadIcons()
            GalleryTab.EMOJIS -> galleryRepository.loadEmojis()
            GalleryTab.STICKERS -> galleryRepository.loadStickers()
            GalleryTab.PRINTS -> {
                val uid = currentUserId() ?: return
                galleryRepository.loadPrints(uid)
            }
            GalleryTab.INVENTORY -> galleryRepository.loadInventory()
        }
    }
}
