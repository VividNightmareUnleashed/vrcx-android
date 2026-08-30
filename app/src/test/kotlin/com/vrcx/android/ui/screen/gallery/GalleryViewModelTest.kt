package com.vrcx.android.ui.screen.gallery

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.gallery.GalleryImageCategory
import com.vrcx.android.data.gallery.GalleryUploadCoordinator
import com.vrcx.android.data.gallery.GalleryUploadResult
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthenticatedSessionGate
import com.vrcx.android.data.repository.GalleryRepository
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
    fun `a delete that succeeds is never reported as a failure when the refresh fails`() = runTest(testDispatcher) {
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
    fun `a cancelled tab load clears its flags so the tab stays retryable`() = runTest(testDispatcher) {
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

    @Test
    fun `upload result keeps preparation and refresh failures distinct`() = runTest(testDispatcher) {
        val galleryApi = mock<GalleryApi>()
        whenever(galleryApi.getFileList(100, 0, "gallery")).thenReturn(emptyList())
        val coordinator = mock<GalleryUploadCoordinator>()
        val uri = mock<android.net.Uri>()
        whenever(coordinator.uploadImage(uri, GalleryImageCategory.GALLERY))
            .thenReturn(GalleryUploadResult.TooLarge)
        val viewModel = buildViewModel(galleryApi, coordinator)
        advanceUntilIdle()

        viewModel.uploadFile(uri, GalleryTab.GALLERY)
        advanceUntilIdle()

        assertEquals("Image too large (max 10 MB)", viewModel.snackbarMessage.value)

        whenever(coordinator.uploadImage(uri, GalleryImageCategory.GALLERY))
            .thenReturn(GalleryUploadResult.RefreshFailed(RuntimeException("timeout")))
        viewModel.uploadFile(uri, GalleryTab.GALLERY)
        advanceUntilIdle()

        assertEquals(
            "Image uploaded, but refresh failed: timeout",
            viewModel.snackbarMessage.value,
        )
    }

    private fun buildViewModel(
        galleryApi: GalleryApi,
        uploadCoordinator: GalleryUploadCoordinator = mock(),
    ): GalleryViewModel {
        val accountScope = AccountScope()
        return GalleryViewModel(
            galleryRepository = GalleryRepository(
                galleryApi = galleryApi,
                inventoryApi = mock<InventoryApi>(),
                userApi = mock<UserApi>(),
                cookieJar = mock(),
                accountScope = accountScope,
                authenticatedSessionGate = AuthenticatedSessionGate(accountScope),
            ),
            authRepository = mock<AuthRepository>(),
            uploadCoordinator = uploadCoordinator,
        )
    }
}
