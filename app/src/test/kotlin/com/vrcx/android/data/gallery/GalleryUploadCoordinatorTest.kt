package com.vrcx.android.data.gallery

import android.net.Uri
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.data.content.ContentImageService
import com.vrcx.android.data.content.GalleryPreparationResult
import com.vrcx.android.data.content.PreparedGalleryUpload
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GalleryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GalleryUploadCoordinatorTest {
    private val uri = mock<Uri>()
    private val prepared = PreparedGalleryUpload(
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/jpeg",
        fileName = "shot.jpg",
    )
    private val contentImageService = mock<ContentImageService>()
    private val galleryRepository = mock<GalleryRepository>()
    private val authRepository = mock<AuthRepository>()
    private val accountScope = AccountScope().apply { bind("usr_me") }
    private val coordinator = GalleryUploadCoordinator(
        contentImageService,
        galleryRepository,
        authRepository,
        accountScope,
    )

    @Test
    fun `image upload prepares sends and refreshes the matching category`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(
            galleryRepository.uploadFile(
                eq("icon"),
                eq(prepared.bytes),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenReturn(GalleryImage())

        val result = coordinator.uploadImage(uri, GalleryImageCategory.ICON)

        assertEquals(GalleryUploadResult.Uploaded, result)
        verify(contentImageService).prepareGalleryUpload(uri)
        verify(galleryRepository).uploadFile(
            eq("icon"),
            eq(prepared.bytes),
            eq("image/jpeg"),
            eq("shot.jpg"),
            any(),
        )
        verify(galleryRepository).loadIcons(any())
        verify(galleryRepository, never()).loadGallery(any())
    }

    @Test
    fun `obsolete selection is discarded after preparation and before upload`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY) { false }

        assertEquals(GalleryUploadResult.Obsolete, result)
        verify(galleryRepository, never()).uploadFile(any(), any(), any(), any(), any())
        verify(galleryRepository, never()).loadGallery(any())
    }

    @Test
    fun `refresh failure is distinct from an accepted upload`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(
            galleryRepository.uploadFile(
                eq("gallery"),
                eq(prepared.bytes),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenReturn(GalleryImage())
        whenever(galleryRepository.loadGallery(any())).thenThrow(RuntimeException("timeout"))

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)

        assertEquals(
            "timeout",
            (result as GalleryUploadResult.RefreshFailed).error.message,
        )
    }

    @Test
    fun `refresh cancellation is not converted to a failed refresh`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(
            galleryRepository.uploadFile(
                eq("gallery"),
                eq(prepared.bytes),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenReturn(GalleryImage())
        whenever(galleryRepository.loadGallery(any())).thenThrow(CancellationException("cancelled"))

        val error = try {
            coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)
            null
        } catch (e: CancellationException) {
            e
        }

        assertEquals("cancelled", error?.message)
    }

    @Test
    fun `unusable content never reaches the API`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.TooLarge)

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)

        assertEquals(GalleryUploadResult.TooLarge, result)
        verify(galleryRepository, never()).uploadFile(any(), any(), any(), any(), any())
    }

    @Test
    fun `print upload normalizes blank note and refreshes the current user`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(authRepository.authState).thenReturn(
            MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_me"))),
        )
        whenever(
            galleryRepository.uploadPrint(
                eq(prepared.bytes),
                eq(null),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenReturn(VrcPrint())

        val result = coordinator.uploadPrint(uri, "   ")

        assertEquals(GalleryUploadResult.Uploaded, result)
        verify(galleryRepository).uploadPrint(
            eq(prepared.bytes),
            eq(null),
            eq("image/jpeg"),
            eq("shot.jpg"),
            any(),
        )
        verify(galleryRepository).loadPrints(eq("usr_me"), any())
    }

    @Test
    fun `accepted print reports when refresh no longer has a session`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(authRepository.authState).thenReturn(MutableStateFlow(AuthState.NotLoggedIn))
        whenever(
            galleryRepository.uploadPrint(
                eq(prepared.bytes),
                eq(null),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenReturn(VrcPrint())

        val result = coordinator.uploadPrint(uri, null)

        assertEquals(GalleryUploadResult.RefreshRequiresAuthentication, result)
        verify(galleryRepository, never()).loadPrints(any(), any())
    }

    @Test
    fun `account switch while preparing prevents the upload`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri)).thenAnswer {
            accountScope.invalidate()
            accountScope.bind("usr_other")
            GalleryPreparationResult.Success(prepared)
        }

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)

        assertEquals(GalleryUploadResult.Obsolete, result)
        verify(galleryRepository, never()).uploadFile(any(), any(), any(), any(), any())
    }

    @Test
    fun `account switch at the pre-send relevance check prevents the upload`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY) {
            accountScope.invalidate()
            accountScope.bind("usr_other")
            true
        }

        assertEquals(GalleryUploadResult.Obsolete, result)
        verify(galleryRepository, never()).uploadFile(any(), any(), any(), any(), any())
    }

    @Test
    fun `account switch during an accepted send prevents a new-account refresh`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(
            galleryRepository.uploadFile(
                eq("gallery"),
                eq(prepared.bytes),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenAnswer {
            accountScope.invalidate()
            accountScope.bind("usr_other")
            GalleryImage()
        }

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)

        assertEquals(GalleryUploadResult.Obsolete, result)
        verify(galleryRepository, never()).loadGallery(any())
    }

    @Test
    fun `account invalidation reported by the send prevents refresh`() = runTest {
        whenever(contentImageService.prepareGalleryUpload(uri))
            .thenReturn(GalleryPreparationResult.Success(prepared))
        whenever(
            galleryRepository.uploadFile(
                eq("gallery"),
                eq(prepared.bytes),
                eq("image/jpeg"),
                eq("shot.jpg"),
                any(),
            ),
        ).thenThrow(AccountChangedException())

        val result = coordinator.uploadImage(uri, GalleryImageCategory.GALLERY)

        assertEquals(GalleryUploadResult.Obsolete, result)
        verify(galleryRepository, never()).loadGallery(any())
    }
}
