package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.UpdateCurrentUserRequest
import com.vrcx.android.data.api.model.VrcPrint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val items = mutableListOf<InventoryItem>()
        var offset = 0
        var pageCount = 0

        while (pageCount < MAX_INVENTORY_PAGES) {
            val response = inventoryApi.getInventoryItems(
                n = INVENTORY_PAGE_SIZE,
                offset = offset,
            )
            if (response.data.isEmpty()) break

            items += response.data
            pageCount++
            if (response.totalCount > 0 && items.size >= response.totalCount) break
            offset += response.data.size
        }

        _inventoryItems.value = items
        val templates = mutableListOf<InventoryTemplate>()
        items.map(InventoryItem::templateId)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { templateId ->
                try {
                    templates += inventoryApi.getInventoryTemplate(templateId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        _inventoryTemplates.value = templates
    }

    suspend fun loadAll(userId: String) {
        val results = coroutineScope {
            listOf<suspend () -> Unit>(
                { loadGallery() },
                { loadIcons() },
                { loadEmojis() },
                { loadStickers() },
                { loadPrints(userId) },
                { loadInventory() },
            ).map { load -> async { runCatching { load() } } }.awaitAll()
        }
        if (results.all { it.isFailure }) {
            val first = results.firstNotNullOf { it.exceptionOrNull() }
            results.drop(1).mapNotNull { it.exceptionOrNull() }.forEach(first::addSuppressed)
            throw first
        }
    }

    fun clearRuntimeState() {
        _galleryImages.value = emptyList()
        _iconImages.value = emptyList()
        _emojiImages.value = emptyList()
        _stickerImages.value = emptyList()
        _prints.value = emptyList()
        _inventoryItems.value = emptyList()
        _inventoryTemplates.value = emptyList()
    }

    suspend fun handleContentRefresh(contentType: String, userId: String) {
        try {
            when (contentType) {
                "gallery" -> loadGallery()
                "icon" -> loadIcons()
                "emoji" -> loadEmojis()
                "sticker" -> loadStickers()
                "print", "prints" -> loadPrints(userId)
                "inventory" -> loadInventory()
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteFile(fileId: String) {
        galleryApi.deleteFile(fileId)
    }

    suspend fun deletePrint(printId: String) {
        galleryApi.deletePrint(printId)
    }

    suspend fun uploadFile(
        tag: String,
        imageBytes: ByteArray,
        mimeType: String = "image/png",
        fileName: String = defaultFileNameFor(mimeType),
    ): GalleryImage {
        val tagBody = tag.toRequestBody("text/plain".toMediaType())
        val imagePart = MultipartBody.Part.createFormData(
            "file", fileName, imageBytes.toRequestBody(mimeType.toMediaType())
        )
        return galleryApi.uploadFile(tagBody, imagePart)
    }

    suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String = "image/png",
        fileName: String = defaultFileNameFor(mimeType),
    ): VrcPrint {
        val imagePart = MultipartBody.Part.createFormData(
            "image", fileName, imageBytes.toRequestBody(mimeType.toMediaType())
        )
        val noteBody = note?.toRequestBody("text/plain".toMediaType())
        return galleryApi.uploadPrint(imagePart, noteBody)
    }

    internal companion object {
        private const val INVENTORY_PAGE_SIZE = 100
        private const val MAX_INVENTORY_PAGES = 100

        /**
         * Picks a filename whose extension matches the actual MIME type so the
         * VRChat file API doesn't reject a JPEG that the device returned but
         * the multipart called "image.png".
         */
        internal fun defaultFileNameFor(mimeType: String): String = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "image.jpg"
            "image/webp" -> "image.webp"
            "image/gif" -> "image.gif"
            else -> "image.png"
        }
    }

    suspend fun setProfilePicOverride(userId: String, fileId: String) {
        val url = if (fileId.isNotEmpty()) "https://api.vrchat.cloud/api/1/file/$fileId/1" else ""
        userApi.saveCurrentUser(userId, UpdateCurrentUserRequest(profilePicOverride = url))
    }

    suspend fun setUserIcon(userId: String, fileId: String) {
        val url = if (fileId.isNotEmpty()) "https://api.vrchat.cloud/api/1/file/$fileId/1" else ""
        userApi.saveCurrentUser(userId, UpdateCurrentUserRequest(userIcon = url))
    }

    suspend fun consumeBundle(itemId: String) {
        inventoryApi.consumeInventoryBundle(itemId)
    }
}
