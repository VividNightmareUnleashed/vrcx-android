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
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
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
    accountScope: AccountScope,
) : AccountScoped {
    private val account = accountScope.bindTo(this)

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

    /** The `GET files` tag each image list is published under. */
    private val imageFlowsByTag = mapOf(
        "gallery" to _galleryImages,
        "icon" to _iconImages,
        "emoji" to _emojiImages,
        "sticker" to _stickerImages,
    )

    suspend fun loadGallery() = loadImages("gallery", account.current())

    suspend fun loadIcons() = loadImages("icon", account.current())

    suspend fun loadEmojis() = loadImages("emoji", account.current())

    suspend fun loadStickers() = loadImages("sticker", account.current())

    suspend fun loadPrints(userId: String) = loadPrints(userId, account.current())

    suspend fun loadInventory() = loadInventory(account.current())

    private suspend fun loadImages(tag: String, token: AccountScope.Token) {
        val destination = imageFlowsByTag.getValue(tag)
        val images = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, n ->
            galleryApi.getFileList(n = n, offset = offset, tag = tag)
        }.reversed()
        account.publishIfCurrent(token) { destination.value = images }
    }

    private suspend fun loadPrints(userId: String, token: AccountScope.Token) {
        val prints = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, n ->
            galleryApi.getPrints(userId, n = n, offset = offset)
        }
        account.publishIfCurrent(token) { _prints.value = prints }
    }

    private suspend fun loadInventory(token: AccountScope.Token) {
        var totalCount = 0
        val items = BulkPaginator.fetchAll(
            pageSize = INVENTORY_PAGE_SIZE,
            maxPages = MAX_INVENTORY_PAGES,
            stopOnShortPage = false,
            stopWhen = { _, fetched -> totalCount in 1..fetched },
        ) { offset, n ->
            account.ensureCurrent(token)
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
                            runCatchingCancellable {
                                account.ensureCurrent(token)
                                inventoryApi.getInventoryTemplate(templateId)
                            }.getOrNull()
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        account.publishIfCurrent(token) {
            _inventoryItems.value = items
            _inventoryTemplates.value = templates
        }
    }

    override fun clearRuntimeState() {
        imageFlowsByTag.values.forEach { it.value = emptyList() }
        _prints.value = emptyList()
        _inventoryItems.value = emptyList()
        _inventoryTemplates.value = emptyList()
    }

    suspend fun handleContentRefresh(contentType: String, userId: String) {
        val token = account.current()
        runIgnoringFailure {
            when {
                contentType in imageFlowsByTag -> loadImages(contentType, token)
                contentType == "print" || contentType == "prints" -> loadPrints(userId, token)
                contentType == "inventory" -> loadInventory(token)
            }
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
        private const val FILE_PAGE_SIZE = 100
        private const val MAX_FILE_PAGES = 10
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
