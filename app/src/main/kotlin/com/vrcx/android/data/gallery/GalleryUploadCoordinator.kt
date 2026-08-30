package com.vrcx.android.data.gallery

import android.net.Uri
import com.vrcx.android.data.content.ContentImageService
import com.vrcx.android.data.content.GalleryPreparationResult
import com.vrcx.android.data.content.PreparedGalleryUpload
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class GalleryImageCategory(internal val apiTag: String) {
    GALLERY("gallery"),
    ICON("icon"),
    EMOJI("emoji"),
    STICKER("sticker"),
}

sealed interface GalleryUploadResult {
    data object Uploaded : GalleryUploadResult
    data object TooLarge : GalleryUploadResult
    data object Unreadable : GalleryUploadResult
    data object Obsolete : GalleryUploadResult
    data object RefreshRequiresAuthentication : GalleryUploadResult
    data class RefreshFailed(val error: Exception) : GalleryUploadResult
}

/** Owns the complete prepare, upload, and refresh transaction for picked images. */
@Singleton
class GalleryUploadCoordinator @Inject constructor(
    private val contentImageService: ContentImageService,
    private val galleryRepository: GalleryRepository,
    private val authRepository: AuthRepository,
    private val accountScope: AccountScope,
) {
    suspend fun uploadImage(uri: Uri, category: GalleryImageCategory): GalleryUploadResult =
        uploadImage(uri, category) { true }

    suspend fun uploadImage(
        uri: Uri,
        category: GalleryImageCategory,
        isStillRelevant: () -> Boolean,
    ): GalleryUploadResult = upload(
        uri = uri,
        isStillRelevant = isStillRelevant,
        send = { prepared, token ->
            galleryRepository.uploadFile(
                category.apiTag,
                prepared.bytes,
                prepared.mimeType,
                prepared.fileName,
                token,
            )
        },
        refresh = { token ->
            when (category) {
                GalleryImageCategory.GALLERY -> galleryRepository.loadGallery(token)
                GalleryImageCategory.ICON -> galleryRepository.loadIcons(token)
                GalleryImageCategory.EMOJI -> galleryRepository.loadEmojis(token)
                GalleryImageCategory.STICKER -> galleryRepository.loadStickers(token)
            }
            GalleryUploadResult.Uploaded
        },
    )

    suspend fun uploadPrint(uri: Uri, note: String?): GalleryUploadResult = upload(
        uri = uri,
        send = { prepared, token ->
            galleryRepository.uploadPrint(
                prepared.bytes,
                note?.ifBlank { null },
                prepared.mimeType,
                prepared.fileName,
                token,
            )
        },
        refresh = { token ->
            val userId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
            if (userId == null) {
                GalleryUploadResult.RefreshRequiresAuthentication
            } else if (userId != token.ownerUserId) {
                GalleryUploadResult.Obsolete
            } else {
                galleryRepository.loadPrints(userId, token)
                GalleryUploadResult.Uploaded
            }
        },
    )

    private suspend fun upload(
        uri: Uri,
        isStillRelevant: () -> Boolean = { true },
        send: suspend (PreparedGalleryUpload, AccountScope.Token) -> Unit,
        refresh: suspend (AccountScope.Token) -> GalleryUploadResult,
    ): GalleryUploadResult {
        val token = accountScope.current()
        return when (val preparation = contentImageService.prepareGalleryUpload(uri)) {
            GalleryPreparationResult.TooLarge -> GalleryUploadResult.TooLarge

            GalleryPreparationResult.Unreadable -> GalleryUploadResult.Unreadable

            is GalleryPreparationResult.Success -> uploadPrepared(
                prepared = preparation.upload,
                token = token,
                isStillRelevant = isStillRelevant,
                send = send,
                refresh = refresh,
            )
        }
    }

    private suspend fun uploadPrepared(
        prepared: PreparedGalleryUpload,
        token: AccountScope.Token,
        isStillRelevant: () -> Boolean,
        send: suspend (PreparedGalleryUpload, AccountScope.Token) -> Unit,
        refresh: suspend (AccountScope.Token) -> GalleryUploadResult,
    ): GalleryUploadResult = if (!isStillRelevant() || !accountScope.isCurrent(token)) {
        GalleryUploadResult.Obsolete
    } else {
        val sent = sendPrepared(prepared, token, send)
        if (sent && accountScope.isCurrent(token)) {
            refreshAfterUpload(token, refresh)
        } else {
            GalleryUploadResult.Obsolete
        }
    }

    private suspend fun sendPrepared(
        prepared: PreparedGalleryUpload,
        token: AccountScope.Token,
        send: suspend (PreparedGalleryUpload, AccountScope.Token) -> Unit,
    ): Boolean = try {
        send(prepared, token)
        true
    } catch (_: AccountChangedException) {
        false
    }

    // Any refresh failure must be returned because the remote upload has already succeeded.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshAfterUpload(
        token: AccountScope.Token,
        refresh: suspend (AccountScope.Token) -> GalleryUploadResult,
    ): GalleryUploadResult = try {
        val result = refresh(token)
        if (accountScope.isCurrent(token)) result else GalleryUploadResult.Obsolete
    } catch (_: AccountChangedException) {
        GalleryUploadResult.Obsolete
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GalleryUploadResult.RefreshFailed(e)
    }
}
