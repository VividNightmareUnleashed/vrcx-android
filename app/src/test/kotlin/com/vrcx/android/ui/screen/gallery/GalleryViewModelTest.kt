package com.vrcx.android.ui.screen.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryViewModelTest {

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
}
