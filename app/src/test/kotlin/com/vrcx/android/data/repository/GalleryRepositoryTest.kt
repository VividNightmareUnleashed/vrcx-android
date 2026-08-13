package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
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
        val accountScope = AccountScope()
        val repository = GalleryRepository(galleryApi, mock(), mock(), accountScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(galleryApi.getFileList(tag = "gallery")).doSuspendableAnswer {
            requestStarted.complete(Unit)
            releaseRequest.await()
            listOf(GalleryImage(id = "file_old_account"))
        }

        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.loadGallery() }
        requestStarted.await()
        accountScope.invalidate()
        releaseRequest.complete(Unit)
        load.await()

        assertTrue(repository.galleryImages.value.isEmpty())
    }

    @Test
    fun `image lists page past the first hundred results`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val repository = GalleryRepository(galleryApi, mock(), mock(), AccountScope())
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "gallery"))
            .thenReturn(List(100) { GalleryImage(id = "file_$it") })
        whenever(galleryApi.getFileList(n = 100, offset = 100, tag = "gallery"))
            .thenReturn(listOf(GalleryImage(id = "file_100")))

        repository.loadGallery()

        val images = repository.galleryImages.value
        assertEquals(101, images.size)
        // The list is reversed as a whole, so the newest page leads.
        assertEquals("file_100", images.first().id)
        verify(galleryApi).getFileList(n = 100, offset = 100, tag = "gallery")
    }

    @Test
    fun `prints page past the first hundred results`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val repository = GalleryRepository(galleryApi, mock(), mock(), AccountScope())
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 0))
            .thenReturn(List(100) { VrcPrint(id = "prnt_$it") })
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 100))
            .thenReturn(listOf(VrcPrint(id = "prnt_100")))

        repository.loadPrints("usr_1")

        assertEquals(101, repository.prints.value.size)
        verify(galleryApi).getPrints("usr_1", n = 100, offset = 100)
    }

    @Test
    fun `content refresh accepts both print aliases and keeps loader failures to itself`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(galleryApi, mock(), mock(), accountScope)
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 0))
            .thenReturn(listOf(VrcPrint(id = "prnt_1")))
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "emoji"))
            .thenThrow(RuntimeException("emoji unavailable"))

        repository.handleContentRefresh("print", "usr_1")
        assertEquals(listOf("prnt_1"), repository.prints.value.map { it.id })

        accountScope.invalidate()
        repository.handleContentRefresh("prints", "usr_1")
        assertEquals(listOf("prnt_1"), repository.prints.value.map { it.id })

        // A pipeline-driven refresh must not surface an error to a screen that
        // did not ask for one, while the screen's own loader still does.
        repository.handleContentRefresh("emoji", "usr_1")
        assertTrue(runCatching { repository.loadEmojis() }.isFailure)
    }

    @Test
    fun `an account change mid-load aborts with an account change signal`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(galleryApi, inventoryApi, mock(), accountScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(inventoryApi.getInventoryItems(n = 100, offset = 0, order = "newest"))
            .doSuspendableAnswer {
                requestStarted.complete(Unit)
                releaseRequest.await()
                InventoryResponse(data = listOf(InventoryItem(id = "inv_first")), totalCount = 2)
            }

        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.loadInventory() }
        requestStarted.await()
        accountScope.invalidate()
        releaseRequest.complete(Unit)
        val error = runCatching { load.await() }.exceptionOrNull()

        assertTrue(error is AccountChangedException)
    }

    @Test
    fun `loadInventory paginates wrapped results and resolves each template once`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val userApi = mock<UserApi>()
        val repository = GalleryRepository(galleryApi, inventoryApi, userApi, AccountScope())
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
