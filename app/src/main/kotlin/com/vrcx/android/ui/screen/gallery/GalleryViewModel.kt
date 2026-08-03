package com.vrcx.android.ui.screen.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import com.vrcx.android.data.util.UploadBytesResult
import com.vrcx.android.data.util.readUploadBytesBounded
import java.io.ByteArrayOutputStream
import javax.inject.Inject

enum class GalleryTab(val label: String) {
    GALLERY("Gallery"),
    ICONS("Icons"),
    EMOJIS("Emojis"),
    STICKERS("Stickers"),
    PRINTS("Prints"),
    INVENTORY("Inventory"),
}

data class GalleryTabState(
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class GalleryUiState(
    val selectedTab: GalleryTab = GalleryTab.GALLERY,
    val tabs: Map<GalleryTab, GalleryTabState> = GalleryTab.entries.associateWith { GalleryTabState() },
) {
    val selectedTabState: GalleryTabState get() = tabs.getValue(selectedTab)
}

internal const val MAX_UPLOAD_DIMENSION = 2_000

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
        if (current.isLoading || current.isRefreshing || (!forceRefresh && current.isLoaded)) return

        updateTab(tab) { state ->
            state.copy(
                isLoading = !state.isLoaded,
                isRefreshing = state.isLoaded,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                reloadTab(tab)
                updateTab(tab) {
                    it.copy(
                        isLoaded = true,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTab(tab) {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message ?: "Failed to load ${tab.label.lowercase()}",
                    )
                }
            }
        }
    }

    private fun updateTab(tab: GalleryTab, transform: (GalleryTabState) -> GalleryTabState) {
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

    fun deleteFile(fileId: String, tab: GalleryTab) {
        viewModelScope.launch {
            try {
                galleryRepository.deleteFile(fileId)
                reloadTab(tab)
                _snackbarMessage.value = "Image deleted"
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun uploadFile(uri: Uri, tab: GalleryTab) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val tag = when (tab) {
                    GalleryTab.GALLERY -> "gallery"
                    GalleryTab.ICONS -> "icon"
                    GalleryTab.EMOJIS -> "emoji"
                    GalleryTab.STICKERS -> "sticker"
                    else -> return@launch
                }
                val sourceMimeType = context.contentResolver.getType(uri) ?: "image/png"
                val sourceFileName = resolveFileName(uri, sourceMimeType)
                val upload = prepareUpload(uri, sourceMimeType, sourceFileName) ?: return@launch
                galleryRepository.uploadFile(tag, upload.bytes, upload.mimeType, upload.fileName)
                _snackbarMessage.value = "Image uploaded"
                try {
                    reloadTab(tab)
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    _snackbarMessage.value = "Image uploaded, but refresh failed: ${error.message}"
                }
            } catch (e: CancellationException) {
                throw e
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
                val sourceMimeType = context.contentResolver.getType(uri) ?: "image/png"
                val sourceFileName = resolveFileName(uri, sourceMimeType)
                val upload = prepareUpload(uri, sourceMimeType, sourceFileName) ?: return@launch
                galleryRepository.uploadPrint(
                    upload.bytes,
                    note?.ifBlank { null },
                    upload.mimeType,
                    upload.fileName,
                )
                _snackbarMessage.value = "Print uploaded"
                val uid = currentUserId()
                if (uid == null) {
                    _snackbarMessage.value = "Print uploaded, but refresh requires signing in again"
                } else {
                    try {
                        galleryRepository.loadPrints(uid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (error: Exception) {
                        _snackbarMessage.value = "Print uploaded, but refresh failed: ${error.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

    private suspend fun prepareUpload(uri: Uri, mimeType: String, fileName: String): PreparedUpload? {
        return when (
            val result = withContext(Dispatchers.IO) {
                prepareUploadResult(uri, mimeType, fileName)
            }
        ) {
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
    }

    private fun prepareUploadResult(uri: Uri, mimeType: String, fileName: String): UploadReadResult {
        val metadataSize = resolveFileSize(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsDecoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } != null || (bounds.outWidth > 0 && bounds.outHeight > 0)
        }.getOrDefault(false)
        val hasDimensions = boundsDecoded || (bounds.outWidth > 0 && bounds.outHeight > 0)
        val needsResize = hasDimensions && (
            bounds.outWidth > MAX_UPLOAD_DIMENSION ||
                bounds.outHeight > MAX_UPLOAD_DIMENSION ||
                (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES)
            )

        if (needsResize) {
            return resizeUpload(uri, mimeType, fileName, bounds.outWidth, bounds.outHeight)
        }
        if (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES) {
            return UploadReadResult.TooLarge
        }

        return when (val read = readUploadBytesBounded(context.contentResolver.openInputStream(uri))) {
            UploadBytesResult.Unreadable -> UploadReadResult.Unreadable
            UploadBytesResult.TooLarge -> UploadReadResult.TooLarge
            is UploadBytesResult.Success -> UploadReadResult.Success(PreparedUpload(read.bytes, mimeType, fileName))
        }
    }

    private fun resizeUpload(
        uri: Uri,
        sourceMimeType: String,
        sourceFileName: String,
        width: Int,
        height: Int,
    ): UploadReadResult {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateUploadSampleSize(width, height)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return UploadReadResult.Unreadable
        val target = scaleBitmapToFit(decoded)
        val outputMimeType = when (sourceMimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/webp" -> "image/webp"
            else -> "image/png"
        }
        val extension = when (outputMimeType) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val outputName = sourceFileName.substringBeforeLast('.', sourceFileName) + ".$extension"
        val bytes = compressBitmapBounded(target, outputMimeType)
        if (target !== decoded) target.recycle()
        decoded.recycle()
        return bytes?.let {
            UploadReadResult.Success(PreparedUpload(it, outputMimeType, outputName))
        } ?: UploadReadResult.TooLarge
    }

    private fun scaleBitmapToFit(bitmap: Bitmap): Bitmap {
        val (targetWidth, targetHeight) = fitUploadDimensions(bitmap.width, bitmap.height)
        return if (targetWidth == bitmap.width && targetHeight == bitmap.height) bitmap else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }

    private fun compressBitmapBounded(bitmap: Bitmap, mimeType: String): ByteArray? {
        val format = when (mimeType) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        val qualities = if (format == Bitmap.CompressFormat.PNG) listOf(100) else listOf(92, 84, 76, 68, 60)
        for (quality in qualities) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(format, quality, output) && output.size() <= MAX_UPLOAD_SIZE_BYTES) {
                return output.toByteArray()
            }
        }
        return null
    }

    private fun resolveFileSize(uri: Uri): Long? {
        val querySize = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx).takeIf { it >= 0 } else null
                } else null
            }
        }.getOrNull()

        if (querySize != null) return querySize

        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0 }
            }
        }.getOrNull()
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

    fun consumeBundle(itemId: String) {
        viewModelScope.launch {
            try {
                galleryRepository.consumeBundle(itemId)
                galleryRepository.loadInventory()
                _snackbarMessage.value = "Bundle consumed"
            } catch (e: CancellationException) {
                throw e
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
                val uid = currentUserId() ?: error("Not logged in")
                galleryRepository.loadPrints(uid)
            }
            GalleryTab.INVENTORY -> galleryRepository.loadInventory()
        }
    }
}

private data class PreparedUpload(val bytes: ByteArray, val mimeType: String, val fileName: String)

private sealed interface UploadReadResult {
    data class Success(val upload: PreparedUpload) : UploadReadResult
    data object TooLarge : UploadReadResult
    data object Unreadable : UploadReadResult
}

internal fun calculateUploadSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int = MAX_UPLOAD_DIMENSION,
): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) > maxDimension || height / (sampleSize * 2) > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun fitUploadDimensions(
    width: Int,
    height: Int,
    maxDimension: Int = MAX_UPLOAD_DIMENSION,
): Pair<Int, Int> {
    if (width <= maxDimension && height <= maxDimension) return width to height
    val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
