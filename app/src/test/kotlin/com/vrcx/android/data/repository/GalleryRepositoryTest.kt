package com.vrcx.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryRepositoryTest {

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
