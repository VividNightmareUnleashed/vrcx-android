package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.VrcPrint

interface GalleryCollections {
    suspend fun loadGallery()

    suspend fun loadIcons()

    suspend fun loadEmojis()

    suspend fun loadStickers()

    suspend fun loadPrints(userId: String)

    suspend fun loadInventory()
}

interface GalleryMutations {
    suspend fun deleteFile(fileId: String)

    suspend fun deletePrint(printId: String)

    suspend fun uploadFile(
        tag: String,
        imageBytes: ByteArray,
        mimeType: String = "image/png",
        fileName: String = GalleryUploadFileNames.defaultFor(mimeType),
    ): GalleryImage

    suspend fun uploadPrint(
        imageBytes: ByteArray,
        note: String?,
        mimeType: String = "image/png",
        fileName: String = GalleryUploadFileNames.defaultFor(mimeType),
    ): VrcPrint

    suspend fun setProfilePicOverride(userId: String, fileId: String)

    suspend fun setUserIcon(userId: String, fileId: String)

    suspend fun consumeBundle(itemId: String)
}
