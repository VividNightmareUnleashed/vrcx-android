package com.vrcx.android.ui.screen.gallery

import android.content.Context
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import com.vrcx.android.ui.common.isLoaded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `default tab loads alone and each selected tab loads once`() = runTest(testDispatcher) {
        val galleryApi = mock<GalleryApi>()
        whenever(galleryApi.getFileList(100, 0, "gallery")).thenReturn(emptyList())
        whenever(galleryApi.getFileList(100, 0, "icon")).thenReturn(emptyList())
        val viewModel = buildViewModel(galleryApi)

        advanceUntilIdle()

        verify(galleryApi).getFileList(100, 0, "gallery")
        verify(galleryApi, never()).getFileList(100, 0, "icon")
        assertTrue(viewModel.uiState.value.tabs.getValue(GalleryTab.GALLERY).isLoaded)
        assertFalse(viewModel.uiState.value.tabs.getValue(GalleryTab.ICONS).isLoaded)

        viewModel.selectTab(GalleryTab.ICONS)
        viewModel.selectTab(GalleryTab.ICONS)
        advanceUntilIdle()
        viewModel.selectTab(GalleryTab.GALLERY)
        viewModel.selectTab(GalleryTab.ICONS)
        advanceUntilIdle()

        verify(galleryApi, times(1)).getFileList(100, 0, "icon")
    }

    @Test
    fun `tab failure stays scoped and does not blank a loaded tab`() = runTest(testDispatcher) {
        val galleryApi = mock<GalleryApi>()
        whenever(galleryApi.getFileList(100, 0, "gallery")).thenReturn(emptyList())
        whenever(galleryApi.getFileList(100, 0, "icon"))
            .thenThrow(RuntimeException("icons unavailable"))
        val viewModel = buildViewModel(galleryApi)
        advanceUntilIdle()

        viewModel.selectTab(GalleryTab.ICONS)
        advanceUntilIdle()

        val iconState = viewModel.uiState.value.tabs.getValue(GalleryTab.ICONS)
        assertEquals(LoadState.Failed("icons unavailable"), iconState)

        viewModel.selectTab(GalleryTab.GALLERY)

        assertEquals(LoadState.Loaded(Unit), viewModel.uiState.value.selectedTabState)
    }

    @Test
    fun `refresh reloads only the selected tab`() = runTest(testDispatcher) {
        val galleryApi = mock<GalleryApi>()
        whenever(galleryApi.getFileList(100, 0, "gallery")).thenReturn(emptyList())
        whenever(galleryApi.getFileList(100, 0, "icon")).thenReturn(emptyList())
        val viewModel = buildViewModel(galleryApi)
        advanceUntilIdle()

        viewModel.selectTab(GalleryTab.ICONS)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        verify(galleryApi, times(1)).getFileList(100, 0, "gallery")
        verify(galleryApi, times(2)).getFileList(100, 0, "icon")
    }

    @Test
    fun `upload dimensions preserve aspect ratio within two thousand pixels`() {
        assertEquals(2_000 to 1_000, fitUploadDimensions(4_000, 2_000))
        assertEquals(1_000 to 2_000, fitUploadDimensions(2_000, 4_000))
        assertEquals(800 to 600, fitUploadDimensions(800, 600))
    }

    @Test
    fun `upload sample size lands the decode at or below the max dimension`() {
        assertEquals(1, calculateUploadSampleSize(2_000, 2_000))
        assertEquals(4, calculateUploadSampleSize(8_000, 4_000))
        assertEquals(8, calculateUploadSampleSize(16_000, 8_000))

        // What the sample size is for: the decoded bitmap never exceeds the
        // ceiling on either edge, so the decode cannot allocate 64 MB for a
        // 10 MB upload.
        listOf(2_001 to 1_000, 8_000 to 4_000, 16_000 to 8_000, 5_000 to 12_000).forEach { (w, h) ->
            val sample = calculateUploadSampleSize(w, h)
            assertTrue("$w x $h sampled by $sample", w / sample <= 2_000 && h / sample <= 2_000)
        }
    }

    @Test
    fun `an oversized metadata size is rejected before any stream is opened`() {
        var opened = false
        val result = readBoundedUpload(
            metadataSize = 40_000_000L,
            mimeType = "image/png",
            fileName = "big.png",
        ) {
            opened = true
            ByteArrayInputStream(ByteArray(0))
        }

        assertEquals(UploadReadResult.TooLarge, result)
        assertFalse("the file was read despite its declared size", opened)
    }

    @Test
    fun `the read is bounded even when the metadata size lies`() {
        // Providers are allowed to report nothing, or to under-report. The read
        // itself is what has to refuse an oversized file.
        val result = readBoundedUpload(
            metadataSize = null,
            mimeType = "image/png",
            fileName = "lies.png",
        ) {
            ByteArrayInputStream(ByteArray(MAX_UPLOAD_SIZE_BYTES + 1))
        }

        assertEquals(UploadReadResult.TooLarge, result)
    }

    @Test
    fun `a readable image within the ceiling carries its name and type through`() {
        val result = readBoundedUpload(
            metadataSize = 3L,
            mimeType = "image/jpeg",
            fileName = "shot.jpg",
        ) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        val upload = (result as UploadReadResult.Success).upload
        assertEquals("shot.jpg", upload.fileName)
        assertEquals("image/jpeg", upload.mimeType)
        assertEquals(3, upload.bytes.size)
    }

    @Test
    fun `an unopenable image is unreadable rather than too large`() {
        val result = readBoundedUpload(
            metadataSize = null,
            mimeType = "image/png",
            fileName = "gone.png",
        ) { null }

        assertEquals(UploadReadResult.Unreadable, result)
    }

    @Test
    fun `a delete that succeeds is never reported as a failure when the refresh fails`() =
        runTest(testDispatcher) {
            val galleryApi = mock<GalleryApi>()
            whenever(galleryApi.getFileList(100, 0, "gallery"))
                .thenReturn(emptyList())
                .thenThrow(RuntimeException("timeout"))
            val viewModel = buildViewModel(galleryApi)
            advanceUntilIdle()

            viewModel.deleteFile("file_1", GalleryTab.GALLERY)
            advanceUntilIdle()

            verify(galleryApi).deleteFile("file_1")
            assertEquals(
                "Image deleted, but refresh failed: timeout",
                viewModel.snackbarMessage.value,
            )
        }

    @Test
    fun `a cancelled tab load clears its flags so the tab stays retryable`() =
        runTest(testDispatcher) {
            val galleryApi = mock<GalleryApi>()
            whenever(galleryApi.getFileList(100, 0, "gallery")).thenReturn(emptyList())
            whenever(galleryApi.getFileList(100, 0, "icon")).doSuspendableAnswer {
                throw AccountChangedException("Gallery load invalidated by account change")
            }
            val viewModel = buildViewModel(galleryApi)
            advanceUntilIdle()

            viewModel.selectTab(GalleryTab.ICONS)
            advanceUntilIdle()

            // Nothing was decided, so the tab is back where it started rather
            // than stuck mid-load.
            assertEquals(
                LoadState.NotLoaded,
                viewModel.uiState.value.tabs.getValue(GalleryTab.ICONS),
            )

            // The load guard keys off that state, so a retry has to get through.
            viewModel.retry()
            advanceUntilIdle()
            verify(galleryApi, times(2)).getFileList(100, 0, "icon")
        }

    private fun buildViewModel(galleryApi: GalleryApi): GalleryViewModel = GalleryViewModel(
        galleryRepository = GalleryRepository(
            galleryApi = galleryApi,
            inventoryApi = mock<InventoryApi>(),
            userApi = mock<UserApi>(),
            accountScope = AccountScope(),
        ),
        authRepository = mock<AuthRepository>(),
        context = mock<Context>(),
    )
}
