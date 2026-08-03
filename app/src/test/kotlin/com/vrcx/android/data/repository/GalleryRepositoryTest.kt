package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.InventoryTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GalleryRepositoryTest {

    @Test
    fun `late gallery result cannot repopulate state after account clear`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val repository = GalleryRepository(galleryApi, mock(), mock())
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(galleryApi.getFileList(tag = "gallery")).doSuspendableAnswer {
            requestStarted.complete(Unit)
            releaseRequest.await()
            listOf(GalleryImage(id = "file_old_account"))
        }

        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.loadGallery() }
        requestStarted.await()
        repository.clearRuntimeState()
        releaseRequest.complete(Unit)
        load.await()

        assertTrue(repository.galleryImages.value.isEmpty())
    }

    @Test
    fun `loadInventory paginates wrapped results and resolves each template once`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val userApi = mock<UserApi>()
        val repository = GalleryRepository(galleryApi, inventoryApi, userApi)
        val first = InventoryItem(id = "inv_first", templateId = "invt_shared")
        val second = InventoryItem(id = "inv_second", templateId = "invt_shared")
        val template = InventoryTemplate(id = "invt_shared", name = "Shared template")

        whenever(inventoryApi.getInventoryItems(n = 100, offset = 0, order = "newest"))
            .thenReturn(InventoryResponse(data = listOf(first), totalCount = 2))
        whenever(inventoryApi.getInventoryItems(n = 100, offset = 1, order = "newest"))
            .thenReturn(InventoryResponse(data = listOf(second), totalCount = 2))
        whenever(inventoryApi.getInventoryTemplate("invt_shared")).thenReturn(template)

        repository.loadInventory()

        assertEquals(listOf(first, second), repository.inventoryItems.value)
        assertEquals(listOf(template), repository.inventoryTemplates.value)
        verify(inventoryApi).getInventoryItems(n = 100, offset = 1, order = "newest")
        verify(inventoryApi).getInventoryTemplate("invt_shared")
    }

    @Test
    fun `defaultFileNameFor maps supported and fallback MIME types`() {
        listOf(
            "image/jpeg" to "image.jpg",
            "image/jpg" to "image.jpg",
            "Image/JPEG" to "image.jpg",
            "image/webp" to "image.webp",
            "image/gif" to "image.gif",
            "image/png" to "image.png",
            "application/octet-stream" to "image.png",
            "" to "image.png",
        ).forEach { (mimeType, expected) ->
            assertEquals(expected, GalleryRepository.defaultFileNameFor(mimeType))
        }
    }
}
