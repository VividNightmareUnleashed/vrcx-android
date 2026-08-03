package com.vrcx.android.ui.screen.gallery

import android.content.Context
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
        assertFalse(iconState.isLoaded)
        assertEquals("icons unavailable", iconState.error)

        viewModel.selectTab(GalleryTab.GALLERY)

        assertTrue(viewModel.uiState.value.selectedTabState.isLoaded)
        assertEquals(null, viewModel.uiState.value.selectedTabState.error)
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
    fun `upload sample size uses powers of two`() {
        assertEquals(1, calculateUploadSampleSize(2_000, 2_000))
        assertEquals(2, calculateUploadSampleSize(8_000, 4_000))
        assertEquals(4, calculateUploadSampleSize(16_000, 8_000))
    }

    private fun buildViewModel(galleryApi: GalleryApi): GalleryViewModel = GalleryViewModel(
        galleryRepository = GalleryRepository(
            galleryApi = galleryApi,
            inventoryApi = mock<InventoryApi>(),
            userApi = mock<UserApi>(),
        ),
        authRepository = mock<AuthRepository>(),
        context = mock<Context>(),
    )
}
