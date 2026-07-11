package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.InventoryTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GalleryRepositoryTest {

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
    fun `defaultFileNameFor picks a JPEG extension for image jpeg`() {
        assertEquals("image.jpg", GalleryRepository.defaultFileNameFor("image/jpeg"))
        assertEquals("image.jpg", GalleryRepository.defaultFileNameFor("image/jpg"))
        // Mixed case should still resolve correctly.
        assertEquals("image.jpg", GalleryRepository.defaultFileNameFor("Image/JPEG"))
    }

    @Test
    fun `defaultFileNameFor picks a webp extension for image webp`() {
        assertEquals("image.webp", GalleryRepository.defaultFileNameFor("image/webp"))
    }

    @Test
    fun `defaultFileNameFor picks a gif extension for image gif`() {
        assertEquals("image.gif", GalleryRepository.defaultFileNameFor("image/gif"))
    }

    @Test
    fun `defaultFileNameFor falls back to png for image png and unknown types`() {
        assertEquals("image.png", GalleryRepository.defaultFileNameFor("image/png"))
        assertEquals("image.png", GalleryRepository.defaultFileNameFor("application/octet-stream"))
        assertEquals("image.png", GalleryRepository.defaultFileNameFor(""))
    }
}
