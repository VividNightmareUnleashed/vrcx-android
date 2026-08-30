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

// Hard limits prevent malformed pagination responses from creating unbounded request loops.
private const val FILE_PAGE_SIZE = 100
private const val MAX_FILE_PAGES = 10
private const val INVENTORY_PAGE_SIZE = 100
private const val TEMPLATE_HYDRATION_CONCURRENCY = 4
private const val MAX_INVENTORY_PAGES = 100
internal const val PRINTS_DATASET = "prints"
internal const val INVENTORY_DATASET = "inventory"

internal class GalleryRepositoryComponents(
    galleryApi: GalleryApi,
    inventoryApi: InventoryApi,
    userApi: UserApi,
    cookieJar: CookieJarImpl,
    val account: AccountScope,
    authenticatedSessionGate: AuthenticatedSessionGate,
) {
    val state = GalleryDatasetState(account)
    val images = GalleryImageLoader(galleryApi, account, state)
    val prints = GalleryPrintLoader(galleryApi, account, state)
    val inventory = GalleryInventoryLoader(inventoryApi, account, state)
    val collections = GalleryCollectionLoader(account, images, prints, inventory)
    val uploader = GalleryUploader(galleryApi, cookieJar, account, authenticatedSessionGate)
    val mutations = GalleryMutationFacade(galleryApi, inventoryApi, userApi, account, uploader)
    val recovery = GalleryRecoveryCoordinator(account, state, images, prints, inventory)
}

internal class GalleryDatasetState(private val account: AccountScope) {
    private val datasetRevisions = ConcurrentHashMap<String, Long>()
    private val requestedDatasets = ConcurrentHashMap.newKeySet<String>()

    private val gallery = MutableStateFlow<List<GalleryImage>>(emptyList())
    private val icons = MutableStateFlow<List<GalleryImage>>(emptyList())
    private val emojis = MutableStateFlow<List<GalleryImage>>(emptyList())
    private val stickers = MutableStateFlow<List<GalleryImage>>(emptyList())
    private val printRows = MutableStateFlow<List<VrcPrint>>(emptyList())
    private val inventoryRows = MutableStateFlow<List<InventoryItem>>(emptyList())
    private val templateRows = MutableStateFlow<List<InventoryTemplate>>(emptyList())

    private val imagesByTag = mapOf(
        "gallery" to gallery,
        "icon" to icons,
        "emoji" to emojis,
        "sticker" to stickers,
    )

    val galleryImages: StateFlow<List<GalleryImage>> = gallery.asStateFlow()
    val iconImages: StateFlow<List<GalleryImage>> = icons.asStateFlow()
    val emojiImages: StateFlow<List<GalleryImage>> = emojis.asStateFlow()
    val stickerImages: StateFlow<List<GalleryImage>> = stickers.asStateFlow()
    val prints: StateFlow<List<VrcPrint>> = printRows.asStateFlow()
    val inventoryItems: StateFlow<List<InventoryItem>> = inventoryRows.asStateFlow()
    val inventoryTemplates: StateFlow<List<InventoryTemplate>> = templateRows.asStateFlow()
    val imageTags: Set<String> = imagesByTag.keys

    fun beginLoad(dataset: String, token: AccountScope.Token): Long {
        var revision: Long? = null
        account.publishOrAbort(token) {
            requestedDatasets += dataset
            revision = nextRevision(dataset)
        }
        return requireNotNull(revision)
    }

    fun beginRecovery(token: AccountScope.Token): Map<String, Long>? {
        var revisions: Map<String, Long>? = null
        account.publishIfCurrent(token) {
            revisions = requestedDatasets.associateWith(::nextRevision)
        }
        return revisions
    }

    fun publishImages(tag: String, revision: Long, token: AccountScope.Token, images: List<GalleryImage>) {
        account.publishIfCurrent(token) {
            if (revision == datasetRevisions[tag]) imagesByTag.getValue(tag).value = images
        }
    }

