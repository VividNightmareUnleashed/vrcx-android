package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.UpdateCurrentUserRequest
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class GalleryRepository @Inject constructor(
    private val galleryApi: GalleryApi,
    private val inventoryApi: InventoryApi,
    private val userApi: UserApi,
    private val cookieJar: CookieJarImpl,
    accountScope: AccountScope,
    private val authenticatedSessionGate: AuthenticatedSessionGate,
) : AccountScoped {
    private val account = accountScope.bindTo(this)
    private val datasetRevisions = ConcurrentHashMap<String, Long>()
    private val requestedDatasets = ConcurrentHashMap.newKeySet<String>()

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

    suspend fun loadGallery() = loadGallery(account.current())

    internal suspend fun loadGallery(token: AccountScope.Token) = loadImages("gallery", token)

    suspend fun loadIcons() = loadIcons(account.current())

    internal suspend fun loadIcons(token: AccountScope.Token) = loadImages("icon", token)

    suspend fun loadEmojis() = loadEmojis(account.current())

    internal suspend fun loadEmojis(token: AccountScope.Token) = loadImages("emoji", token)

    suspend fun loadStickers() = loadStickers(account.current())

    internal suspend fun loadStickers(token: AccountScope.Token) = loadImages("sticker", token)

    suspend fun loadPrints(userId: String) = loadPrints(userId, account.current())

    internal suspend fun loadPrints(userId: String, token: AccountScope.Token) = loadPrintsAtRevision(userId, token)

    suspend fun loadInventory() = loadInventory(account.current())

    internal suspend fun resynchronize(token: AccountScope.Token) {
        val revisions = beginRecoveryLoads(token) ?: return
        val failures = supervisorScope {
            revisions.map { (dataset, revision) ->
                async {
                    captureFailure {
                        refreshDataset(dataset, revision, token)
                    }
                }
            }
                .awaitAll()
                .filterNotNull()
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private suspend fun loadImages(tag: String, token: AccountScope.Token) =
        loadImages(tag, token, beginDatasetLoad(tag, token))

    private suspend fun loadImages(tag: String, token: AccountScope.Token, revision: Long) {
        val destination = imageFlowsByTag.getValue(tag)
        val images = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, n ->
            account.ensureCurrent(token)
            galleryApi.getFileList(n = n, offset = offset, tag = tag)
        }.reversed()
        account.publishIfCurrent(token) {
            if (revision == datasetRevisions[tag]) destination.value = images
        }
    }

    private suspend fun loadPrintsAtRevision(userId: String, token: AccountScope.Token) =
        loadPrintsAtRevision(userId, token, beginDatasetLoad(PRINTS_DATASET, token))

    private suspend fun loadPrintsAtRevision(userId: String, token: AccountScope.Token, revision: Long) {
        val prints = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, n ->
            account.ensureCurrent(token)
            galleryApi.getPrints(userId, n = n, offset = offset)
        }
        account.publishIfCurrent(token) {
            if (revision == datasetRevisions[PRINTS_DATASET]) _prints.value = prints
        }
    }

    private suspend fun loadInventory(token: AccountScope.Token) =
        loadInventory(token, beginDatasetLoad(INVENTORY_DATASET, token))

    private suspend fun loadInventory(token: AccountScope.Token, revision: Long) {
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

        // Template hydration is one request per distinct item; bounding the
        // fan-out avoids a request burst on large inventories.
        val semaphore = Semaphore(TEMPLATE_HYDRATION_CONCURRENCY)
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
            if (revision == datasetRevisions[INVENTORY_DATASET]) {
                _inventoryItems.value = items
                _inventoryTemplates.value = templates
            }
        }
    }

    private suspend fun refreshDataset(dataset: String, revision: Long, token: AccountScope.Token) {
        when {
            dataset in imageFlowsByTag -> loadImages(dataset, token, revision)
            dataset == PRINTS_DATASET -> loadPrintsAtRevision(token.ownerUserId, token, revision)
            dataset == INVENTORY_DATASET -> loadInventory(token, revision)
        }
    }

    override fun clearRuntimeState() {
        datasetRevisions.clear()
        requestedDatasets.clear()
        imageFlowsByTag.values.forEach { it.value = emptyList() }
        _prints.value = emptyList()
        _inventoryItems.value = emptyList()
        _inventoryTemplates.value = emptyList()
    }

    suspend fun handleContentRefresh(contentType: String, userId: String, token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        runIgnoringFailure {
            when {
                contentType in imageFlowsByTag -> loadImages(contentType, token)
                contentType == "print" || contentType == "prints" -> loadPrints(userId, token)
                contentType == "inventory" -> loadInventory(token)
            }
        }
    }

    private fun beginDatasetLoad(dataset: String, token: AccountScope.Token): Long {
        var revision: Long? = null
        account.publishOrAbort(token) {
            requestedDatasets += dataset
            revision = nextDatasetRevision(dataset)
        }
        return requireNotNull(revision)
    }

    private fun beginRecoveryLoads(token: AccountScope.Token): Map<String, Long>? {
        var revisions: Map<String, Long>? = null
        account.publishIfCurrent(token) {
            revisions = requestedDatasets.associateWith(::nextDatasetRevision)
        }
        return revisions
    }

    private fun nextDatasetRevision(dataset: String): Long =
        datasetRevisions.merge(dataset, 1L) { current, increment -> current + increment } ?: 1L

    internal suspend fun handleContentRefresh(contentType: String, userId: String) {
        handleContentRefresh(contentType, userId, account.current())
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
    ): GalleryImage = uploadFile(tag, imageBytes, mimeType, fileName, account.current())

    internal suspend fun uploadFile(
        tag: String,
        imageBytes: ByteArray,
        mimeType: String,
        fileName: String,
        token: AccountScope.Token,
    ): GalleryImage {
        account.ensureCurrent(token)
        val tagBody = tag.toRequestBody("text/plain".toMediaType())
        val imagePart = MultipartBody.Part.createFormData(
            "file",
            fileName,
            imageBytes.toRequestBody(mimeType.toMediaType()),
        )
        account.ensureCurrent(token)
        val accountCookies = authenticatedSessionGate.request(token) {
            cookieJar.snapshotForAccountBoundRequest()
        }
        account.ensureCurrent(token)
        return galleryApi.uploadFile(accountCookies, tagBody, imagePart)
    }

    suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String = "image/png",
        fileName: String = defaultFileNameFor(mimeType),
    ): VrcPrint = uploadPrint(imageBytes, note, mimeType, fileName, account.current())

    internal suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String,
        fileName: String,
        token: AccountScope.Token,
    ): VrcPrint {
        account.ensureCurrent(token)
        val imagePart = MultipartBody.Part.createFormData(
            "image",
            fileName,
            imageBytes.toRequestBody(mimeType.toMediaType()),
        )
        val noteBody = note?.toRequestBody("text/plain".toMediaType())
        account.ensureCurrent(token)
        val accountCookies = authenticatedSessionGate.request(token) {
            cookieJar.snapshotForAccountBoundRequest()
        }
        account.ensureCurrent(token)
        return galleryApi.uploadPrint(accountCookies, imagePart, noteBody)
    }

    internal companion object {
        // Hard stops keep malformed pagination responses from driving
        // unbounded API requests.
        private const val FILE_PAGE_SIZE = 100
        private const val MAX_FILE_PAGES = 10
        private const val INVENTORY_PAGE_SIZE = 100
        private const val TEMPLATE_HYDRATION_CONCURRENCY = 4
        private const val MAX_INVENTORY_PAGES = 100
        private const val PRINTS_DATASET = "prints"
        private const val INVENTORY_DATASET = "inventory"

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
