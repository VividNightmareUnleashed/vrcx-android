package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryRepository @Inject constructor(
    private val galleryApi: GalleryApi,
    private val inventoryApi: InventoryApi,
    private val userApi: UserApi,
) {
    private val _galleryImages = MutableStateFlow<List<GalleryImage>>(emptyList())
    val galleryImages: StateFlow<List<GalleryImage>> = _galleryImages.asStateFlow()

    private val _iconImages = MutableStateFlow<List<GalleryImage>>(emptyList())
    val iconImages: StateFlow<List<GalleryImage>> = _iconImages.asStateFlow()

    private val _emojiImages = MutableStateFlow<List<GalleryImage>>(emptyList())
    val emojiImages: StateFlow<List<GalleryImage>> = _emojiImages.asStateFlow()

    private val _stickerImages = MutableStateFlow<List<GalleryImage>>(emptyList())
    val stickerImages: StateFlow<List<GalleryImage>> = _stickerImages.asStateFlow()

    private val _prints = MutableStateFlow<List<VrcPrint>>(emptyList())
    val prints: StateFlow<List<VrcPrint>> = _prints.asStateFlow()

    private val _inventoryItems = MutableStateFlow<List<InventoryItem>>(emptyList())
    val inventoryItems: StateFlow<List<InventoryItem>> = _inventoryItems.asStateFlow()

    private val _inventoryTemplates = MutableStateFlow<List<InventoryTemplate>>(emptyList())
    val inventoryTemplates: StateFlow<List<InventoryTemplate>> = _inventoryTemplates.asStateFlow()

    suspend fun loadGallery() {
        _galleryImages.value = galleryApi.getFileList(tag = "gallery").reversed()
    }

    suspend fun loadIcons() {
        _iconImages.value = galleryApi.getFileList(tag = "icon").reversed()
    }

    suspend fun loadEmojis() {
        _emojiImages.value = galleryApi.getFileList(tag = "emoji").reversed()
    }

    suspend fun loadStickers() {
        _stickerImages.value = galleryApi.getFileList(tag = "sticker").reversed()
    }

    suspend fun loadPrints(userId: String) {
        _prints.value = galleryApi.getPrints(userId)
    }

    suspend fun loadInventory() {
        _inventoryItems.value = inventoryApi.getInventoryItems()
        _inventoryTemplates.value = inventoryApi.getInventoryTemplates()
    }

    suspend fun loadAll(userId: String) {
        coroutineScope {
            launch { runCatching { loadGallery() } }
            launch { runCatching { loadIcons() } }
            launch { runCatching { loadEmojis() } }
            launch { runCatching { loadStickers() } }
            launch { runCatching { loadPrints(userId) } }
            launch { runCatching { loadInventory() } }
        }
    }

    suspend fun deleteFile(fileId: String) {
        galleryApi.deleteFile(fileId)
    }

    suspend fun deletePrint(printId: String) {
        galleryApi.deletePrint(printId)
    }

    suspend fun uploadFile(tag: String, imageBytes: ByteArray, mimeType: String = "image/png"): GalleryImage {
        val tagBody = tag.toRequestBody("text/plain".toMediaType())
        val imagePart = MultipartBody.Part.createFormData(
            "file", "blob", imageBytes.toRequestBody(mimeType.toMediaType())
        )
        return galleryApi.uploadFile(tagBody, imagePart)
    }

    suspend fun uploadPrint(imageBytes: ByteArray, note: String?, mimeType: String = "image/png"): VrcPrint {
        val imagePart = MultipartBody.Part.createFormData(
            "image", "image.png", imageBytes.toRequestBody(mimeType.toMediaType())
        )
        val noteBody = note?.toRequestBody("text/plain".toMediaType())
        return galleryApi.uploadPrint(imagePart, noteBody)
    }

    suspend fun setProfilePicOverride(userId: String, fileId: String) {
        val url = if (fileId.isNotEmpty()) "https://api.vrchat.cloud/api/1/file/$fileId/1" else ""
        userApi.saveCurrentUser(userId, mapOf("profilePicOverride" to url))
    }

    suspend fun setUserIcon(userId: String, fileId: String) {
        val url = if (fileId.isNotEmpty()) "https://api.vrchat.cloud/api/1/file/$fileId/1" else ""
        userApi.saveCurrentUser(userId, mapOf("userIcon" to url))
    }

    suspend fun consumeBundle(itemId: String) {
        inventoryApi.consumeInventoryBundle(itemId)
    }
}