    fun publishPrints(revision: Long, token: AccountScope.Token, prints: List<VrcPrint>) {
        account.publishIfCurrent(token) {
            if (revision == datasetRevisions[PRINTS_DATASET]) printRows.value = prints
        }
    }

    fun publishInventory(
        revision: Long,
        token: AccountScope.Token,
        items: List<InventoryItem>,
        templates: List<InventoryTemplate>,
    ) {
        account.publishIfCurrent(token) {
            if (revision == datasetRevisions[INVENTORY_DATASET]) {
                inventoryRows.value = items
                templateRows.value = templates
            }
        }
    }

    fun clear() {
        datasetRevisions.clear()
        requestedDatasets.clear()
        imagesByTag.values.forEach { it.value = emptyList() }
        printRows.value = emptyList()
        inventoryRows.value = emptyList()
        templateRows.value = emptyList()
    }

    private fun nextRevision(dataset: String): Long =
        datasetRevisions.merge(dataset, 1L) { current, increment -> current + increment } ?: 1L
}

internal class GalleryImageLoader(
    private val galleryApi: GalleryApi,
    private val account: AccountScope,
    private val state: GalleryDatasetState,
) {
    suspend fun load(tag: String, token: AccountScope.Token) = load(tag, token, state.beginLoad(tag, token))

    suspend fun load(tag: String, token: AccountScope.Token, revision: Long) {
        val images = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, count ->
            account.ensureCurrent(token)
            galleryApi.getFileList(n = count, offset = offset, tag = tag)
        }.reversed()
        state.publishImages(tag, revision, token, images)
    }
}

internal class GalleryPrintLoader(
    private val galleryApi: GalleryApi,
    private val account: AccountScope,
    private val state: GalleryDatasetState,
) {
    suspend fun load(userId: String, token: AccountScope.Token) =
        load(userId, token, state.beginLoad(PRINTS_DATASET, token))

    suspend fun load(userId: String, token: AccountScope.Token, revision: Long) {
        val prints = BulkPaginator.fetchAll(
            pageSize = FILE_PAGE_SIZE,
            maxPages = MAX_FILE_PAGES,
        ) { offset, count ->
            account.ensureCurrent(token)
            galleryApi.getPrints(userId, n = count, offset = offset)
        }
        state.publishPrints(revision, token, prints)
    }
}

internal class GalleryInventoryLoader(
    private val inventoryApi: InventoryApi,
    private val account: AccountScope,
    private val state: GalleryDatasetState,
) {
    suspend fun load(token: AccountScope.Token) = load(token, state.beginLoad(INVENTORY_DATASET, token))

    suspend fun load(token: AccountScope.Token, revision: Long) {
        var totalCount = 0
        val items = BulkPaginator.fetchAll(
            pageSize = INVENTORY_PAGE_SIZE,
            maxPages = MAX_INVENTORY_PAGES,
            stopOnShortPage = false,
            stopWhen = { _, fetched -> totalCount in 1..fetched },
        ) { offset, count ->
            account.ensureCurrent(token)
            val response = inventoryApi.getInventoryItems(n = count, offset = offset)
            if (response.totalCount > 0) totalCount = response.totalCount
            response.data
        }
        state.publishInventory(revision, token, items, hydrateTemplates(items, token))
    }

    private suspend fun hydrateTemplates(
        items: List<InventoryItem>,
        token: AccountScope.Token,
    ): List<InventoryTemplate> {
        // Template hydration is one request per distinct item; bound the fan-out for large inventories.
        val semaphore = Semaphore(TEMPLATE_HYDRATION_CONCURRENCY)
        return coroutineScope {
            items.asSequence()
                .map(InventoryItem::templateId)
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
                .toList()
                .awaitAll()
                .filterNotNull()
        }
    }
}

