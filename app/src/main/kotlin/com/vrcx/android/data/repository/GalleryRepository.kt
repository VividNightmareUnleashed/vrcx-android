package com.vrcx.android.data.repository

import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Singleton
class GalleryRepository private constructor(
    private val components: GalleryRepositoryComponents,
    accountScope: AccountScope,
) : AccountScoped,
    GalleryCollections by components.collections,
    GalleryMutations by components.mutations {

    @Inject
    constructor(
        galleryApi: GalleryApi,
        inventoryApi: InventoryApi,
        userApi: UserApi,
        cookieJar: CookieJarImpl,
        accountScope: AccountScope,
        authenticatedSessionGate: AuthenticatedSessionGate,
    ) : this(
        components = GalleryRepositoryComponents(
            galleryApi,
            inventoryApi,
            userApi,
            cookieJar,
            accountScope,
            authenticatedSessionGate,
        ),
        accountScope = accountScope,
    )

    init {
        accountScope.bindTo(this)
    }

    val galleryImages: StateFlow<List<GalleryImage>> = components.state.galleryImages
    val iconImages: StateFlow<List<GalleryImage>> = components.state.iconImages
    val emojiImages: StateFlow<List<GalleryImage>> = components.state.emojiImages
    val stickerImages: StateFlow<List<GalleryImage>> = components.state.stickerImages
    val prints: StateFlow<List<VrcPrint>> = components.state.prints
    val inventoryItems: StateFlow<List<InventoryItem>> = components.state.inventoryItems
    val inventoryTemplates: StateFlow<List<InventoryTemplate>> = components.state.inventoryTemplates

    internal val currentToken: AccountScope.Token get() = components.account.current()

    internal suspend fun loadGallery(token: AccountScope.Token) = components.images.load("gallery", token)

    internal suspend fun loadIcons(token: AccountScope.Token) = components.images.load("icon", token)

    internal suspend fun loadEmojis(token: AccountScope.Token) = components.images.load("emoji", token)

    internal suspend fun loadStickers(token: AccountScope.Token) = components.images.load("sticker", token)

    internal suspend fun loadPrints(userId: String, token: AccountScope.Token) = components.prints.load(userId, token)

    internal suspend fun resynchronize(token: AccountScope.Token) {
        components.recovery.resynchronize(token)
    }

    override fun clearRuntimeState() {
        components.state.clear()
    }

    suspend fun handleContentRefresh(contentType: String, userId: String, token: AccountScope.Token) {
        components.recovery.handleContentRefresh(contentType, userId, token)
    }

    internal suspend fun uploadFile(
        tag: String,
        imageBytes: ByteArray,
        mimeType: String,
        fileName: String,
        token: AccountScope.Token,
    ): GalleryImage = components.uploader.uploadFile(tag, imageBytes, mimeType, fileName, token)

    internal suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String,
        fileName: String,
        token: AccountScope.Token,
    ): VrcPrint = components.uploader.uploadPrint(imageBytes, note, mimeType, fileName, token)
}

internal suspend fun GalleryRepository.handleContentRefresh(contentType: String, userId: String) {
    handleContentRefresh(contentType, userId, currentToken)
}
