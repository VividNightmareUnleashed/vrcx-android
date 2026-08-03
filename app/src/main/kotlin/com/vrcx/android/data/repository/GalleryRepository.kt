package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val stateLock = Any()
    private var accountGeneration = 0L

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

    suspend fun loadGallery() = loadImages("gallery", currentGeneration(), _galleryImages)

    suspend fun loadIcons() = loadImages("icon", currentGeneration(), _iconImages)

    suspend fun loadEmojis() = loadImages("emoji", currentGeneration(), _emojiImages)

    suspend fun loadStickers() = loadImages("sticker", currentGeneration(), _stickerImages)

    suspend fun loadPrints(userId: String) = loadPrints(userId, currentGeneration())

    suspend fun loadInventory() = loadInventory(currentGeneration())

    private suspend fun loadImages(
        tag: String,
        generation: Long,
        destination: MutableStateFlow<List<GalleryImage>>,
    ) {
        val images = galleryApi.getFileList(tag = tag).reversed()
        publishIfCurrent(generation) { destination.value = images }
    }

    private suspend fun loadPrints(userId: String, generation: Long) {
        val prints = galleryApi.getPrints(userId)
        publishIfCurrent(generation) { _prints.value = prints }
    }

    private suspend fun loadInventory(generation: Long) {
        var totalCount = 0
        val items = BulkPaginator.fetchAll(
            pageSize = INVENTORY_PAGE_SIZE,
            maxPages = MAX_INVENTORY_PAGES,
            stopOnShortPage = false,
            stopWhen = { fetched -> totalCount in 1..fetched },
        ) { offset, n ->
            ensureCurrentGeneration(generation)
            val response = inventoryApi.getInventoryItems(n = n, offset = offset)
            if (response.totalCount > 0) totalCount = response.totalCount
            response.data
        }

        val semaphore = Semaphore(4)
        val templates = coroutineScope {
            items.map(InventoryItem::templateId)
                .filter(String::isNotBlank)
                .distinct()
                .map { templateId ->
                    async {
                        semaphore.withPermit {
                            try {
                                ensureCurrentGeneration(generation)
                                inventoryApi.getInventoryTemplate(templateId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        publishIfCurrent(generation) {
            _inventoryItems.value = items
            _inventoryTemplates.value = templates
        }
    }

    fun clearRuntimeState() {
        synchronized(stateLock) {
            accountGeneration++
            _galleryImages.value = emptyList()
            _iconImages.value = emptyList()
            _emojiImages.value = emptyList()
            _stickerImages.value = emptyList()
            _prints.value = emptyList()
            _inventoryItems.value = emptyList()
            _inventoryTemplates.value = emptyList()
        }
    }

    suspend fun handleContentRefresh(contentType: String, userId: String) {
        val generation = currentGeneration()
        try {
            when (contentType) {
                "gallery" -> loadImages("gallery", generation, _galleryImages)
                "icon" -> loadImages("icon", generation, _iconImages)
                "emoji" -> loadImages("emoji", generation, _emojiImages)
                "sticker" -> loadImages("sticker", generation, _stickerImages)
                "print", "prints" -> loadPrints(userId, generation)
                "inventory" -> loadInventory(generation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private fun currentGeneration(): Long = synchronized(stateLock) { accountGeneration }

    private fun ensureCurrentGeneration(generation: Long) {
        synchronized(stateLock) {
            if (generation != accountGeneration) {
                throw CancellationException("Gallery load invalidated by account change")
            }
        }
    }

    private inline fun publishIfCurrent(generation: Long, publish: () -> Unit) {
        synchronized(stateLock) {
            if (generation == accountGeneration) publish()
        }
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