internal class GalleryCollectionLoader(
    private val account: AccountScope,
    private val images: GalleryImageLoader,
    private val prints: GalleryPrintLoader,
    private val inventory: GalleryInventoryLoader,
) : GalleryCollections {
    override suspend fun loadGallery() = images.load("gallery", account.current())

    override suspend fun loadIcons() = images.load("icon", account.current())

    override suspend fun loadEmojis() = images.load("emoji", account.current())

    override suspend fun loadStickers() = images.load("sticker", account.current())

    override suspend fun loadPrints(userId: String) = prints.load(userId, account.current())

    override suspend fun loadInventory() = inventory.load(account.current())
}

internal class GalleryUploader(
    private val galleryApi: GalleryApi,
    private val cookieJar: CookieJarImpl,
    private val account: AccountScope,
    private val authenticatedSessionGate: AuthenticatedSessionGate,
) {
    suspend fun uploadFile(
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
        val cookies = authenticatedSessionGate.request(token) {
            cookieJar.snapshotForAccountBoundRequest()
        }
        account.ensureCurrent(token)
        return galleryApi.uploadFile(cookies, tagBody, imagePart)
    }

    suspend fun uploadPrint(
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
        val cookies = authenticatedSessionGate.request(token) {
            cookieJar.snapshotForAccountBoundRequest()
        }
        account.ensureCurrent(token)
        return galleryApi.uploadPrint(cookies, imagePart, noteBody)
    }
}

internal class GalleryMutationFacade(
    private val galleryApi: GalleryApi,
    private val inventoryApi: InventoryApi,
    private val userApi: UserApi,
    private val account: AccountScope,
    private val uploader: GalleryUploader,
) : GalleryMutations {
    override suspend fun deleteFile(fileId: String) {
        galleryApi.deleteFile(fileId)
    }

    override suspend fun deletePrint(printId: String) {
        galleryApi.deletePrint(printId)
    }

    override suspend fun uploadFile(
        tag: String,
        imageBytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): GalleryImage = uploader.uploadFile(tag, imageBytes, mimeType, fileName, account.current())

    override suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String,
        fileName: String,
    ): VrcPrint = uploader.uploadPrint(imageBytes, note, mimeType, fileName, account.current())

    override suspend fun setProfilePicOverride(userId: String, fileId: String) {
        userApi.saveCurrentUser(
            userId,
            UpdateCurrentUserRequest(profilePicOverride = fileVersionUrl(fileId)),
        )
    }

    override suspend fun setUserIcon(userId: String, fileId: String) {
        userApi.saveCurrentUser(
            userId,
            UpdateCurrentUserRequest(userIcon = fileVersionUrl(fileId)),
        )
    }

    override suspend fun consumeBundle(itemId: String) {
        inventoryApi.consumeInventoryBundle(itemId)
    }
}

internal class GalleryRecoveryCoordinator(
    private val account: AccountScope,
    private val state: GalleryDatasetState,
    private val images: GalleryImageLoader,
    private val prints: GalleryPrintLoader,
    private val inventory: GalleryInventoryLoader,
) {
    suspend fun resynchronize(token: AccountScope.Token) {
        val revisions = state.beginRecovery(token) ?: return
        val failures = supervisorScope {
            revisions.map { (dataset, revision) ->
                async { captureFailure { refresh(dataset, revision, token) } }
            }.awaitAll().filterNotNull()
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    suspend fun handleContentRefresh(contentType: String, userId: String, token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        runIgnoringFailure {
            when {
                contentType in state.imageTags -> images.load(contentType, token)
                contentType == "print" || contentType == PRINTS_DATASET -> prints.load(userId, token)
                contentType == INVENTORY_DATASET -> inventory.load(token)
            }
        }
    }

    private suspend fun refresh(dataset: String, revision: Long, token: AccountScope.Token) {
        when {
            dataset in state.imageTags -> images.load(dataset, token, revision)
            dataset == PRINTS_DATASET -> prints.load(token.ownerUserId, token, revision)
            dataset == INVENTORY_DATASET -> inventory.load(token, revision)
        }
    }
}

private fun fileVersionUrl(fileId: String): String =
    if (fileId.isEmpty()) "" else "https://api.vrchat.cloud/api/1/file/$fileId/1"
